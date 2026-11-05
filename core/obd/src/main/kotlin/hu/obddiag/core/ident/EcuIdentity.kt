package hu.obddiag.core.ident

import hu.obddiag.core.ObdClient
import hu.obddiag.core.elm.ElmResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Egy szabványos (OBD-címen válaszoló) vezérlő azonosító adatai, kizárólag dokumentált, járműfüggetlen
 * azonosítókból: SAE J1979 Mode 09 (CALID, CVN, ECU-név), ISO 14229-1 C. melléklet (UDS 22 F1xx) és
 * ISO 14230-3 (KWP 1A 8x/9x). Semmilyen gyártói feltételezés nincs benne.
 */
data class EcuIdentity(
    /** válaszcím, ahogy az adapter jelzi (pl. 7E8) */
    val address: String,
    val requestId: Int?,
    val ecuName: String? = null,
    val calibrationIds: List<String> = emptyList(),
    val cvns: List<String> = emptyList(),
    /** kiolvasott azonosítók: név → érték (szöveg, vagy nyomtatható karakter híján hexa) */
    val identifiers: Map<String, String> = emptyMap(),
    /** milyen szolgáltatással sikerült olvasni: "UDS", "KWP" vagy null (csak Mode 09) */
    val diagProtocol: String? = null
) {
    /** Minden kiolvasott, cikkszám-jellegű azonosító (adatforrások ezekkel is illeszthetnek). */
    val partNumbers: Set<String> get() = identifiers.filterKeys { it in PART_KEYS }.values.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    companion object {
        val PART_KEYS = setOf("sparePartNumber", "ecuHardwareNumber", "supplierHardwareNumber", "supplierSoftwareNumber")
    }
}

/**
 * A szabványos azonosítók olvasása. Fizikai címzés csak 11 bites CAN-en (7E0–7E7 → 7E8–7EF), és csak a
 * [ALLOWED] listában lévő, olvasó kérésekkel. Minden művelet a busz zárolása alatt fut, a végén a szabványos
 * OBD-címzés (7DF) visszaáll.
 */
class StandardEcuIdentifier(private val client: ObdClient, private val log: RecognitionLog) {

    /** ISO 14229-1 C.1 szabványos DID-ek (csak olvasás). */
    private val udsDids = linkedMapOf(
        0xF187 to "sparePartNumber", 0xF18A to "supplierId", 0xF18C to "serialNumber", 0xF190 to "vin",
        0xF191 to "ecuHardwareNumber", 0xF192 to "supplierHardwareNumber", 0xF193 to "supplierHardwareVersion",
        0xF194 to "supplierSoftwareNumber", 0xF195 to "supplierSoftwareVersion", 0xF197 to "systemName"
    )

    /** ISO 14230-3 ReadEcuIdentification azonosítók (csak olvasás). */
    private val kwpIds = linkedMapOf(
        0x87 to "sparePartNumber", 0x8A to "supplierId", 0x8C to "serialNumber", 0x90 to "vin",
        0x91 to "ecuHardwareNumber", 0x92 to "supplierHardwareNumber", 0x94 to "supplierSoftwareNumber", 0x97 to "systemName"
    )

    suspend fun identify(): List<EcuIdentity> {
        val info = client.vehicleInfo ?: return emptyList()
        val ecus = info.ecus.filter { it.isNotBlank() && it != "?" }
        // Mode 09: funkcionális (7DF) kérés, minden ECU válaszol – a meglévő szabványos olvasók
        val names = runCatching { client.readEcuNames() }.getOrDefault(emptyMap())
        val calIds = runCatching { client.readCalibrationIds() }.getOrDefault(emptyList())
        val cvns = runCatching { client.readCvns() }.getOrDefault(emptyList())
        log.add(Step.ECU, "Mode 09 (SAE J1979): ECU-név ${names.size}, CALID ${calIds.size}, CVN ${cvns.size} válasz")
        val out = mutableListOf<EcuIdentity>()
        for (addr in ecus) {
            var id = EcuIdentity(addr, null, names[addr], calIds.filter { it.first == addr }.map { it.second }, cvns.filter { it.first == addr }.map { it.second })
            val rs = addr.toIntOrNull(16)
            val physical = info.protocol.canHeaderBytes == 11 && rs != null && rs in 0x7E8..0x7EF
            if (physical) {
                val rq = rs!! - 8
                id = id.copy(requestId = rq)
                val (proto, ids) = readPhysical(rq, rs)
                id = id.copy(identifiers = ids, diagProtocol = proto)
            } else {
                log.add(Step.ECU, "$addr: fizikai azonosító olvasás kihagyva (${if (info.protocol.isCan) "29 bites címzés" else "nem CAN protokoll"}); csak Mode 09")
            }
            log.add(Step.ECU, "$addr: " + describe(id))
            out += id
        }
        return out
    }

    private fun describe(id: EcuIdentity): String = buildList {
        id.ecuName?.let { add("név=\"$it\"") }
        if (id.calibrationIds.isNotEmpty()) add("CALID=" + id.calibrationIds.joinToString("/"))
        if (id.cvns.isNotEmpty()) add("CVN=" + id.cvns.joinToString("/"))
        id.identifiers.forEach { (k, v) -> add("$k=\"$v\"") }
    }.joinToString(", ").ifEmpty { "nem adott vissza azonosítót" }

    private suspend fun readPhysical(rq: Int, rs: Int): Pair<String?, Map<String, String>> = client.busLock.lockAndRun {
        val ids = LinkedHashMap<String, String>()
        var proto: String? = null
        try {
            at("ATSH%03X".format(rq)); at("ATCRA%03X".format(rs))
            if (at("ATFCSH%03X".format(rq)) !is ElmResult.Error) { at("ATFCSD300000"); at("ATFCSM1") }
            var udsRejected = false
            for ((did, name) in udsDids) {
                val r = request("22%04X".format(did), rs, 0x62, listOf(did shr 8, did and 0xFF))
                when (r) {
                    is Answer.Ok -> { ids[name] = render(r.data); proto = "UDS" }
                    is Answer.Nrc -> if (r.nrc == 0x11) { udsRejected = true; log.add(Step.ECU, "%03X: 22 → NRC 0x11 (az UDS olvasás nem támogatott)".format(rq)) }
                    Answer.None -> Unit
                }
                if (udsRejected) break
                // ha a vezérlő az első két azonosítóra sem válaszol, nem terheljük tovább
                if (did == 0xF18A && ids.isEmpty() && !udsRejected) { log.add(Step.ECU, "%03X: nem válaszol UDS azonosító kérésekre".format(rq)); break }
            }
            if (ids.isEmpty()) {
                for ((lid, name) in kwpIds) {
                    val r = request("1A%02X".format(lid), rs, 0x5A, listOf(lid))
                    if (r is Answer.Ok) { ids[name] = render(r.data); proto = "KWP" }
                    if (r is Answer.Nrc && r.nrc == 0x11) break
                    if (lid == 0x8A && ids.isEmpty()) break
                }
            }
        } finally {
            withContext(NonCancellable) {
                for (c in listOf("ATFCSM0", "ATCRA", "ATSH7DF")) runCatching { client.session.at(c) }
            }
        }
        proto to ids
    }

    private sealed class Answer {
        class Ok(val data: List<Int>) : Answer()
        class Nrc(val nrc: Int) : Answer()
        data object None : Answer()
    }

    private suspend fun at(cmd: String): ElmResult {
        require(AT_ALLOWED.any { it.matches(cmd) }) { "Nem engedélyezett AT-parancs: $cmd" }
        return client.session.at(cmd)
    }

    private suspend fun request(cmd: String, rs: Int, positive: Int, echo: List<Int>): Answer {
        check(ALLOWED.any { it.matches(cmd) }) { "Nem engedélyezett azonosító kérés: $cmd" }
        val res = runCatching { client.session.obd(cmd, 1_500) }.getOrNull() ?: return Answer.None
        val hex = "%03X".format(rs)
        if (res !is ElmResult.Data) return Answer.None
        val m = res.messages.firstOrNull { (it.ecu.equals(hex, true) || it.ecu == "?") && it.data.size > echo.size &&
                (it.data[0].toInt() and 0xFF) == positive && echo.indices.all { i -> (it.data[i + 1].toInt() and 0xFF) == echo[i] } }
        if (m != null) return Answer.Ok(m.data.drop(1 + echo.size).map { it.toInt() and 0xFF })
        val n = res.negatives.firstOrNull { it.ecu.equals(hex, true) || it.ecu == "?" }
        return if (n != null) Answer.Nrc(n.nrc) else Answer.None
    }

    companion object {
        val ALLOWED = listOf(Regex("^22F1[0-9A-F]{2}$"), Regex("^1A[89][0-9A-F]$"))
        val AT_ALLOWED = listOf(Regex("^ATSH7E[0-7]$"), Regex("^ATCRA7E[89A-F]$"), Regex("^ATFCSH7E[0-7]$"),
            Regex("^ATFCSD300000$"), Regex("^ATFCSM1$"))

        /** Nyomtatható ASCII → szöveg; különben hexa. */
        fun render(data: List<Int>): String {
            val trimmed = data.dropLastWhile { it == 0x00 || it == 0x20 || it == 0xFF }
            return if (trimmed.isNotEmpty() && trimmed.all { it in 0x20..0x7E }) trimmed.map { it.toChar() }.joinToString("").trim()
            else data.joinToString(" ") { "%02X".format(it) }
        }
    }
}
