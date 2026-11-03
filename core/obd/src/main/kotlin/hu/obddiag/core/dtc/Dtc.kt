package hu.obddiag.core.dtc

import hu.obddiag.core.elm.EcuMessage

enum class DtcCategory(val letter: Char) { POWERTRAIN('P'), CHASSIS('C'), BODY('B'), NETWORK('U') }

/** Melyik Mode-ból származik a hibakód. */
enum class DtcStatus { STORED, PENDING, PERMANENT }

data class Dtc(val code: String, val status: DtcStatus, val ecu: String) {
    val category: DtcCategory get() = DtcCategory.entries.first { it.letter == code[0] }

    /** SAE J2012: P0, P2, P34–P39, B0, C0, U0 szabványos (generikus); a többi gyártóspecifikus vagy fenntartott. */
    val isGeneric: Boolean
        get() {
            val d1 = code[1]
            return when (category) {
                DtcCategory.POWERTRAIN -> d1 == '0' || d1 == '2' || (d1 == '3' && code[2] in '4'..'9')
                else -> d1 == '0'
            }
        }
}

object DtcParser {

    /** Két bájt (A, B) átalakítása hibakóddá, pl. 0x01 0x33 → "P0133". 0x0000 esetén null (kitöltés). */
    fun decode(a: Int, b: Int): String? {
        if (a == 0 && b == 0) return null
        val letter = DtcCategory.entries[(a shr 6) and 0x03].letter
        val d1 = (a shr 4) and 0x03
        val d2 = a and 0x0F
        return "%c%d%X%02X".format(letter, d1, d2, b)
    }

    /** Hibakód szövegből bájtpárrá (tesztekhez és a szimulátorhoz). */
    fun encode(code: String): Pair<Int, Int> {
        require(code.length == 5) { "Érvénytelen hibakód: $code" }
        val cat = DtcCategory.entries.indexOfFirst { it.letter == code[0].uppercaseChar() }
        require(cat >= 0) { "Érvénytelen hibakód-kategória: $code" }
        val d1 = code[1].digitToInt()
        require(d1 in 0..3)
        val d2 = code[2].digitToInt(16)
        val b = code.substring(3).toInt(16)
        return ((cat shl 6) or (d1 shl 4) or d2) to b
    }

    /**
     * Mode 03 / 07 / 0A válaszok feldolgozása.
     *
     * CAN esetén a szolgáltatásbájt után a hibakódok száma következik, majd kétbájtos kódok.
     * Hagyományos protokollokon (J1850, ISO 9141, KWP) nincs darabszám; minden üzenet 3 kódot (6 bájt)
     * tartalmaz, a hiányzókat 0000 tölti ki.
     */
    fun parse(messages: List<EcuMessage>, status: DtcStatus, isCan: Boolean): List<Dtc> {
        val responseService = when (status) {
            DtcStatus.STORED -> 0x43
            DtcStatus.PENDING -> 0x47
            DtcStatus.PERMANENT -> 0x4A
        }
        val out = LinkedHashSet<Dtc>()
        for (m in messages) {
            val d = m.data.map { it.toInt() and 0xFF }
            if (d.isEmpty() || d[0] != responseService) continue
            val payload = if (isCan) {
                val count = d.getOrNull(1) ?: 0
                d.drop(2).take(count * 2)
            } else {
                d.drop(1)
            }
            var i = 0
            while (i + 1 < payload.size) {
                decode(payload[i], payload[i + 1])?.let { out += Dtc(it, status, m.ecu) }
                i += 2
            }
        }
        return out.toList()
    }
}
