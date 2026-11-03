package hu.obddiag.core.diag

import hu.obddiag.core.elm.EcuMessage

/** Mode 06 teszteredmény (CAN formátum, SAE J1979 / ISO 15031-5). */
data class Mode06Result(
    val ecu: String,
    val mid: Int,
    val tid: Int,
    val uasId: Int,
    val value: Double,
    val min: Double,
    val max: Double,
    val unit: String,
    /** Igaz, ha a skálázás ismert; hamis esetén a nyers értékek látszanak. */
    val scaled: Boolean
) {
    val passed: Boolean get() = value in min..max
}

object Mode06Decoder {

    private data class Uas(val factor: Double, val offset: Double, val unit: String, val signed: Boolean)

    // Unit and Scaling ID táblázat – a gyakran használt elemek
    private val UAS: Map<Int, Uas> = mapOf(
        0x01 to Uas(1.0, 0.0, "", false),
        0x02 to Uas(0.1, 0.0, "", false),
        0x03 to Uas(0.01, 0.0, "", false),
        0x04 to Uas(0.001, 0.0, "", false),
        0x05 to Uas(0.0000305, 0.0, "", false),
        0x06 to Uas(0.000305, 0.0, "", false),
        0x07 to Uas(0.25, 0.0, "1/min", false),
        0x08 to Uas(0.01, 0.0, "km/h", false),
        0x09 to Uas(1.0, 0.0, "km/h", false),
        0x0A to Uas(0.122, 0.0, "mV", false),
        0x0B to Uas(0.001, 0.0, "V", false),
        0x0C to Uas(0.01, 0.0, "V", false),
        0x0D to Uas(0.00390625, 0.0, "mA", false),
        0x0E to Uas(0.001, 0.0, "A", false),
        0x0F to Uas(0.01, 0.0, "A", false),
        0x10 to Uas(1.0, 0.0, "ms", false),
        0x11 to Uas(100.0, 0.0, "ms", false),
        0x12 to Uas(1.0, 0.0, "s", false),
        0x13 to Uas(1.0, 0.0, "mΩ", false),
        0x14 to Uas(1.0, 0.0, "Ω", false),
        0x15 to Uas(1.0, 0.0, "kΩ", false),
        0x16 to Uas(0.1, -40.0, "°C", false),
        0x17 to Uas(0.01, 0.0, "kPa", false),
        0x18 to Uas(0.0117, 0.0, "kPa", false),
        0x19 to Uas(0.079, 0.0, "kPa", false),
        0x1A to Uas(1.0, 0.0, "kPa", false),
        0x1B to Uas(10.0, 0.0, "kPa", false),
        0x1C to Uas(0.01, 0.0, "°", false),
        0x1D to Uas(0.5, 0.0, "°", false),
        0x1E to Uas(0.0000305, 0.0, "λ", false),
        0x1F to Uas(0.05, 0.0, "A/F", false),
        0x20 to Uas(0.0039062, 0.0, "", false),
        0x21 to Uas(1.0, 0.0, "mHz", false),
        0x22 to Uas(1.0, 0.0, "Hz", false),
        0x23 to Uas(1.0, 0.0, "kHz", false),
        0x24 to Uas(1.0, 0.0, "db", false),
        0x25 to Uas(1.0, 0.0, "km", false),
        0x26 to Uas(0.1, 0.0, "mV/ms", false),
        0x27 to Uas(0.01, 0.0, "g/s", false),
        0x28 to Uas(1.0, 0.0, "g/s", false),
        0x29 to Uas(0.25, 0.0, "Pa/s", false),
        0x2A to Uas(0.001, 0.0, "kg/h", false),
        0x2B to Uas(1.0, 0.0, "kapcsolás", false),
        0x2C to Uas(0.01, 0.0, "g/henger", false),
        0x2D to Uas(0.01, 0.0, "mg/löket", false),
        0x2F to Uas(0.01, 0.0, "%", false),
        0x30 to Uas(0.001526, 0.0, "%", false),
        0x31 to Uas(0.001, 0.0, "L", false),
        0x34 to Uas(1.0, 0.0, "min", false),
        0x35 to Uas(10.0, 0.0, "ms", false),
        0x81 to Uas(1.0, 0.0, "", true),
        0x82 to Uas(0.1, 0.0, "", true),
        0x83 to Uas(0.01, 0.0, "", true),
        0x84 to Uas(0.001, 0.0, "", true),
        0x85 to Uas(0.0000305, 0.0, "", true),
        0x86 to Uas(0.000305, 0.0, "", true),
        0x8A to Uas(0.122, 0.0, "mV", true),
        0x8B to Uas(0.001, 0.0, "V", true),
        0x8C to Uas(0.01, 0.0, "V", true),
        0x8D to Uas(0.00390625, 0.0, "mA", true),
        0x8E to Uas(0.001, 0.0, "A", true),
        0x90 to Uas(1.0, 0.0, "ms", true),
        0x96 to Uas(0.1, 0.0, "°C", true),
        0x9C to Uas(0.01, 0.0, "°", true),
        0x9D to Uas(0.5, 0.0, "°", true),
        0xA8 to Uas(1.0, 0.0, "g/s", true),
        0xA9 to Uas(0.25, 0.0, "Pa/s", true),
        0xAF to Uas(0.01, 0.0, "%", true),
        0xB0 to Uas(0.003052, 0.0, "%", true),
        0xFD to Uas(0.001, 0.0, "kPa", true),
        0xFE to Uas(0.25, 0.0, "Pa", true),
    )

    private fun raw16(hi: Int, lo: Int, signed: Boolean): Int {
        val v = hi * 256 + lo
        return if (signed && v >= 0x8000) v - 0x10000 else v
    }

    /** A 46 xx válaszok feldolgozása: minden teszt 9 bájt (MID, TID, UASID, érték, min, max). */
    fun decode(messages: List<EcuMessage>): List<Mode06Result> {
        val out = mutableListOf<Mode06Result>()
        for (m in messages) {
            val d = m.data.map { it.toInt() and 0xFF }
            if (d.isEmpty() || d[0] != 0x46) continue
            var i = 1
            // A 0600, 0620… támogatás-lekérdezésre adott válasz 4 bájtos maszk – azt nem dekódoljuk eredményként.
            if (d.size == 6 && d[1] % 0x20 == 0) continue
            while (i + 9 <= d.size) {
                val mid = d[i]; val tid = d[i + 1]; val uasId = d[i + 2]
                val uas = UAS[uasId]
                val signed = uas?.signed ?: false
                val factor = uas?.factor ?: 1.0
                val offset = uas?.offset ?: 0.0
                fun scale(hi: Int, lo: Int) = raw16(hi, lo, signed) * factor + offset
                out += Mode06Result(
                    m.ecu, mid, tid, uasId,
                    scale(d[i + 3], d[i + 4]), scale(d[i + 5], d[i + 6]), scale(d[i + 7], d[i + 8]),
                    uas?.unit ?: "nyers (UASID 0x%02X)".format(uasId), uas != null
                )
                i += 9
            }
        }
        return out
    }

    /** A monitor (OBDMID) magyar megnevezésének fordítási kulcsa. */
    fun midKey(mid: Int): String = when (mid) {
        in 0x01..0x10 -> "mid_o2_sensor"
        in 0x21..0x24 -> "mid_catalyst"
        in 0x31..0x38 -> "mid_egr_vvt"
        in 0x39..0x3D -> "mid_evap"
        in 0x41..0x50 -> "mid_o2_heater"
        in 0x61..0x64 -> "mid_heated_catalyst"
        in 0x71..0x74 -> "mid_secondary_air"
        in 0x81..0x84 -> "mid_fuel_system"
        in 0x85..0x86 -> "mid_boost"
        in 0x90..0x91 -> "mid_nox_adsorber"
        in 0x98..0x99 -> "mid_nox_catalyst"
        0xA1 -> "mid_misfire_general"
        in 0xA2..0xAD -> "mid_misfire_cylinder"
        in 0xB0..0xB1 -> "mid_pm_filter"
        else -> "mid_other"
    }
}
