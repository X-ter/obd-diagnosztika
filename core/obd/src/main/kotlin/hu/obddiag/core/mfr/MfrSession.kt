package hu.obddiag.core.mfr

import hu.obddiag.core.ObdClient
import hu.obddiag.core.elm.ElmResult
import hu.obddiag.core.elm.ObdProtocol
import hu.obddiag.core.util.CommLog

/** Egy gyártói azonosító olvasásának eredménye. */
sealed class MfrReadResult {
    /** A szolgáltatás- és azonosítóbájtok utáni adat. */
    class Ok(val payload: ByteArray) : MfrReadResult()
    data class Negative(val nrc: Int) : MfrReadResult() {
        /** 0x11/0x12/0x31: a vezérlő nem ismeri az azonosítót. */
        val notSupported: Boolean get() = nrc == 0x11 || nrc == 0x12 || nrc == 0x31
    }
    data object NoResponse : MfrReadResult()
    data class Failed(val reason: String) : MfrReadResult()
}

class MfrSafetyException(message: String) : Exception(message)

/**
 * Gyártói (PSA) diagnosztikai kommunikáció az ELM327 adapteren keresztül – KIZÁRÓLAG OLVASÁS.
 *
 * Engedélyezett diagnosztikai kérések: 22 xx xx (UDS olvasás), 21 xx (KWP olvasás), 1A xx (KWP azonosítás),
 * 3E (tester present), 81 / 82 és 10 C0 / 10 81 (a PSA KWP-olvasáshoz szükséges kommunikációs session).
 * Minden más kérést (írás, kódolás, rutin, aktuátorteszt, biztonsági hozzáférés, programozás) a [send] elutasít.
 *
 * Minden művelet a [ObdClient.busLock] alatt fut, és a végén visszaállítja a szabványos OBD-címzést.
 */
class MfrSession(private val client: ObdClient, private val log: CommLog? = null, private val clock: () -> Long = System::currentTimeMillis) {

    private var selected: MfrEcu? = null
    /** Címenként az utolsó KWP-forgalom ideje (a PSA KWP-session kb. 5 s tétlenség után lezárul). */
    private val kwpActivity = HashMap<Int, Long>()

    /** Az adapter elfogadta-e az egyedi flow control beállítást (több keretes válaszokhoz szükséges). */
    var flowControlSupported: Boolean = true
        private set

    val protocolSupported: Boolean
        get() = client.vehicleInfo?.protocol.let { it == ObdProtocol.ISO_15765_4_CAN_11_500 || it == ObdProtocol.ISO_15765_4_CAN_11_250 }

    /** Egy blokk végrehajtása kizárólagos buszhozzáféréssel; a végén mindig visszaáll a szabványos címzés. */
    suspend fun <T> exclusive(block: suspend MfrSession.() -> T): T = client.busLock.lockAndRun {
        try {
            block()
        } finally {
            restoreObd()
        }
    }

    private suspend fun at(cmd: String): ElmResult {
        require(AT_WHITELIST.any { it.matches(cmd) }) { "Nem engedélyezett AT-parancs: $cmd" }
        return client.session.at(cmd)
    }

    /**
     * A vezérlő kiválasztása (fejléc, válaszszűrő, flow control). KWP-vezérlőnél kommunikációs sessiont is nyit,
     * ha az utolsó KWP-forgalom óta több mint [KWP_SESSION_REFRESH_MS] telt el (vagy [forceSession]).
     * A session-időzítés címenként és csak a KWP-forgalomra vonatkozik: egy ugyanarra a címre küldött UDS-kérés
     * nem tartja életben a KWP-sessiont.
     */
    suspend fun select(ecu: MfrEcu, forceSession: Boolean = false) {
        if (selected?.requestId != ecu.requestId || selected?.responseId != ecu.responseId) {
            at("ATSH%03X".format(ecu.requestId))
            at("ATCRA%03X".format(ecu.responseId))
            val fc = at("ATFCSH%03X".format(ecu.requestId))
            flowControlSupported = fc !is ElmResult.Error
            if (flowControlSupported) {
                at("ATFCSD300000")
                at("ATFCSM1")
            }
        }
        selected = ecu
        if (ecu.protocol == MfrProtocol.KWP_PSA &&
            (forceSession || clock() - (kwpActivity[ecu.requestId] ?: 0L) > KWP_SESSION_REFRESH_MS)) {
            send(ecu, if (ecu.isPowertrain) "81" else "10C0")
        }
    }

    /**
     * A szabványos OBD-címzés visszaállítása. Megszakíthatatlan: ha a lekérdezést éppen egy gyártói olvasás
     * közben állítják le (pl. a szakértői terminál megnyitásakor), a címzésnek akkor is vissza kell állnia,
     * különben a következő szabványos kérés a gyártói címre menne.
     */
    suspend fun restoreObd() {
        if (selected == null) return
        selected = null
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            for (cmd in listOf("ATFCSM0", "ATCRA", "ATSH7DF")) {
                try { client.session.at(cmd) } catch (_: Exception) { }
            }
        }
    }

    /** Biztonsági ellenőrzés után elküld egy diagnosztikai kérést a kiválasztott vezérlőnek. */
    suspend fun send(ecu: MfrEcu, request: String, timeoutMs: Long = 1_500): ElmResult {
        val cmd = request.replace(" ", "").uppercase()
        if (REQUEST_WHITELIST.none { it.matches(cmd) }) {
            log?.add(CommLog.Direction.ERROR, "Letiltott gyártói kérés: $cmd")
            throw MfrSafetyException("Nem engedélyezett diagnosztikai kérés: $cmd")
        }
        check(selected?.requestId == ecu.requestId && selected?.responseId == ecu.responseId) { "A vezérlő nincs kiválasztva" }
        val res = client.session.obd(cmd, timeoutMs)
        if (ecu.protocol == MfrProtocol.KWP_PSA) kwpActivity[ecu.requestId] = clock()
        return res
    }

    /** Egy azonosító olvasása; csak a vezérlő válaszcíméről érkező pozitív választ fogadja el. */
    suspend fun read(ecu: MfrEcu, request: String): MfrReadResult {
        val cmd = request.replace(" ", "").uppercase()
        val res = try { send(ecu, cmd) } catch (e: MfrSafetyException) { throw e } catch (e: Exception) {
            return MfrReadResult.Failed(e.message ?: "kommunikációs hiba")
        }
        val reqBytes = cmd.chunked(2).map { it.toInt(16) }
        val positive = reqBytes[0] + 0x40
        val ecuHex = "%03X".format(ecu.responseId)
        return when (res) {
            is ElmResult.Data -> {
                val mine = res.messages.filter { it.ecu.equals(ecuHex, true) || it.ecu == "?" }
                val ok = mine.firstOrNull { m ->
                    val d = m.data.map { it.toInt() and 0xFF }
                    d.size >= reqBytes.size && d[0] == positive && (1 until reqBytes.size).all { d[it] == reqBytes[it] }
                }
                when {
                    ok != null -> MfrReadResult.Ok(ok.data.copyOfRange(reqBytes.size, ok.data.size))
                    res.negatives.any { it.ecu.equals(ecuHex, true) || it.ecu == "?" } ->
                        MfrReadResult.Negative(res.negatives.first { it.ecu.equals(ecuHex, true) || it.ecu == "?" }.nrc)
                    else -> MfrReadResult.NoResponse
                }
            }
            is ElmResult.NoData -> MfrReadResult.NoResponse
            is ElmResult.Error -> MfrReadResult.Failed(res.kind.userMessage)
            is ElmResult.Text -> MfrReadResult.Failed("váratlan válasz")
        }
    }

    fun itemRequest(ecu: MfrEcu, item: MfrItem): String =
        if (ecu.protocol == MfrProtocol.UDS) "22%04X".format(item.id) else "21%02X".format(item.id)

    companion object {
        const val KWP_SESSION_REFRESH_MS = 2_000L

        val REQUEST_WHITELIST = listOf(
            Regex("^22[0-9A-F]{4}$"),     // UDS ReadDataByIdentifier
            Regex("^21[0-9A-F]{2}$"),     // KWP ReadDataByLocalIdentifier
            Regex("^1A[0-9A-F]{2}$"),     // KWP ReadEcuIdentification
            Regex("^3E(00|01|80)?$"),     // TesterPresent
            Regex("^8[12]$"),             // KWP Start/StopCommunication
            Regex("^10(C0|81|01)$"),      // PSA KWP olvasó session / alapértelmezett session
        )
        val AT_WHITELIST = listOf(
            Regex("^ATSH[0-9A-F]{3}$"), Regex("^ATCRA([0-9A-F]{3})?$"), Regex("^ATFCSH[0-9A-F]{3}$"),
            Regex("^ATFCSD300000$"), Regex("^ATFCSM[01]$")
        )
    }
}
