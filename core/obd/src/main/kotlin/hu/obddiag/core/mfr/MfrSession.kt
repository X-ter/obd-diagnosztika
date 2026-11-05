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

/** Egy gyártói hibakód, ahogy a vezérlő visszaadta. */
data class MfrDtc(
    /** a nyers 2 bájtos kód (pl. 0x0562 → P0562, 0x5315 → C1315) */
    val raw: Int,
    /** UDS esetén a hibatípus bájt (failure type), KWP-nél nincs */
    val failureType: Int?,
    val status: Int,
    val protocol: MfrProtocol
) {
    val code: String get() = "PCBU"[(raw shr 14) and 3] + "%04X".format(raw and 0x3FFF)

    /** UDS: 0. bit = jelenleg hibás (test failed), 3. bit = megerősített. KWP-PSA-nál a bitek jelentése nem ismert pontosan. */
    val activeNow: Boolean? get() = if (protocol == MfrProtocol.UDS) status and 0x01 != 0 else null
    val confirmed: Boolean? get() = if (protocol == MfrProtocol.UDS) status and 0x08 != 0 else null
}

sealed class MfrDtcResult {
    data class Ok(val dtcs: List<MfrDtc>) : MfrDtcResult()
    data class Negative(val nrc: Int) : MfrDtcResult()
    data object NoResponse : MfrDtcResult()
    data class Failed(val reason: String) : MfrDtcResult()
}

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
    /** Azok a KWP-vezérlők, amelyekkel kommunikációs sessiont nyitottunk (a végén szabályosan lezárjuk). */
    private val openKwp = LinkedHashMap<Int, MfrEcu>()
    /** Lassan válaszoló vezérlők: nekik hosszabb válaszidőt adunk (a szabványos ECU-khoz tanult időzítés kevés lehet). */
    private val slowEcus = HashSet<Int>()
    private var slowTimingActive = false

    /** Az utolsó gyártói olvasások rövid naplója (hibakereséshez, az azonosítási jelentésbe kerül). */
    val readLog: List<String> get() = synchronized(log_) { log_.toList() }
    private val log_ = ArrayDeque<String>()
    private fun note(line: String) = synchronized(log_) { log_.addLast(line); while (log_.size > READ_LOG_SIZE) log_.removeFirst() }

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
        applyTiming(ecu.requestId in slowEcus)
        if (ecu.protocol == MfrProtocol.KWP_PSA &&
            (forceSession || clock() - (kwpActivity[ecu.requestId] ?: 0L) > KWP_SESSION_REFRESH_MS)) {
            openSession(ecu)
        }
    }

    private suspend fun openSession(ecu: MfrEcu) {
        val res = send(ecu, if (ecu.isPowertrain) "81" else "10C0")
        // csak a ténylegesen megnyílt sessiont jegyezzük fel (a válaszoló vezérlőt a végén szabályosan lezárjuk)
        val ecuHex = "%03X".format(ecu.responseId)
        if (res is ElmResult.Data && res.messages.any { (it.ecu.equals(ecuHex, true) || it.ecu == "?") &&
                (it.data.firstOrNull()?.toInt()?.and(0xFF)) in setOf(0xC1, 0x50) }) openKwp[ecu.requestId] = ecu
    }

    private suspend fun applyTiming(slow: Boolean) {
        if (slow == slowTimingActive) return
        if (slow) { at("ATAT0"); at("ATST$SLOW_TIMEOUT_HEX") } else { at("ATST32"); at("ATAT1") }
        slowTimingActive = slow
    }

    /**
     * A megnyitott KWP-sessionök szabályos lezárása (82 = StopCommunication, utastér-oldalon 10 81 = alapállapot).
     * Nyitva hagyott diagnosztikai session mellett egyes vezérlők korlátozott üzemmódban maradhatnak.
     */
    suspend fun closeSessions() {
        if (openKwp.isEmpty()) return
        for (ecu in openKwp.values.toList()) {
            try {
                select(ecu)
                send(ecu, if (ecu.isPowertrain) "82" else "1081", 600)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            kwpActivity.remove(ecu.requestId)
        }
        openKwp.clear()
    }

    /**
     * A szabványos OBD-címzés visszaállítása. Megszakíthatatlan: ha a lekérdezést éppen egy gyártói olvasás
     * közben állítják le (pl. a szakértői terminál megnyitásakor), a címzésnek akkor is vissza kell állnia,
     * különben a következő szabványos kérés a gyártói címre menne.
     */
    suspend fun restoreObd() {
        if (selected == null) return
        selected = null
        val cmds = listOf("ATFCSM0", "ATCRA", "ATSH7DF") + if (slowTimingActive) listOf("ATST32", "ATAT1") else emptyList()
        slowTimingActive = false
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            for (cmd in cmds) {
                try { client.session.at(cmd) } catch (_: Exception) { }
            }
        }
    }

    /** Biztonsági ellenőrzés után elküld egy diagnosztikai kérést a kiválasztott vezérlőnek. */
    suspend fun send(ecu: MfrEcu, request: String, timeoutMs: Long = 1_500): ElmResult = sendChecked(ecu, request, timeoutMs, false)

    private suspend fun sendChecked(ecu: MfrEcu, request: String, timeoutMs: Long, clearAllowed: Boolean): ElmResult {
        val cmd = request.replace(" ", "").uppercase()
        val allowed = REQUEST_WHITELIST.any { it.matches(cmd) } || DTC_READ.any { it.matches(cmd) } ||
            (clearAllowed && DTC_CLEAR.any { it.matches(cmd) })
        if (!allowed) {
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
        var r = readOnce(ecu, cmd)
        // Nincs válasz: lehet, hogy a vezérlő csak lassan válaszol (a szabványos ECU-khoz tanult időzítés rövid) –
        // egyszer hosszabb várakozással újrapróbáljuk, és ha így válaszol, a vezérlőnél ezután is így várunk.
        if (r is MfrReadResult.NoResponse && ecu.requestId !in slowEcus) {
            applyTiming(true)
            r = readOnce(ecu, cmd)
            if (r !is MfrReadResult.NoResponse) slowEcus += ecu.requestId else applyTiming(false)
        }
        // 0x80 / 0x22: a vezérlő szerint nincs megfelelő diagnosztikai session – egyszer újranyitjuk
        if (r is MfrReadResult.Negative && (r.nrc == 0x80 || r.nrc == 0x22) && ecu.protocol == MfrProtocol.KWP_PSA) {
            openSession(ecu)
            r = readOnce(ecu, cmd)
        }
        note("%03X %s: %s".format(ecu.requestId, cmd, when (r) {
            is MfrReadResult.Ok -> "OK ${r.payload.size} bájt"
            is MfrReadResult.Negative -> "NRC 0x%02X".format(r.nrc)
            MfrReadResult.NoResponse -> "nincs válasz"
            is MfrReadResult.Failed -> "hiba: ${r.reason}"
        }))
        return r
    }

    private suspend fun readOnce(ecu: MfrEcu, cmd: String): MfrReadResult {
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

    /** A vezérlő hibakódjainak olvasása (KWP-PSA: 17 FF 00, UDS: 19 02 FF). */
    suspend fun readDtcs(ecu: MfrEcu): MfrDtcResult {
        val req = if (ecu.protocol == MfrProtocol.UDS) "1902FF" else "17FF00"
        val res = try { sendChecked(ecu, req, 3_000, false) } catch (e: MfrSafetyException) { throw e } catch (e: Exception) {
            return MfrDtcResult.Failed(e.message ?: "kommunikációs hiba")
        }
        val out = parseDtcResponse(ecu, res)
        note("%03X %s: %s".format(ecu.requestId, req, when (out) {
            is MfrDtcResult.Ok -> "OK ${out.dtcs.size} hibakód"
            is MfrDtcResult.Negative -> "NRC 0x%02X".format(out.nrc)
            MfrDtcResult.NoResponse -> "nincs válasz"
            is MfrDtcResult.Failed -> "hiba: ${out.reason}"
        }))
        return out
    }

    /**
     * A vezérlő hibakódjainak törlése (KWP-PSA: 14 FF 00, UDS: 14 FF FF FF). Ez az egyetlen nem olvasó művelet,
     * kizárólag a felhasználó kifejezett, megerősített kérésére hívható.
     */
    suspend fun clearDtcs(ecu: MfrEcu): MfrReadResult {
        val req = if (ecu.protocol == MfrProtocol.UDS) "14FFFFFF" else "14FF00"
        log?.add(CommLog.Direction.INFO, "Hibakód-törlés (felhasználói kérés): %03X".format(ecu.requestId))
        val res = try { sendChecked(ecu, req, 5_000, true) } catch (e: MfrSafetyException) { throw e } catch (e: Exception) {
            return MfrReadResult.Failed(e.message ?: "kommunikációs hiba")
        }
        val ecuHex = "%03X".format(ecu.responseId)
        val r = when (res) {
            is ElmResult.Data -> {
                val mine = res.messages.filter { it.ecu.equals(ecuHex, true) || it.ecu == "?" }
                when {
                    mine.any { (it.data.firstOrNull()?.toInt()?.and(0xFF)) == 0x54 } -> MfrReadResult.Ok(ByteArray(0))
                    res.negatives.any { it.ecu.equals(ecuHex, true) || it.ecu == "?" } ->
                        MfrReadResult.Negative(res.negatives.first { it.ecu.equals(ecuHex, true) || it.ecu == "?" }.nrc)
                    else -> MfrReadResult.NoResponse
                }
            }
            is ElmResult.NoData -> MfrReadResult.NoResponse
            is ElmResult.Error -> MfrReadResult.Failed(res.kind.userMessage)
            is ElmResult.Text -> MfrReadResult.Failed("váratlan válasz")
        }
        note("%03X %s: %s".format(ecu.requestId, req, if (r is MfrReadResult.Ok) "OK (törölve)" else r.toString()))
        return r
    }

    private fun parseDtcResponse(ecu: MfrEcu, res: ElmResult): MfrDtcResult {
        val ecuHex = "%03X".format(ecu.responseId)
        return when (res) {
            is ElmResult.Data -> {
                val mine = res.messages.filter { it.ecu.equals(ecuHex, true) || it.ecu == "?" }
                val uds = ecu.protocol == MfrProtocol.UDS
                val msg = mine.firstOrNull { (it.data.firstOrNull()?.toInt()?.and(0xFF)) == if (uds) 0x59 else 0x57 }
                if (msg == null) {
                    val neg = res.negatives.firstOrNull { it.ecu.equals(ecuHex, true) || it.ecu == "?" }
                    return if (neg != null) MfrDtcResult.Negative(neg.nrc) else MfrDtcResult.NoResponse
                }
                val d = msg.data.map { it.toInt() and 0xFF }
                MfrDtcResult.Ok(parseDtcs(d, uds))
            }
            is ElmResult.NoData -> MfrDtcResult.NoResponse
            is ElmResult.Error -> MfrDtcResult.Failed(res.kind.userMessage)
            is ElmResult.Text -> MfrDtcResult.Failed("váratlan válasz")
        }
    }

    fun itemRequest(ecu: MfrEcu, item: MfrItem): String =
        if (ecu.protocol == MfrProtocol.UDS) "22%04X".format(item.id) else "21%02X".format(item.id)

    companion object {
        const val KWP_SESSION_REFRESH_MS = 2_000L
        const val READ_LOG_SIZE = 400
        /** lassú vezérlőknél a válaszra várás: 0xC8 × 4 ms = 800 ms */
        const val SLOW_TIMEOUT_HEX = "C8"

        /**
         * Hibakód-válasz feldolgozása. UDS: 59 02 <maszk> [kód_H kód_L típus állapot]…;
         * KWP-PSA: 57 <darab> [kód_H kód_L állapot]…  A 0000 kód (kitöltés) kimarad.
         */
        fun parseDtcs(d: List<Int>, uds: Boolean): List<MfrDtc> {
            val out = mutableListOf<MfrDtc>()
            if (uds) {
                var i = 3
                while (i + 4 <= d.size) {
                    val raw = (d[i] shl 8) or d[i + 1]
                    if (raw != 0) out += MfrDtc(raw, d[i + 2], d[i + 3], MfrProtocol.UDS)
                    i += 4
                }
            } else {
                val count = d.getOrNull(1) ?: 0
                var i = 2
                while (i + 3 <= d.size && out.size < count) {
                    val raw = (d[i] shl 8) or d[i + 1]
                    if (raw != 0) out += MfrDtc(raw, null, d[i + 2], MfrProtocol.KWP_PSA)
                    i += 3
                }
            }
            return out.distinctBy { Triple(it.raw, it.failureType, it.status) }
        }

        /** Hibakód olvasás (csak olvasás). */
        val DTC_READ = listOf(Regex("^17FF00$"), Regex("^1902(FF|09|08)$"))
        /** Hibakód törlés – csak a [clearDtcs] küldheti, a felhasználó megerősítése után. */
        val DTC_CLEAR = listOf(Regex("^14FF00$"), Regex("^14FFFFFF$"))

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
            Regex("^ATFCSD300000$"), Regex("^ATFCSM[01]$"), Regex("^ATAT[01]$"), Regex("^ATST[0-9A-F]{2}$")
        )
    }
}
