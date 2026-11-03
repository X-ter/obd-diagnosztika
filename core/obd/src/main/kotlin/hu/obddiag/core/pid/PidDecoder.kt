package hu.obddiag.core.pid

import hu.obddiag.core.elm.EcuMessage

/** Egy PID dekódolt eredménye: a numerikus jelek és az állapotértékek. */
data class DecodedPid(
    val pid: Int,
    val ecu: String,
    val values: Map<String, Double>,
    val states: Map<String, String>
)

object PidDecoder {

    /**
     * Mode 01 (vagy Mode 02) válaszüzenetek dekódolása. Egy üzenet több PID-et is tartalmazhat
     * (CAN multi-PID kérés esetén). A PID-ek hosszát a definíciók adják; ismeretlen PID-nél a
     * feldolgozás leáll, mert a további bájtok határa nem állapítható meg.
     *
     * @param responseService 0x41 élő adatoknál, 0x42 freeze frame-nél
     * @param freezeFrame Mode 02 esetén minden PID után egy keretszám-bájt következik
     */
    fun decode(messages: List<EcuMessage>, responseService: Int = 0x41, freezeFrame: Boolean = false): List<DecodedPid> {
        val out = mutableListOf<DecodedPid>()
        for (m in messages) {
            val d = m.data.map { it.toInt() and 0xFF }
            if (d.isEmpty() || d[0] != responseService) continue
            var i = 1
            while (i < d.size) {
                val pidNo = d[i]
                val def = StandardPids.pid(pidNo)
                val start = i + 1 + if (freezeFrame) 1 else 0
                val len = def?.bytes ?: supportMaskLength(pidNo) ?: break
                if (start + len > d.size) break
                val bytes = d.subList(start, start + len).toIntArray()
                if (def != null) out += decodeOne(def, m.ecu, bytes)
                i = start + len
            }
        }
        return out
    }

    private fun supportMaskLength(pid: Int): Int? = if (pid in StandardPids.SUPPORT_PIDS) 4 else null

    fun decodeOne(def: PidDef, ecu: String, bytes: IntArray): DecodedPid {
        val values = LinkedHashMap<String, Double>()
        for (s in def.signals) {
            val v = try { s.decode(bytes) } catch (_: IndexOutOfBoundsException) { null }
            if (v != null && v.isFinite()) values[s.id] = v
        }
        val states = LinkedHashMap<String, String>()
        for (s in def.states) {
            val v = try { s.decode(bytes) } catch (_: IndexOutOfBoundsException) { null }
            if (v != null) states[s.id] = v
        }
        return DecodedPid(def.pid, ecu, values, states)
    }
}

/** A támogatott PID-ek bitmaszkjainak (0100, 0120, …) feldolgozása. */
object SupportedPidParser {
    /**
     * @param base a lekérdezett tartomány kezdete (0x00, 0x20, …)
     * @param mask a 4 adatbájt
     * @return a támogatott PID-ek (base+1 … base+0x20)
     */
    fun parse(base: Int, mask: IntArray): Set<Int> {
        require(mask.size >= 4)
        val result = mutableSetOf<Int>()
        for (byteIdx in 0 until 4) {
            for (bit in 0 until 8) {
                if ((mask[byteIdx] shr (7 - bit)) and 1 == 1) result += base + byteIdx * 8 + bit + 1
            }
        }
        return result
    }

    /** Igaz, ha a következő tartomány lekérdezése is támogatott (base+0x20 benne van a maszkban). */
    fun hasNextRange(base: Int, supported: Set<Int>) = supported.contains(base + 0x20)
}
