package hu.obddiag.core.diag

/** Egy OBD készenléti (readiness) monitor állapota. */
data class MonitorStatus(val key: String, val available: Boolean, val complete: Boolean)

/** A 0101 (vagy 0141) PID dekódolt tartalma. */
data class ReadinessInfo(
    val milOn: Boolean,
    val dtcCount: Int,
    val compressionIgnition: Boolean,
    val monitors: List<MonitorStatus>
) {
    val supported: List<MonitorStatus> get() = monitors.filter { it.available }
    val allComplete: Boolean get() = supported.all { it.complete }
}

object ReadinessDecoder {
    private val SPARK = listOf("catalyst", "heated_catalyst", "evap", "secondary_air", "ac_refrigerant", "o2_sensor", "o2_heater", "egr")
    private val COMPRESSION = listOf("nmhc_catalyst", "nox_scr", "reserved_c2", "boost_pressure", "reserved_c4", "exhaust_sensor", "pm_filter", "egr_vvt")

    /** @param d a 4 adatbájt (A, B, C, D) */
    fun decode(d: IntArray): ReadinessInfo {
        require(d.size >= 4)
        val a = d[0]; val b = d[1]; val c = d[2]; val dd = d[3]
        val diesel = (b shr 3) and 1 == 1
        val monitors = mutableListOf<MonitorStatus>()
        // Folyamatos monitorok: B0–B2 = elérhető, B4–B6 = befejezetlen
        listOf("misfire", "fuel_system", "components").forEachIndexed { i, key ->
            monitors += MonitorStatus(key, (b shr i) and 1 == 1, (b shr (i + 4)) and 1 == 0)
        }
        val names = if (diesel) COMPRESSION else SPARK
        names.forEachIndexed { i, key ->
            if (key.startsWith("reserved")) return@forEachIndexed
            monitors += MonitorStatus(key, (c shr i) and 1 == 1, (dd shr i) and 1 == 0)
        }
        return ReadinessInfo((a shr 7) and 1 == 1, a and 0x7F, diesel, monitors)
    }
}
