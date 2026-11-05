package hu.obddiag.core.ident

/**
 * Gyártói csoportok. Egy adatforrás (pl. PSA-RE) ezek szerint jelzi, mely járművekre van adata.
 * A csoport a VIN első három karakteréből (WMI, ISO 3780) vagy a felhasználó kifejezett megadásából származik.
 */
enum class BrandGroup(val label: String) {
    STELLANTIS_PSA("Stellantis – PSA-platform (Peugeot, Citroën, DS, Opel 2017+)"),
    STELLANTIS_FCA("Stellantis – FCA (Fiat, Alfa Romeo, Jeep, Lancia)"),
    GM_OPEL("Opel/Vauxhall – GM-korszak (2017 előtt)"),
    VAG("Volkswagen-csoport (VW, Audi, Škoda, Seat, Cupra, Porsche)"),
    BMW("BMW / Mini"),
    MERCEDES("Mercedes-Benz / smart"),
    RENAULT_NISSAN("Renault / Dacia / Nissan"),
    FORD("Ford"),
    TOYOTA("Toyota / Lexus"),
    MAZDA("Mazda"),
    HONDA("Honda"),
    HYUNDAI_KIA("Hyundai / Kia"),
    VOLVO("Volvo"),
    SUZUKI("Suzuki"),
    MITSUBISHI("Mitsubishi"),
    SUBARU("Subaru"),
    OTHER("Egyéb / ismeretlen");
}

/** A VIN szerkezeti elemzése (ISO 3779 / 3780). Csak a dokumentált, járműfüggetlen részeket értelmezi. */
data class VinInfo(
    val vin: String,
    val wmi: String,
    val manufacturer: String?,
    val group: BrandGroup,
    /** A 10. karakterből adódó lehetséges modellévek (a kód 30 évente ismétlődik). */
    val modelYears: List<Int>,
    /** Csak az észak-amerikai VIN-eknél kötelező; Európában a 9. karakter tetszőleges lehet. */
    val checkDigitValid: Boolean
)

object VinDecoder {
    /**
     * WMI → (gyártó, csoport). Csak ellenőrzött, közismert WMI-k; ami nincs a listában, az ismeretlen
     * (a felismerés ilyenkor nem találgat, hanem a felhasználótól kér márkát).
     */
    val WMI: Map<String, Pair<String, BrandGroup>> = buildMap {
        fun put(g: BrandGroup, name: String, vararg w: String) = w.forEach { put(it, name to g) }
        put(BrandGroup.STELLANTIS_PSA, "Peugeot", "VF3", "VR3")
        put(BrandGroup.STELLANTIS_PSA, "Citroën", "VF7", "VR7")
        put(BrandGroup.STELLANTIS_PSA, "DS Automobiles", "VR1")
        put(BrandGroup.STELLANTIS_PSA, "Opel/Vauxhall (Stellantis)", "W0V", "VXK")
        put(BrandGroup.GM_OPEL, "Opel/Vauxhall (GM)", "W0L")
        put(BrandGroup.STELLANTIS_FCA, "Fiat", "ZFA", "NM4")
        put(BrandGroup.STELLANTIS_FCA, "Alfa Romeo", "ZAR")
        put(BrandGroup.STELLANTIS_FCA, "Lancia", "ZLA")
        put(BrandGroup.STELLANTIS_FCA, "Jeep", "1C4", "1J4", "1J8", "ZAC")
        put(BrandGroup.VAG, "Volkswagen", "WVW", "WV1", "WV2", "WVG", "3VW")
        put(BrandGroup.VAG, "Audi", "WAU", "WA1", "TRU")
        put(BrandGroup.VAG, "Škoda", "TMB")
        put(BrandGroup.VAG, "Seat / Cupra", "VSS")
        put(BrandGroup.VAG, "Porsche", "WP0", "WP1")
        put(BrandGroup.BMW, "BMW", "WBA", "WBS", "WBX", "WBY", "5UX")
        put(BrandGroup.BMW, "Mini", "WMW")
        put(BrandGroup.MERCEDES, "Mercedes-Benz", "WDB", "WDD", "WDC", "WDF", "W1K", "W1N", "W1V")
        put(BrandGroup.MERCEDES, "smart", "WME")
        put(BrandGroup.RENAULT_NISSAN, "Renault", "VF1")
        put(BrandGroup.RENAULT_NISSAN, "Dacia", "UU1")
        put(BrandGroup.RENAULT_NISSAN, "Nissan", "JN1", "SJN", "VSK")
        put(BrandGroup.FORD, "Ford", "WF0", "1FA", "1FM", "1FT", "NM0")
        put(BrandGroup.TOYOTA, "Toyota", "JTD", "JTE", "JTM", "JTN", "SB1", "VNK", "NMT")
        put(BrandGroup.TOYOTA, "Lexus", "JTH", "JTJ")
        put(BrandGroup.MAZDA, "Mazda", "JM1", "JMZ", "JM3")
        put(BrandGroup.HONDA, "Honda", "JHM", "SHH", "SHS", "1HG")
        put(BrandGroup.HYUNDAI_KIA, "Hyundai", "KMH", "TMA", "NLH")
        put(BrandGroup.HYUNDAI_KIA, "Kia", "KNA", "KNE", "U5Y")
        put(BrandGroup.VOLVO, "Volvo", "YV1", "YV4", "LVY")
        put(BrandGroup.SUZUKI, "Suzuki", "JS2", "JSA", "TSM")
        put(BrandGroup.MITSUBISHI, "Mitsubishi", "JMB", "JA3", "MMB")
        put(BrandGroup.SUBARU, "Subaru", "JF1", "JF2")
    }

    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"
    private val TRANSLIT = mapOf(
        'A' to 1, 'B' to 2, 'C' to 3, 'D' to 4, 'E' to 5, 'F' to 6, 'G' to 7, 'H' to 8,
        'J' to 1, 'K' to 2, 'L' to 3, 'M' to 4, 'N' to 5, 'P' to 7, 'R' to 9,
        'S' to 2, 'T' to 3, 'U' to 4, 'V' to 5, 'W' to 6, 'X' to 7, 'Y' to 8, 'Z' to 9
    )
    private val WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    fun isValidFormat(vin: String): Boolean =
        vin.length == 17 && vin.all { it in '0'..'9' || it in 'A'..'Z' } && vin.none { it == 'I' || it == 'O' || it == 'Q' }

    /** Az ISO 3779 / FMVSS 115 szerinti ellenőrző karakter (9. pozíció). */
    fun checkDigit(vin: String): Char {
        var sum = 0
        for (i in vin.indices) {
            val c = vin[i]
            val v = if (c.isDigit()) c - '0' else TRANSLIT[c] ?: 0
            sum += v * WEIGHTS[i]
        }
        val r = sum % 11
        return if (r == 10) 'X' else ('0' + r)
    }

    fun decode(vin: String): VinInfo? {
        val v = vin.trim().uppercase()
        if (!isValidFormat(v)) return null
        val wmi = v.substring(0, 3)
        val hit = WMI[wmi]
        val idx = YEAR_CODES.indexOf(v[9])
        val years = if (idx < 0) emptyList() else listOf(1980 + idx, 2010 + idx).filter { it <= 2040 }
        return VinInfo(v, wmi, hit?.first, hit?.second ?: BrandGroup.OTHER, years, checkDigit(v) == v[8])
    }
}
