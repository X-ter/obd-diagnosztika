package hu.obddiag.core.diag

import hu.obddiag.core.elm.EcuMessage

/** Mode 09 – járműinformációk (VIN, kalibrációs azonosítók, ECU-név). */
object Mode09Decoder {

    /**
     * Egy InfoType összes adatbájtja, ECU-nként. CAN-en egy (ISO-TP) üzenet érkezik,
     * amelynek a harmadik bájtja az adatelemek száma; hagyományos protokollokon több üzenet,
     * mindegyik sorszámmal kezdődik.
     */
    fun payloadByEcu(messages: List<EcuMessage>, infoType: Int, isCan: Boolean): Map<String, List<Int>> {
        val result = LinkedHashMap<String, MutableList<Int>>()
        for (m in messages) {
            val d = m.data.map { it.toInt() and 0xFF }
            if (d.size < 3 || d[0] != 0x49 || d[1] != infoType) continue
            // A harmadik bájt CAN-en az elemszám, hagyományos protokollon az üzenet sorszáma – mindkét esetben kihagyjuk.
            // A paraméter megmarad, mert egyes InfoType-ok értelmezése protokollfüggő lehet.
            @Suppress("UNUSED_VARIABLE") val can = isCan
            result.getOrPut(m.ecu) { mutableListOf() }.addAll(d.drop(3))
        }
        return result
    }

    private fun ascii(bytes: List<Int>): String =
        bytes.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()

    /** VIN (InfoType 02). A kitöltő bájtokat elhagyja; érvénytelen esetén null. */
    fun vin(messages: List<EcuMessage>, isCan: Boolean): String? {
        val all = payloadByEcu(messages, 0x02, isCan)
        for ((_, bytes) in all) {
            val text = ascii(bytes).replace(" ", "")
            val candidate = if (text.length >= 17) text.takeLast(17) else continue
            if (isPlausibleVin(candidate)) return candidate
        }
        return null
    }

    fun isPlausibleVin(vin: String): Boolean =
        vin.length == 17 && vin.all { it in '0'..'9' || it in 'A'..'Z' } && vin.none { it == 'I' || it == 'O' || it == 'Q' }

    /** Kalibrációs azonosítók (InfoType 04): 16 karakteres ASCII blokkok. */
    fun calibrationIds(messages: List<EcuMessage>, isCan: Boolean): List<Pair<String, String>> =
        payloadByEcu(messages, 0x04, isCan).flatMap { (ecu, bytes) ->
            bytes.chunked(16).map { ecu to ascii(it) }.filter { it.second.isNotEmpty() }
        }

    /** Kalibrációs ellenőrzőszámok (InfoType 06): 4 bájtos hexa értékek. */
    fun cvns(messages: List<EcuMessage>, isCan: Boolean): List<Pair<String, String>> =
        payloadByEcu(messages, 0x06, isCan).flatMap { (ecu, bytes) ->
            bytes.chunked(4).filter { it.size == 4 }.map { ecu to it.joinToString("") { b -> "%02X".format(b) } }
        }

    /** ECU-név (InfoType 0A). */
    fun ecuNames(messages: List<EcuMessage>, isCan: Boolean): Map<String, String> =
        payloadByEcu(messages, 0x0A, isCan).mapValues { (_, b) -> ascii(b).replace('\u0000', ' ').trim() }
            .filterValues { it.isNotEmpty() }
}
