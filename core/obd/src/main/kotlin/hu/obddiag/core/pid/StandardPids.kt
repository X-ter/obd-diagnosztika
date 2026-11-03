package hu.obddiag.core.pid

import hu.obddiag.core.pid.ObdUnit.*
import hu.obddiag.core.pid.PidCategory.*

/**
 * A szabványos Mode 01 PID-ek (SAE J1979) képletei.
 * A képletekben A, B, C… a válasz adatbájtjai (d[0], d[1], d[2]…).
 */
object StandardPids {

    private fun ab(d: IntArray, i: Int = 0) = d[i] * 256 + d[i + 1]
    private fun signed16(d: IntArray, i: Int = 0): Int { val v = ab(d, i); return if (v >= 0x8000) v - 0x10000 else v }
    private fun pct(a: Int) = a * 100.0 / 255.0
    private fun trim(a: Int) = (a - 128) * 100.0 / 128.0
    private fun bit(v: Int, b: Int) = (v shr b) and 1 == 1

    private fun sig(pid: Int, suffix: String? = null, key: String, unit: ObdUnit, cat: PidCategory,
                    min: Double, max: Double, dec: Int = 0, f: (IntArray) -> Double?): SignalDef {
        val id = "01.%02X".format(pid) + (suffix?.let { ".$it" } ?: "")
        return SignalDef(id, key, unit, cat, min, max, dec, f)
    }

    private val list = mutableListOf<PidDef>()
    private fun def(pid: Int, bytes: Int, vararg signals: SignalDef) { list += PidDef(1, pid, bytes, signals.toList()) }
    private fun statePid(pid: Int, bytes: Int, vararg states: StateDef) { list += PidDef(1, pid, bytes, states = states.toList()) }

    init {
        // 0x01: monitorállapot – külön dekóder (ReadinessDecoder); a MIL és a hibakódszám jelként is elérhető
        def(0x01, 4,
            sig(0x01, "MIL", "mil", NONE, STATUS, 0.0, 1.0) { d -> if (bit(d[0], 7)) 1.0 else 0.0 },
            sig(0x01, "DTC", "dtc_count", COUNT, STATUS, 0.0, 127.0) { d -> (d[0] and 0x7F).toDouble() })
        statePid(0x03, 2,
            StateDef("01.03.1", "fuel_system_1", FUEL) { d -> fuelSystemState(d[0]) },
            StateDef("01.03.2", "fuel_system_2", FUEL) { d -> fuelSystemState(d[1]) })
        def(0x04, 1, sig(0x04, key = "engine_load", unit = PERCENT, cat = ENGINE, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x05, 1, sig(0x05, key = "coolant_temp", unit = CELSIUS, cat = TEMPERATURE, min = -40.0, max = 215.0) { it[0] - 40.0 })
        def(0x06, 1, sig(0x06, key = "stft_b1", unit = PERCENT, cat = FUEL, min = -100.0, max = 99.2, dec = 1) { trim(it[0]) })
        def(0x07, 1, sig(0x07, key = "ltft_b1", unit = PERCENT, cat = FUEL, min = -100.0, max = 99.2, dec = 1) { trim(it[0]) })
        def(0x08, 1, sig(0x08, key = "stft_b2", unit = PERCENT, cat = FUEL, min = -100.0, max = 99.2, dec = 1) { trim(it[0]) })
        def(0x09, 1, sig(0x09, key = "ltft_b2", unit = PERCENT, cat = FUEL, min = -100.0, max = 99.2, dec = 1) { trim(it[0]) })
        def(0x0A, 1, sig(0x0A, key = "fuel_pressure", unit = KPA, cat = FUEL, min = 0.0, max = 765.0) { it[0] * 3.0 })
        def(0x0B, 1, sig(0x0B, key = "map", unit = KPA, cat = AIR, min = 0.0, max = 255.0) { it[0].toDouble() })
        def(0x0C, 2, sig(0x0C, key = "rpm", unit = RPM, cat = ENGINE, min = 0.0, max = 8000.0) { ab(it) / 4.0 })
        def(0x0D, 1, sig(0x0D, key = "speed", unit = KMH, cat = VEHICLE, min = 0.0, max = 255.0) { it[0].toDouble() })
        def(0x0E, 1, sig(0x0E, key = "timing_advance", unit = DEGREE, cat = ENGINE, min = -64.0, max = 63.5, dec = 1) { it[0] / 2.0 - 64.0 })
        def(0x0F, 1, sig(0x0F, key = "intake_temp", unit = CELSIUS, cat = TEMPERATURE, min = -40.0, max = 215.0) { it[0] - 40.0 })
        def(0x10, 2, sig(0x10, key = "maf", unit = GRAMS_PER_SEC, cat = AIR, min = 0.0, max = 655.35, dec = 2) { ab(it) / 100.0 })
        def(0x11, 1, sig(0x11, key = "throttle", unit = PERCENT, cat = ENGINE, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        statePid(0x12, 1, StateDef("01.12", "secondary_air", EXHAUST) { d -> secondaryAirState(d[0]) })
        def(0x13, 1, sig(0x13, key = "o2_present", unit = COUNT, cat = OXYGEN, min = 0.0, max = 8.0) { Integer.bitCount(it[0]).toDouble() })

        // Hagyományos (keskenysávú) lambdaszondák: feszültség + rövid távú korrekció
        for (i in 0 until 8) {
            val p = 0x14 + i
            val bank = if (i < 4) 1 else 2
            val sensor = i % 4 + 1
            def(p, 2,
                sig(p, "V", "o2_b${bank}s${sensor}_voltage", VOLT, OXYGEN, 0.0, 1.275, 3) { it[0] / 200.0 },
                sig(p, "T", "o2_b${bank}s${sensor}_trim", PERCENT, OXYGEN, -100.0, 99.2, 1) { d -> if (d[1] == 0xFF) null else trim(d[1]) })
        }

        statePid(0x1C, 1, StateDef("01.1C", "obd_standard", STATUS) { d -> "obd_std_${d[0]}" })
        def(0x1F, 2, sig(0x1F, key = "run_time", unit = SECONDS, cat = ENGINE, min = 0.0, max = 65535.0) { ab(it).toDouble() })
        def(0x21, 2, sig(0x21, key = "distance_mil", unit = KM, cat = STATUS, min = 0.0, max = 65535.0) { ab(it).toDouble() })
        def(0x22, 2, sig(0x22, key = "fuel_rail_pressure_rel", unit = KPA, cat = FUEL, min = 0.0, max = 5177.265, dec = 1) { ab(it) * 0.079 })
        def(0x23, 2, sig(0x23, key = "fuel_rail_pressure", unit = KPA, cat = FUEL, min = 0.0, max = 655350.0) { ab(it) * 10.0 })

        // Szélessávú lambdaszondák (feszültség)
        for (i in 0 until 8) {
            val p = 0x24 + i
            def(p, 4,
                sig(p, "L", "o2wr_s${i + 1}_lambda", RATIO, OXYGEN, 0.0, 2.0, 3) { d -> ab(d) * 2.0 / 65536.0 },
                sig(p, "V", "o2wr_s${i + 1}_voltage", VOLT, OXYGEN, 0.0, 8.0, 3) { d -> ab(d, 2) * 8.0 / 65536.0 })
        }

        def(0x2C, 1, sig(0x2C, key = "egr_commanded", unit = PERCENT, cat = EXHAUST, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x2D, 1, sig(0x2D, key = "egr_error", unit = PERCENT, cat = EXHAUST, min = -100.0, max = 99.2, dec = 1) { trim(it[0]) })
        def(0x2E, 1, sig(0x2E, key = "evap_purge", unit = PERCENT, cat = FUEL, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x2F, 1, sig(0x2F, key = "fuel_level", unit = PERCENT, cat = FUEL, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x30, 1, sig(0x30, key = "warmups_since_clear", unit = COUNT, cat = STATUS, min = 0.0, max = 255.0) { it[0].toDouble() })
        def(0x31, 2, sig(0x31, key = "distance_since_clear", unit = KM, cat = STATUS, min = 0.0, max = 65535.0) { ab(it).toDouble() })
        def(0x32, 2, sig(0x32, key = "evap_vapor_pressure", unit = PA, cat = FUEL, min = -8192.0, max = 8191.75, dec = 2) { signed16(it) / 4.0 })
        def(0x33, 1, sig(0x33, key = "baro", unit = KPA, cat = AIR, min = 0.0, max = 255.0) { it[0].toDouble() })

        // Szélessávú lambdaszondák (áram)
        for (i in 0 until 8) {
            val p = 0x34 + i
            def(p, 4,
                sig(p, "L", "o2wrc_s${i + 1}_lambda", RATIO, OXYGEN, 0.0, 2.0, 3) { d -> ab(d) * 2.0 / 65536.0 },
                sig(p, "I", "o2wrc_s${i + 1}_current", MILLIAMP, OXYGEN, -128.0, 128.0, 2) { d -> ab(d, 2) / 256.0 - 128.0 })
        }

        def(0x3C, 2, sig(0x3C, key = "cat_temp_b1s1", unit = CELSIUS, cat = EXHAUST, min = -40.0, max = 6513.5, dec = 1) { ab(it) / 10.0 - 40.0 })
        def(0x3D, 2, sig(0x3D, key = "cat_temp_b2s1", unit = CELSIUS, cat = EXHAUST, min = -40.0, max = 6513.5, dec = 1) { ab(it) / 10.0 - 40.0 })
        def(0x3E, 2, sig(0x3E, key = "cat_temp_b1s2", unit = CELSIUS, cat = EXHAUST, min = -40.0, max = 6513.5, dec = 1) { ab(it) / 10.0 - 40.0 })
        def(0x3F, 2, sig(0x3F, key = "cat_temp_b2s2", unit = CELSIUS, cat = EXHAUST, min = -40.0, max = 6513.5, dec = 1) { ab(it) / 10.0 - 40.0 })
        def(0x41, 4) // aktuális menetciklus monitorállapota – ReadinessDecoder
        def(0x42, 2, sig(0x42, key = "module_voltage", unit = VOLT, cat = ELECTRICAL, min = 0.0, max = 65.535, dec = 2) { ab(it) / 1000.0 })
        def(0x43, 2, sig(0x43, key = "absolute_load", unit = PERCENT, cat = ENGINE, min = 0.0, max = 25700.0, dec = 1) { ab(it) * 100.0 / 255.0 })
        def(0x44, 2, sig(0x44, key = "commanded_lambda", unit = RATIO, cat = FUEL, min = 0.0, max = 2.0, dec = 3) { ab(it) * 2.0 / 65536.0 })
        def(0x45, 1, sig(0x45, key = "relative_throttle", unit = PERCENT, cat = ENGINE, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x46, 1, sig(0x46, key = "ambient_temp", unit = CELSIUS, cat = TEMPERATURE, min = -40.0, max = 215.0) { it[0] - 40.0 })
        def(0x47, 1, sig(0x47, key = "throttle_b", unit = PERCENT, cat = ENGINE, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x48, 1, sig(0x48, key = "throttle_c", unit = PERCENT, cat = ENGINE, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x49, 1, sig(0x49, key = "pedal_d", unit = PERCENT, cat = ENGINE, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x4A, 1, sig(0x4A, key = "pedal_e", unit = PERCENT, cat = ENGINE, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x4B, 1, sig(0x4B, key = "pedal_f", unit = PERCENT, cat = ENGINE, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x4C, 1, sig(0x4C, key = "throttle_commanded", unit = PERCENT, cat = ENGINE, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x4D, 2, sig(0x4D, key = "time_mil_on", unit = MINUTES, cat = STATUS, min = 0.0, max = 65535.0) { ab(it).toDouble() })
        def(0x4E, 2, sig(0x4E, key = "time_since_clear", unit = MINUTES, cat = STATUS, min = 0.0, max = 65535.0) { ab(it).toDouble() })
        statePid(0x51, 1, StateDef("01.51", "fuel_type", FUEL) { d -> "fuel_type_${d[0]}" })
        def(0x52, 1, sig(0x52, key = "ethanol", unit = PERCENT, cat = FUEL, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x53, 2, sig(0x53, key = "evap_abs_pressure", unit = KPA, cat = FUEL, min = 0.0, max = 327.675, dec = 2) { ab(it) / 200.0 })
        def(0x54, 2, sig(0x54, key = "evap_pressure_wide", unit = PA, cat = FUEL, min = -32767.0, max = 32768.0) { signed16(it).toDouble() })
        def(0x55, 2,
            sig(0x55, "A", "stft_o2_b1", PERCENT, OXYGEN, -100.0, 99.2, 1) { trim(it[0]) },
            sig(0x55, "B", "stft_o2_b3", PERCENT, OXYGEN, -100.0, 99.2, 1) { trim(it[1]) })
        def(0x56, 2,
            sig(0x56, "A", "ltft_o2_b1", PERCENT, OXYGEN, -100.0, 99.2, 1) { trim(it[0]) },
            sig(0x56, "B", "ltft_o2_b3", PERCENT, OXYGEN, -100.0, 99.2, 1) { trim(it[1]) })
        def(0x57, 2,
            sig(0x57, "A", "stft_o2_b2", PERCENT, OXYGEN, -100.0, 99.2, 1) { trim(it[0]) },
            sig(0x57, "B", "stft_o2_b4", PERCENT, OXYGEN, -100.0, 99.2, 1) { trim(it[1]) })
        def(0x58, 2,
            sig(0x58, "A", "ltft_o2_b2", PERCENT, OXYGEN, -100.0, 99.2, 1) { trim(it[0]) },
            sig(0x58, "B", "ltft_o2_b4", PERCENT, OXYGEN, -100.0, 99.2, 1) { trim(it[1]) })
        def(0x59, 2, sig(0x59, key = "fuel_rail_abs_pressure", unit = KPA, cat = FUEL, min = 0.0, max = 655350.0) { ab(it) * 10.0 })
        def(0x5A, 1, sig(0x5A, key = "relative_pedal", unit = PERCENT, cat = ENGINE, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x5B, 1, sig(0x5B, key = "hybrid_battery", unit = PERCENT, cat = ELECTRICAL, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x5C, 1, sig(0x5C, key = "oil_temp", unit = CELSIUS, cat = TEMPERATURE, min = -40.0, max = 210.0) { it[0] - 40.0 })
        def(0x5D, 2, sig(0x5D, key = "injection_timing", unit = DEGREE, cat = FUEL, min = -210.0, max = 301.992, dec = 2) { ab(it) / 128.0 - 210.0 })
        def(0x5E, 2, sig(0x5E, key = "fuel_rate", unit = LITERS_PER_HOUR, cat = FUEL, min = 0.0, max = 3276.75, dec = 2) { ab(it) / 20.0 })
        def(0x61, 1, sig(0x61, key = "torque_demand", unit = PERCENT, cat = ENGINE, min = -125.0, max = 130.0) { it[0] - 125.0 })
        def(0x62, 1, sig(0x62, key = "torque_actual", unit = PERCENT, cat = ENGINE, min = -125.0, max = 130.0) { it[0] - 125.0 })
        def(0x63, 2, sig(0x63, key = "torque_reference", unit = NEWTON_METER, cat = ENGINE, min = 0.0, max = 65535.0) { ab(it).toDouble() })

        // Szabványos dízel/turbó PID-ek (az A bájt jelzi, mely mezők érvényesek)
        def(0x70, 10,
            sig(0x70, "CA", "boost_commanded_a", KPA, DIESEL, 0.0, 300.0, 1) { d -> if (bit(d[0], 0)) ab(d, 1) * 0.03125 else null },
            sig(0x70, "AA", "boost_actual_a", KPA, DIESEL, 0.0, 300.0, 1) { d -> if (bit(d[0], 1)) ab(d, 3) * 0.03125 else null },
            sig(0x70, "CB", "boost_commanded_b", KPA, DIESEL, 0.0, 300.0, 1) { d -> if (bit(d[0], 2)) ab(d, 5) * 0.03125 else null },
            sig(0x70, "AB", "boost_actual_b", KPA, DIESEL, 0.0, 300.0, 1) { d -> if (bit(d[0], 3)) ab(d, 7) * 0.03125 else null })
        def(0x7A, 7,
            sig(0x7A, "D", "dpf_b1_delta", KPA, DIESEL, -50.0, 100.0, 2) { d -> if (bit(d[0], 0)) signed16(d, 1) * 0.01 else null },
            sig(0x7A, "I", "dpf_b1_inlet", KPA, DIESEL, 0.0, 300.0, 2) { d -> if (bit(d[0], 1)) ab(d, 3) * 0.01 else null },
            sig(0x7A, "O", "dpf_b1_outlet", KPA, DIESEL, 0.0, 300.0, 2) { d -> if (bit(d[0], 2)) ab(d, 5) * 0.01 else null })
        def(0x7C, 9,
            sig(0x7C, "I1", "dpf_temp_b1_inlet", CELSIUS, DIESEL, -40.0, 1000.0, 1) { d -> if (bit(d[0], 0)) ab(d, 1) / 10.0 - 40.0 else null },
            sig(0x7C, "O1", "dpf_temp_b1_outlet", CELSIUS, DIESEL, -40.0, 1000.0, 1) { d -> if (bit(d[0], 1)) ab(d, 3) / 10.0 - 40.0 else null },
            sig(0x7C, "I2", "dpf_temp_b2_inlet", CELSIUS, DIESEL, -40.0, 1000.0, 1) { d -> if (bit(d[0], 2)) ab(d, 5) / 10.0 - 40.0 else null },
            sig(0x7C, "O2", "dpf_temp_b2_outlet", CELSIUS, DIESEL, -40.0, 1000.0, 1) { d -> if (bit(d[0], 3)) ab(d, 7) / 10.0 - 40.0 else null })
    }

    val all: List<PidDef> = list.toList()
    private val byPid: Map<Int, PidDef> = all.associateBy { it.pid }
    private val bySignal: Map<String, Pair<PidDef, SignalDef>> =
        all.flatMap { p -> p.signals.map { it.id to (p to it) } }.toMap()
    private val byState: Map<String, Pair<PidDef, StateDef>> =
        all.flatMap { p -> p.states.map { it.id to (p to it) } }.toMap()

    fun pid(pid: Int): PidDef? = byPid[pid]
    fun signal(id: String): SignalDef? = bySignal[id]?.second
    fun pidOfSignal(id: String): PidDef? = bySignal[id]?.first ?: byState[id]?.first
    fun state(id: String): StateDef? = byState[id]?.second
    val allSignals: List<SignalDef> = all.flatMap { it.signals }
    val allStates: List<StateDef> = all.flatMap { it.states }

    /** A PID-bitmaszk-lekérdező PID-ek (0x00, 0x20, …). */
    val SUPPORT_PIDS = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

    private fun fuelSystemState(v: Int): String? = when (v) {
        0 -> null
        1 -> "fuel_sys_open_temp"
        2 -> "fuel_sys_closed"
        4 -> "fuel_sys_open_load"
        8 -> "fuel_sys_open_fault"
        16 -> "fuel_sys_closed_fault"
        else -> "fuel_sys_unknown"
    }

    private fun secondaryAirState(v: Int): String? = when (v) {
        1 -> "sec_air_upstream"
        2 -> "sec_air_downstream"
        4 -> "sec_air_atmosphere"
        8 -> "sec_air_diag"
        else -> null
    }
}
