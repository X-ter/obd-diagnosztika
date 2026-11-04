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
    private fun u32(d: IntArray, i: Int): Double = ((d[i].toLong() shl 24) or (d[i + 1].toLong() shl 16) or (d[i + 2].toLong() shl 8) or d[i + 3].toLong()).toDouble()

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

        // ───── További szabványos PID-ek (SAE J1979 / ISO 15031-5) ─────
        // A több érzékelős PID-eknél az A bájt bitjei jelzik, mely mezők érvényesek; a nem támogatott mező nem kap értéket.
        def(0x1D, 1, sig(0x1D, key = "o2_present_4bank", unit = COUNT, cat = OXYGEN, min = 0.0, max = 8.0) { Integer.bitCount(it[0]).toDouble() })
        statePid(0x1E, 1, StateDef("01.1E", "pto_status", STATUS) { d -> if (bit(d[0], 0)) "pto_on" else "pto_off" })
        def(0x4F, 4,
            sig(0x4F, "L", "max_lambda", RATIO, STATUS, 0.0, 255.0) { d -> d[0].toDouble().takeIf { it > 0 } },
            sig(0x4F, "V", "max_o2_voltage", VOLT, STATUS, 0.0, 255.0) { d -> d[1].toDouble().takeIf { it > 0 } },
            sig(0x4F, "I", "max_o2_current", MILLIAMP, STATUS, 0.0, 255.0) { d -> d[2].toDouble().takeIf { it > 0 } },
            sig(0x4F, "M", "max_map", KPA, STATUS, 0.0, 2550.0) { d -> (d[3] * 10.0).takeIf { it > 0 } })
        def(0x50, 4, sig(0x50, key = "max_maf", unit = GRAMS_PER_SEC, cat = STATUS, min = 0.0, max = 2550.0) { d -> (d[0] * 10.0).takeIf { it > 0 } })
        def(0x64, 5,
            sig(0x64, "I", "torque_idle", PERCENT, ENGINE, -125.0, 130.0) { it[0] - 125.0 },
            sig(0x64, "1", "torque_point1", PERCENT, ENGINE, -125.0, 130.0) { it[1] - 125.0 },
            sig(0x64, "2", "torque_point2", PERCENT, ENGINE, -125.0, 130.0) { it[2] - 125.0 },
            sig(0x64, "3", "torque_point3", PERCENT, ENGINE, -125.0, 130.0) { it[3] - 125.0 },
            sig(0x64, "4", "torque_point4", PERCENT, ENGINE, -125.0, 130.0) { it[4] - 125.0 })
        def(0x66, 5,
            sig(0x66, "A", "maf_a", GRAMS_PER_SEC, AIR, 0.0, 2048.0, 2) { d -> if (bit(d[0], 0)) ab(d, 1) / 32.0 else null },
            sig(0x66, "B", "maf_b", GRAMS_PER_SEC, AIR, 0.0, 2048.0, 2) { d -> if (bit(d[0], 1)) ab(d, 3) / 32.0 else null })
        def(0x67, 3,
            sig(0x67, "1", "coolant_temp_1", CELSIUS, TEMPERATURE, -40.0, 215.0) { d -> if (bit(d[0], 0)) d[1] - 40.0 else null },
            sig(0x67, "2", "coolant_temp_2", CELSIUS, TEMPERATURE, -40.0, 215.0) { d -> if (bit(d[0], 1)) d[2] - 40.0 else null })
        def(0x68, 7,
            *listOf("b1s1", "b1s2", "b1s3", "b2s1", "b2s2", "b2s3").mapIndexed { i, n ->
                sig(0x68, n.uppercase(), "intake_temp_$n", CELSIUS, TEMPERATURE, -40.0, 215.0) { d -> if (bit(d[0], i)) d[i + 1] - 40.0 else null }
            }.toTypedArray())
        def(0x69, 7,
            sig(0x69, "CA", "egr_a_commanded", PERCENT, EXHAUST, 0.0, 100.0, 1) { d -> if (bit(d[0], 0)) pct(d[1]) else null },
            sig(0x69, "AA", "egr_a_actual", PERCENT, EXHAUST, 0.0, 100.0, 1) { d -> if (bit(d[0], 1)) pct(d[2]) else null },
            sig(0x69, "EA", "egr_a_error", PERCENT, EXHAUST, -100.0, 99.2, 1) { d -> if (bit(d[0], 2)) trim(d[3]) else null },
            sig(0x69, "CB", "egr_b_commanded", PERCENT, EXHAUST, 0.0, 100.0, 1) { d -> if (bit(d[0], 3)) pct(d[4]) else null },
            sig(0x69, "AB", "egr_b_actual", PERCENT, EXHAUST, 0.0, 100.0, 1) { d -> if (bit(d[0], 4)) pct(d[5]) else null },
            sig(0x69, "EB", "egr_b_error", PERCENT, EXHAUST, -100.0, 99.2, 1) { d -> if (bit(d[0], 5)) trim(d[6]) else null })
        def(0x6A, 5,
            sig(0x6A, "CA", "intake_flow_a_commanded", PERCENT, DIESEL, 0.0, 100.0, 1) { d -> if (bit(d[0], 0)) pct(d[1]) else null },
            sig(0x6A, "PA", "intake_flow_a_position", PERCENT, DIESEL, 0.0, 100.0, 1) { d -> if (bit(d[0], 1)) pct(d[2]) else null },
            sig(0x6A, "CB", "intake_flow_b_commanded", PERCENT, DIESEL, 0.0, 100.0, 1) { d -> if (bit(d[0], 2)) pct(d[3]) else null },
            sig(0x6A, "PB", "intake_flow_b_position", PERCENT, DIESEL, 0.0, 100.0, 1) { d -> if (bit(d[0], 3)) pct(d[4]) else null })
        def(0x6B, 5,
            *listOf("b1s1", "b1s2", "b2s1", "b2s2").mapIndexed { i, n ->
                sig(0x6B, n.uppercase(), "egr_temp_$n", CELSIUS, EXHAUST, -40.0, 215.0) { d -> if (bit(d[0], i)) d[i + 1] - 40.0 else null }
            }.toTypedArray())
        def(0x6C, 5,
            sig(0x6C, "CA", "throttle_actuator_a_commanded", PERCENT, ENGINE, 0.0, 100.0, 1) { d -> if (bit(d[0], 0)) pct(d[1]) else null },
            sig(0x6C, "PA", "throttle_actuator_a_position", PERCENT, ENGINE, 0.0, 100.0, 1) { d -> if (bit(d[0], 1)) pct(d[2]) else null },
            sig(0x6C, "CB", "throttle_actuator_b_commanded", PERCENT, ENGINE, 0.0, 100.0, 1) { d -> if (bit(d[0], 2)) pct(d[3]) else null },
            sig(0x6C, "PB", "throttle_actuator_b_position", PERCENT, ENGINE, 0.0, 100.0, 1) { d -> if (bit(d[0], 3)) pct(d[4]) else null })
        def(0x6D, 11,
            sig(0x6D, "CA", "rail_a_commanded", KPA, FUEL, 0.0, 655350.0) { d -> if (bit(d[0], 0)) ab(d, 1) * 10.0 else null },
            sig(0x6D, "AA", "rail_a_actual", KPA, FUEL, 0.0, 655350.0) { d -> if (bit(d[0], 1)) ab(d, 3) * 10.0 else null },
            sig(0x6D, "TA", "rail_a_temp", CELSIUS, FUEL, -40.0, 215.0) { d -> if (bit(d[0], 2)) d[5] - 40.0 else null },
            sig(0x6D, "CB", "rail_b_commanded", KPA, FUEL, 0.0, 655350.0) { d -> if (bit(d[0], 3)) ab(d, 6) * 10.0 else null },
            sig(0x6D, "AB", "rail_b_actual", KPA, FUEL, 0.0, 655350.0) { d -> if (bit(d[0], 4)) ab(d, 8) * 10.0 else null },
            sig(0x6D, "TB", "rail_b_temp", CELSIUS, FUEL, -40.0, 215.0) { d -> if (bit(d[0], 5)) d[10] - 40.0 else null })
        def(0x6E, 9,
            sig(0x6E, "CA", "injection_a_commanded", KPA, FUEL, 0.0, 655350.0) { d -> if (bit(d[0], 0)) ab(d, 1) * 10.0 else null },
            sig(0x6E, "AA", "injection_a_actual", KPA, FUEL, 0.0, 655350.0) { d -> if (bit(d[0], 1)) ab(d, 3) * 10.0 else null },
            sig(0x6E, "CB", "injection_b_commanded", KPA, FUEL, 0.0, 655350.0) { d -> if (bit(d[0], 2)) ab(d, 5) * 10.0 else null },
            sig(0x6E, "AB", "injection_b_actual", KPA, FUEL, 0.0, 655350.0) { d -> if (bit(d[0], 3)) ab(d, 7) * 10.0 else null })
        def(0x6F, 3,
            sig(0x6F, "A", "turbo_inlet_pressure_a", KPA, DIESEL, 0.0, 255.0) { d -> if (bit(d[0], 0)) d[1].toDouble() else null },
            sig(0x6F, "B", "turbo_inlet_pressure_b", KPA, DIESEL, 0.0, 255.0) { d -> if (bit(d[0], 1)) d[2].toDouble() else null })
        def(0x71, 6,
            sig(0x71, "CA", "vgt_a_commanded", PERCENT, DIESEL, 0.0, 100.0, 1) { d -> if (bit(d[0], 0)) pct(d[1]) else null },
            sig(0x71, "PA", "vgt_a_position", PERCENT, DIESEL, 0.0, 100.0, 1) { d -> if (bit(d[0], 1)) pct(d[2]) else null },
            sig(0x71, "CB", "vgt_b_commanded", PERCENT, DIESEL, 0.0, 100.0, 1) { d -> if (bit(d[0], 2)) pct(d[3]) else null },
            sig(0x71, "PB", "vgt_b_position", PERCENT, DIESEL, 0.0, 100.0, 1) { d -> if (bit(d[0], 3)) pct(d[4]) else null })
        def(0x72, 5,
            sig(0x72, "CA", "wastegate_a_commanded", PERCENT, DIESEL, 0.0, 100.0, 1) { d -> if (bit(d[0], 0)) pct(d[1]) else null },
            sig(0x72, "PA", "wastegate_a_position", PERCENT, DIESEL, 0.0, 100.0, 1) { d -> if (bit(d[0], 1)) pct(d[2]) else null },
            sig(0x72, "CB", "wastegate_b_commanded", PERCENT, DIESEL, 0.0, 100.0, 1) { d -> if (bit(d[0], 2)) pct(d[3]) else null },
            sig(0x72, "PB", "wastegate_b_position", PERCENT, DIESEL, 0.0, 100.0, 1) { d -> if (bit(d[0], 3)) pct(d[4]) else null })
        def(0x73, 5,
            sig(0x73, "1", "exhaust_pressure_b1", KPA, EXHAUST, 0.0, 655.35, 2) { d -> if (bit(d[0], 0)) ab(d, 1) * 0.01 else null },
            sig(0x73, "2", "exhaust_pressure_b2", KPA, EXHAUST, 0.0, 655.35, 2) { d -> if (bit(d[0], 1)) ab(d, 3) * 0.01 else null })
        def(0x74, 5,
            sig(0x74, "A", "turbo_rpm_a", RPM, DIESEL, 0.0, 250000.0) { d -> if (bit(d[0], 0)) ab(d, 1) * 10.0 else null },
            sig(0x74, "B", "turbo_rpm_b", RPM, DIESEL, 0.0, 250000.0) { d -> if (bit(d[0], 1)) ab(d, 3) * 10.0 else null })
        for ((p, t) in listOf(0x75 to "a", 0x76 to "b")) {
            def(p, 7,
                sig(p, "CI", "turbo_${t}_compressor_inlet_temp", CELSIUS, DIESEL, -40.0, 215.0) { d -> if (bit(d[0], 0)) d[1] - 40.0 else null },
                sig(p, "CO", "turbo_${t}_compressor_outlet_temp", CELSIUS, DIESEL, -40.0, 215.0) { d -> if (bit(d[0], 1)) d[2] - 40.0 else null },
                sig(p, "TI", "turbo_${t}_turbine_inlet_temp", CELSIUS, DIESEL, -40.0, 1200.0, 1) { d -> if (bit(d[0], 2)) ab(d, 3) / 10.0 - 40.0 else null },
                sig(p, "TO", "turbo_${t}_turbine_outlet_temp", CELSIUS, DIESEL, -40.0, 1200.0, 1) { d -> if (bit(d[0], 3)) ab(d, 5) / 10.0 - 40.0 else null })
        }
        def(0x77, 5,
            *listOf("b1s1", "b1s2", "b2s1", "b2s2").mapIndexed { i, n ->
                sig(0x77, n.uppercase(), "charge_air_temp_$n", CELSIUS, TEMPERATURE, -40.0, 215.0) { d -> if (bit(d[0], i)) d[i + 1] - 40.0 else null }
            }.toTypedArray())
        // Kipufogógáz-hőmérséklet (EGT): 0x78/0x79 az 1–4., 0x98/0x99 az 5–8. érzékelő
        for ((p, bank, first) in listOf(Triple(0x78, 1, 1), Triple(0x79, 2, 1), Triple(0x98, 1, 5), Triple(0x99, 2, 5))) {
            def(p, 9, *(0 until 4).map { i ->
                sig(p, "S${first + i}", "egt_b${bank}s${first + i}", CELSIUS, EXHAUST, -40.0, 1200.0, 1) { d -> if (bit(d[0], i)) ab(d, 1 + i * 2) / 10.0 - 40.0 else null }
            }.toTypedArray())
        }
        def(0x7B, 7,
            sig(0x7B, "D", "dpf_b2_delta", KPA, DIESEL, -50.0, 100.0, 2) { d -> if (bit(d[0], 0)) signed16(d, 1) * 0.01 else null },
            sig(0x7B, "I", "dpf_b2_inlet", KPA, DIESEL, 0.0, 300.0, 2) { d -> if (bit(d[0], 1)) ab(d, 3) * 0.01 else null },
            sig(0x7B, "O", "dpf_b2_outlet", KPA, DIESEL, 0.0, 300.0, 2) { d -> if (bit(d[0], 2)) ab(d, 5) * 0.01 else null })
        def(0x7F, 13,
            sig(0x7F, "T", "engine_total_hours", HOURS, ENGINE, 0.0, 1_200_000.0, 1) { d -> if (bit(d[0], 0)) u32(d, 1) / 3600.0 else null },
            sig(0x7F, "I", "engine_idle_hours", HOURS, ENGINE, 0.0, 1_200_000.0, 1) { d -> if (bit(d[0], 1)) u32(d, 5) / 3600.0 else null },
            sig(0x7F, "P", "engine_pto_hours", HOURS, ENGINE, 0.0, 1_200_000.0, 1) { d -> if (bit(d[0], 2)) u32(d, 9) / 3600.0 else null })
        def(0x83, 9, *(0 until 4).map { i ->
            sig(0x83, "${i + 1}", "nox_${i + 1}", PPM, EXHAUST, 0.0, 65535.0) { d -> if (bit(d[0], i)) ab(d, 1 + i * 2).toDouble() else null }
        }.toTypedArray())
        def(0x84, 1, sig(0x84, key = "manifold_surface_temp", unit = CELSIUS, cat = TEMPERATURE, min = -40.0, max = 215.0) { it[0] - 40.0 })
        def(0x85, 10,
            sig(0x85, "R", "def_consumption", LITERS_PER_HOUR, DIESEL, 0.0, 327.675, 3) { d -> if (bit(d[0], 0)) ab(d, 1) * 0.005 else null },
            sig(0x85, "D", "def_demanded", LITERS_PER_HOUR, DIESEL, 0.0, 327.675, 3) { d -> if (bit(d[0], 1)) ab(d, 3) * 0.005 else null },
            sig(0x85, "L", "def_tank_level", PERCENT, DIESEL, 0.0, 100.0, 1) { d -> if (bit(d[0], 2)) pct(d[5]) else null })
        def(0x86, 5,
            sig(0x86, "1", "pm_b1", MG_PER_M3, EXHAUST, 0.0, 819.0, 2) { d -> if (bit(d[0], 0)) ab(d, 1) * 0.0125 else null },
            sig(0x86, "2", "pm_b2", MG_PER_M3, EXHAUST, 0.0, 819.0, 2) { d -> if (bit(d[0], 1)) ab(d, 3) * 0.0125 else null })
        def(0x87, 5,
            sig(0x87, "A", "map_a", KPA, AIR, 0.0, 2048.0, 1) { d -> if (bit(d[0], 0)) ab(d, 1) / 32.0 else null },
            sig(0x87, "B", "map_b", KPA, AIR, 0.0, 2048.0, 1) { d -> if (bit(d[0], 1)) ab(d, 3) / 32.0 else null })
        def(0x8D, 1, sig(0x8D, key = "throttle_g", unit = PERCENT, cat = ENGINE, min = 0.0, max = 100.0, dec = 1) { pct(it[0]) })
        def(0x8E, 1, sig(0x8E, key = "friction_torque", unit = PERCENT, cat = ENGINE, min = -125.0, max = 130.0) { it[0] - 125.0 })
        def(0x9D, 4,
            sig(0x9D, "E", "engine_fuel_rate", GRAMS_PER_SEC, FUEL, 0.0, 1310.7, 2) { d -> ab(d, 0) * 0.02 },
            sig(0x9D, "V", "vehicle_fuel_rate", GRAMS_PER_SEC, FUEL, 0.0, 1310.7, 2) { d -> ab(d, 2) * 0.02 })
        def(0x9E, 2, sig(0x9E, key = "exhaust_flow", unit = KG_PER_HOUR, cat = EXHAUST, min = 0.0, max = 13107.0, dec = 1) { ab(it) * 0.2 })
        def(0xA2, 2, sig(0xA2, key = "cylinder_fuel_rate", unit = MG_PER_STROKE, cat = FUEL, min = 0.0, max = 2048.0, dec = 2) { ab(it) * 0.03125 })
        def(0xA4, 4,
            sig(0xA4, "G", "gear", GEAR, TRANSMISSION, 0.0, 15.0) { d -> (d[1] shr 4).toDouble() },
            sig(0xA4, "R", "gear_ratio", NONE, TRANSMISSION, 0.0, 65.535, 3) { d -> ab(d, 2).takeIf { it > 0 }?.let { it / 1000.0 } })
        def(0xA5, 4, sig(0xA5, key = "def_dosing", unit = PERCENT, cat = DIESEL, min = 0.0, max = 127.5, dec = 1) { d -> d[1] * 0.5 })
        def(0xA6, 4, sig(0xA6, key = "odometer", unit = KM, cat = VEHICLE, min = 0.0, max = 429_496_729.5, dec = 1) { d -> u32(d, 0) / 10.0 })
    }

    val all: List<PidDef> = list.toList()
    private val byPid: Map<Int, PidDef> = all.associateBy { it.pid }
    private val bySignal: Map<String, Pair<PidDef, SignalDef>> =
        all.flatMap { p -> p.signals.map { it.id to (p to it) } }.toMap()
    private val byState: Map<String, Pair<PidDef, StateDef>> =
        all.flatMap { p -> p.states.map { it.id to (p to it) } }.toMap()

    fun pid(pid: Int): PidDef? = byPid[pid]
    /** Szabványos vagy számított jel definíciója. */
    fun signal(id: String): SignalDef? = bySignal[id]?.second ?: DerivedSignals.signal(id)

    /** A jel lekérdezéséhez szükséges Mode 01 PID-ek (számított értéknél a bemenetei). */
    fun requiredPids(id: String): Set<Int> = pidOfSignal(id)?.let { setOf(it.pid) } ?: DerivedSignals.requiredPids(id)
    fun pidOfSignal(id: String): PidDef? = bySignal[id]?.first ?: byState[id]?.first
    fun state(id: String): StateDef? = byState[id]?.second
    val allSignals: List<SignalDef> = all.flatMap { it.signals }
    val allStates: List<StateDef> = all.flatMap { it.states }

    /** A PID-bitmaszk-lekérdező PID-ek (0x00, 0x20, …). */
    val SUPPORT_PIDS = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)

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
