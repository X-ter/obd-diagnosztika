package hu.obddiag.core

import hu.obddiag.core.diag.Mode06Decoder
import hu.obddiag.core.diag.Mode06Result
import hu.obddiag.core.diag.Mode09Decoder
import hu.obddiag.core.diag.ReadinessDecoder
import hu.obddiag.core.diag.ReadinessInfo
import hu.obddiag.core.dtc.Dtc
import hu.obddiag.core.dtc.DtcParser
import hu.obddiag.core.dtc.DtcStatus
import hu.obddiag.core.elm.ElmErrorKind
import hu.obddiag.core.elm.ElmResult
import hu.obddiag.core.elm.ElmSession
import hu.obddiag.core.elm.ObdProtocol
import hu.obddiag.core.pid.DecodedPid
import hu.obddiag.core.pid.PidDecoder
import hu.obddiag.core.pid.StandardPids
import hu.obddiag.core.pid.SupportedPidParser
import kotlinx.coroutines.delay

/** Az adapter azonosítása az inicializálás után. */
data class AdapterInfo(
    val elmVersion: String,
    val deviceDescription: String?,
    /** STN11xx/STN21xx esetén az STI válasza, egyébként null. */
    val stnVersion: String?,
    val voltage: Double?
) {
    val isStn: Boolean get() = stnVersion != null
    /** A válaszszám-utótag ("010C1") az ELM327 v1.3-tól támogatott. */
    val supportsResponseCountHint: Boolean
        get() = isStn || (Regex("v(\\d+)\\.(\\d+)").find(elmVersion)?.destructured?.let { (a, b) ->
            a.toInt() > 1 || (a.toInt() == 1 && b.toInt() >= 3)
        } ?: false)
}

data class VehicleConnectionInfo(val protocol: ObdProtocol, val ecus: List<String>)

class ObdException(val kind: ElmErrorKind?, message: String) : Exception(message)

/** Freeze frame: a hibát kiváltó kód és a pillanatkép értékei. */
data class FreezeFrame(val triggerDtc: String?, val values: List<DecodedPid>)

/**
 * Magas szintű OBD-II kliens az [ElmSession] fölött: inicializálás, PID-felderítés és a szabványos módok.
 * Csak olvasó és a szabványos Mode 04 (hibatörlés) műveletet végzi; vezérlő vagy író parancsot nem küld.
 */
class ObdClient(val session: ElmSession) {

    var adapterInfo: AdapterInfo? = null
        private set
    var vehicleInfo: VehicleConnectionInfo? = null
        private set

    val isCan: Boolean get() = vehicleInfo?.protocol?.isCan ?: session.protocol?.isCan ?: false

    /** Mode 01 támogatott PID-ek (felderítés után). */
    var supportedPids: Set<Int> = emptySet()
        private set

    /** Igaz, ha a jármű elfogadja a több PID-et tartalmazó Mode 01 kérést (csak CAN-en). */
    var multiPidSupported: Boolean = false
        private set

    var useResponseCountHint: Boolean = false
        private set

    /** Ha a jármű a csoportos kérésekre ismételten hiányosan válaszol, visszaállunk az egyenkénti lekérdezésre. */
    fun disableMultiPid() {
        multiPidSupported = false
    }

    /** Az adapter alaphelyzetbe állítása és beállítása. */
    suspend fun initializeAdapter(): AdapterInfo {
        var reset = session.at("ATZ", ElmSession.RESET_TIMEOUT_MS)
        if (reset is ElmResult.Error) {
            // Néhány klón lassan indul; második próbálkozás meleg újraindítással.
            delay(300)
            reset = session.at("ATWS", ElmSession.RESET_TIMEOUT_MS)
            if (reset is ElmResult.Error) throw ObdException(reset.kind, "Az adapter nem válaszol az újraindításra: ${reset.kind.userMessage}")
        }
        val resetText = (reset as? ElmResult.Text)?.text.orEmpty()
        for (cmd in listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATAT1")) {
            val r = session.at(cmd)
            if (r is ElmResult.Error && cmd in setOf("ATE0", "ATH1")) {
                throw ObdException(r.kind, "Az adapter nem fogadta el a(z) $cmd parancsot")
            }
        }
        session.headersEnabled = true
        val version = (session.at("ATI") as? ElmResult.Text)?.text?.lineSequence()?.lastOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: resetText.lineSequence().lastOrNull { it.contains("ELM", true) }?.trim() ?: "ismeretlen"
        val desc = (session.at("AT@1") as? ElmResult.Text)?.text?.trim()?.takeIf { it.isNotEmpty() && it != "?" }
        val stn = (session.at("STI") as? ElmResult.Text)?.text?.trim()?.takeIf { it.startsWith("STN", ignoreCase = true) }
        val voltage = readAdapterVoltage()
        return AdapterInfo(version, desc, stn, voltage).also {
            adapterInfo = it
            useResponseCountHint = false
        }
    }

    /**
     * A munkamenet beállításainak visszaállítása (pl. a szakértői terminál után, ahol a felhasználó
     * megváltoztathatta a fejléc-, visszhang- vagy szűrőbeállításokat).
     */
    suspend fun restoreSessionSettings() {
        for (cmd in listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATAT1", "ATCAF1", "ATCRA")) session.at(cmd)
        when (session.protocol?.canHeaderBytes) {
            11 -> session.at("ATSH7DF")
            29 -> session.at("ATSH18DB33F1")
            else -> Unit
        }
        session.headersEnabled = true
    }

    /** Az adapter által mért feszültség (ATRV) – a legtöbb járműben a diagnosztikai csatlakozó 16-os tüskéje, azaz akkumulátorfeszültség. */
    suspend fun readAdapterVoltage(): Double? {
        val r = session.at("ATRV") as? ElmResult.Text ?: return null
        return Regex("(\\d+(?:[.,]\\d+)?)\\s*V?").find(r.text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            ?.takeIf { it in 0.0..40.0 }
    }

    /**
     * Kapcsolódás a járműhöz: protokoll beállítása, keresés (0100), felismert protokoll lekérdezése.
     * @param preferred [ObdProtocol.AUTO] esetén automatikus keresés
     */
    suspend fun connectVehicle(preferred: ObdProtocol = ObdProtocol.AUTO): VehicleConnectionInfo {
        session.at("ATSP${preferred.code}")
        session.protocol = if (preferred == ObdProtocol.AUTO) null else preferred
        val probe = session.obd("0100", ElmSession.SEARCH_TIMEOUT_MS)
        when (probe) {
            is ElmResult.Error -> throw ObdException(probe.kind, probe.kind.userMessage)
            is ElmResult.NoData -> throw ObdException(ElmErrorKind.UNABLE_TO_CONNECT, "A jármű nem válaszolt (NO DATA). Kapcsold be a gyújtást.")
            else -> Unit
        }
        val dpn = (session.at("ATDPN") as? ElmResult.Text)?.text
        val protocol = dpn?.let { ObdProtocol.fromDpn(it) } ?: preferred.takeIf { it != ObdProtocol.AUTO }
            ?: throw ObdException(null, "A protokoll nem azonosítható (ATDPN: $dpn)")
        session.protocol = protocol
        // A keresés eredményét a felismert protokoll szerint újraértelmezzük.
        val reparsed = session.obd("0100")
        val ecus = (reparsed as? ElmResult.Data)?.messages?.map { it.ecu }?.distinct()
            ?: (probe as? ElmResult.Data)?.messages?.map { it.ecu }?.distinct().orEmpty()
        return VehicleConnectionInfo(protocol, ecus).also { vehicleInfo = it }
    }

    /** Támogatott PID-ek felderítése egy adott módban (01, 02 freeze frame, 06, 09). */
    suspend fun discoverSupported(mode: Int): Set<Int> {
        val result = mutableSetOf<Int>()
        for (base in StandardPids.SUPPORT_PIDS) {
            val cmd = if (mode == 0x02) "%02X%02X00".format(mode, base) else "%02X%02X".format(mode, base)
            val r = session.obd(cmd) as? ElmResult.Data ?: break
            val respService = mode + 0x40
            var any = false
            for (m in r.messages) {
                val d = m.data.map { it.toInt() and 0xFF }
                val offset = if (mode == 0x02) 3 else 2
                if (d.size >= offset + 4 && d[0] == respService && d[1] == base) {
                    result += SupportedPidParser.parse(base, d.subList(offset, offset + 4).toIntArray())
                    any = true
                }
            }
            if (!any || !SupportedPidParser.hasNextRange(base, result)) break
        }
        if (mode == 0x01) supportedPids = result
        return result
    }

    /** Megvizsgálja, hogy a jármű kezeli-e a több PID-et tartalmazó kérést, és hogy működik-e a válaszszám-utótag. */
    suspend fun probeOptimizations() {
        multiPidSupported = false
        if (isCan) {
            val candidates = supportedPids.filter { it in setOf(0x0C, 0x0D, 0x05, 0x04, 0x11, 0x0F) }.take(3)
            if (candidates.size >= 2) {
                val cmd = "01" + candidates.joinToString("") { "%02X".format(it) }
                val decoded = (session.obd(cmd) as? ElmResult.Data)?.let { PidDecoder.decode(it.messages) }.orEmpty()
                multiPidSupported = decoded.map { it.pid }.toSet().containsAll(candidates)
            }
        }
        useResponseCountHint = false
        if (isCan && adapterInfo?.supportsResponseCountHint == true && vehicleInfo?.ecus?.size == 1) {
            val r = session.obd("01001")
            useResponseCountHint = r is ElmResult.Data && r.firstFor(0x41) != null
        }
    }

    /** Mode 01 lekérdezés egy vagy (CAN-en) legfeljebb 6 PID-re. */
    suspend fun readPids(pids: List<Int>, timeoutMs: Long = ElmSession.DEFAULT_TIMEOUT_MS): ElmResult {
        require(pids.isNotEmpty() && pids.size <= 6)
        // A válaszszám-utótagot ("010C1") csak egy PID-es kérésnél használjuk: az ELM327 a CAN-kereteket
        // számolja, így egy több keretes (több PID-es) válasz az első keret után megszakadna.
        val hint = useResponseCountHint && pids.size == 1
        val cmd = "01" + pids.joinToString("") { "%02X".format(it) } + if (hint) "1" else ""
        return session.obd(cmd, timeoutMs)
    }

    suspend fun readReadiness(): ReadinessInfo? {
        val r = session.obd("0101") as? ElmResult.Data ?: return null
        val m = r.firstFor(0x41) ?: return null
        val d = m.data.map { it.toInt() and 0xFF }
        if (d.size < 6 || d[1] != 0x01) return null
        return ReadinessDecoder.decode(d.subList(2, 6).toIntArray())
    }

    suspend fun readDtcs(status: DtcStatus): DtcReadResult {
        val cmd = when (status) {
            DtcStatus.STORED -> "03"
            DtcStatus.PENDING -> "07"
            DtcStatus.PERMANENT -> "0A"
        }
        return when (val r = session.obd(cmd, 5_000)) {
            is ElmResult.Data -> {
                if (r.messages.isEmpty() && r.negatives.isNotEmpty()) DtcReadResult.NotSupported(r.negatives.first().description)
                else DtcReadResult.Success(DtcParser.parse(r.messages, status, isCan))
            }
            // A NO DATA a legtöbb járműnél azt jelenti, hogy nincs hibakód.
            is ElmResult.NoData -> DtcReadResult.Success(emptyList())
            is ElmResult.Error -> if (r.kind == ElmErrorKind.UNKNOWN_COMMAND) DtcReadResult.NotSupported(r.kind.userMessage) else DtcReadResult.Failure(r.kind)
            is ElmResult.Text -> DtcReadResult.Failure(ElmErrorKind.MALFORMED)
        }
    }

    /** Hibakódok és a hozzájuk tartozó diagnosztikai adatok törlése (Mode 04). */
    suspend fun clearDtcs(): ClearResult = when (val r = session.obd("04", 8_000)) {
        is ElmResult.Data -> when {
            r.firstFor(0x44) != null -> ClearResult.Success
            r.negatives.isNotEmpty() -> ClearResult.Rejected(r.negatives.first().description)
            else -> ClearResult.Failure("Váratlan válasz: ${r.raw.trim()}")
        }
        is ElmResult.NoData -> ClearResult.Failure("A jármű nem igazolta vissza a törlést (NO DATA).")
        is ElmResult.Error -> ClearResult.Failure(r.kind.userMessage)
        is ElmResult.Text -> ClearResult.Failure("Váratlan válasz: ${r.text}")
    }

    /** Freeze frame (Mode 02, 0. keret) a támogatott PID-ekre. */
    suspend fun readFreezeFrame(): FreezeFrame? {
        val supported = discoverSupported(0x02)
        if (supported.isEmpty()) return null
        val trigger = (session.obd("020200") as? ElmResult.Data)?.firstFor(0x42)?.data
            ?.map { it.toInt() and 0xFF }?.takeIf { it.size >= 5 }?.let { DtcParser.decode(it[3], it[4]) }
        val values = mutableListOf<DecodedPid>()
        for (pid in supported.sorted()) {
            if (pid in StandardPids.SUPPORT_PIDS || pid == 0x02) continue
            if (StandardPids.pid(pid) == null) continue
            val r = session.obd("02%02X00".format(pid)) as? ElmResult.Data ?: continue
            values += PidDecoder.decode(r.messages, responseService = 0x42, freezeFrame = true)
        }
        if (trigger == null && values.isEmpty()) return null
        return FreezeFrame(trigger, values)
    }

    suspend fun readVin(): String? {
        val r = session.obd("0902", 5_000) as? ElmResult.Data ?: return null
        return Mode09Decoder.vin(r.messages, isCan)
    }

    suspend fun readCalibrationIds(): List<Pair<String, String>> =
        (session.obd("0904", 5_000) as? ElmResult.Data)?.let { Mode09Decoder.calibrationIds(it.messages, isCan) }.orEmpty()

    suspend fun readCvns(): List<Pair<String, String>> =
        (session.obd("0906", 5_000) as? ElmResult.Data)?.let { Mode09Decoder.cvns(it.messages, isCan) }.orEmpty()

    suspend fun readEcuNames(): Map<String, String> =
        (session.obd("090A", 5_000) as? ElmResult.Data)?.let { Mode09Decoder.ecuNames(it.messages, isCan) }.orEmpty()

    /** Mode 06 teszteredmények (csak CAN formátum). */
    suspend fun readMode06(): List<Mode06Result> {
        if (!isCan) return emptyList()
        val mids = discoverSupported(0x06).filter { it !in StandardPids.SUPPORT_PIDS }
        val out = mutableListOf<Mode06Result>()
        for (mid in mids) {
            val r = session.obd("06%02X".format(mid)) as? ElmResult.Data ?: continue
            out += Mode06Decoder.decode(r.messages)
        }
        return out
    }
}

sealed class DtcReadResult {
    data class Success(val codes: List<Dtc>) : DtcReadResult()
    data class NotSupported(val reason: String) : DtcReadResult()
    data class Failure(val kind: ElmErrorKind) : DtcReadResult()
}

sealed class ClearResult {
    data object Success : ClearResult()
    data class Rejected(val reason: String) : ClearResult()
    data class Failure(val reason: String) : ClearResult()
}
