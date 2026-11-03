package hu.obddiag.core.util

/**
 * A szakértői terminálban kézzel beírt parancsok ellenőrzése.
 * Az író, vezérlő, programozó és biztonsági hozzáférést kérő szolgáltatásokat letiltja,
 * mert ezek a járművet vagy az adaptert tartósan módosíthatják.
 */
object RawCommandGuard {

    sealed class Verdict {
        data object Allowed : Verdict()
        data class Blocked(val reason: String) : Verdict()
        data class Invalid(val reason: String) : Verdict()
    }

    private val BLOCKED_SERVICES = mapOf(
        0x04 to "A hibakódok törlése csak a Hibakódok képernyőn, megerősítéssel lehetséges.",
        0x08 to "A Mode 08 a fedélzeti rendszerek vezérlésére szolgál.",
        0x10 to "A diagnosztikai munkamenet váltása (UDS 0x10) nem engedélyezett.",
        0x11 to "Az ECU újraindítása (UDS 0x11) nem engedélyezett.",
        0x14 to "A diagnosztikai információk törlése (UDS 0x14) nem engedélyezett.",
        0x27 to "A biztonsági hozzáférés (UDS 0x27) nem engedélyezett.",
        0x28 to "A kommunikáció vezérlése (UDS 0x28) nem engedélyezett.",
        0x2C to "Az adatazonosítók dinamikus definiálása (UDS 0x2C) nem engedélyezett.",
        0x2E to "Adat írása (UDS 0x2E WriteDataByIdentifier) nem engedélyezett.",
        0x2F to "Be- és kimenetek vezérlése (UDS 0x2F) nem engedélyezett.",
        0x31 to "Rutinvezérlés (UDS 0x31) nem engedélyezett.",
        0x34 to "Letöltés/programozás (UDS 0x34) nem engedélyezett.",
        0x35 to "Feltöltés (UDS 0x35) nem engedélyezett.",
        0x36 to "Adatátvitel (UDS 0x36) nem engedélyezett.",
        0x37 to "Átvitel befejezése (UDS 0x37) nem engedélyezett.",
        0x3B to "Helyi azonosító szerinti írás (KWP 0x3B) nem engedélyezett.",
        0x3D to "Memória írása (UDS 0x3D) nem engedélyezett.",
        0x85 to "A DTC-tárolás vezérlése (UDS 0x85) nem engedélyezett.",
        0x87 to "Kapcsolatvezérlés (UDS 0x87) nem engedélyezett.",
    )

    /** OBD 01–03, 05–07, 09, 0A; UDS/KWP olvasás: 0x19 (DTC), 0x1A (ECU-azonosítás), 0x21/0x22 (adatolvasás), 0x3E (tester present). */
    val READ_ONLY_SERVICES = setOf(0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x09, 0x0A, 0x19, 0x1A, 0x21, 0x22, 0x3E)

    private val BLOCKED_AT_PREFIXES = mapOf(
        "PP" to "Az adapter tartós beállításainak (programozható paraméterek) módosítása nem engedélyezett.",
        "BRD" to "Az adapter adatátviteli sebességének módosítása megszakíthatja a kapcsolatot.",
        "BRT" to "Az adapter adatátviteli sebességének módosítása megszakíthatja a kapcsolatot.",
        "MA" to "A busz teljes figyelése (monitor all) elárasztja a kapcsolatot; használd külső eszközzel.",
        "MR" to "A busz figyelése elárasztja a kapcsolatot.",
        "MT" to "A busz figyelése elárasztja a kapcsolatot.",
        "SD" to "Az adapter belső memóriájának írása nem engedélyezett.",
    )

    private val BLOCKED_ST_PREFIXES = listOf("STSBR", "STBR", "STWBR", "STPBR", "STSAVE", "STUNLOCK", "STM")

    fun check(input: String): Verdict {
        val cmd = input.trim().uppercase().replace(" ", "")
        if (cmd.isEmpty()) return Verdict.Invalid("Üres parancs")
        if (cmd.startsWith("AT")) {
            val rest = cmd.substring(2)
            BLOCKED_AT_PREFIXES.entries.firstOrNull { rest.startsWith(it.key) }?.let { return Verdict.Blocked(it.value) }
            return Verdict.Allowed
        }
        if (cmd.startsWith("ST")) {
            if (BLOCKED_ST_PREFIXES.any { cmd.startsWith(it) }) return Verdict.Blocked("Az STN adapter tartós vagy adatfolyam-beállítása nem engedélyezett.")
            return Verdict.Allowed
        }
        if (!cmd.all { it in '0'..'9' || it in 'A'..'F' }) return Verdict.Invalid("Csak hexadecimális karakterek vagy AT/ST parancsok adhatók meg.")
        if (cmd.length < 2) return Verdict.Invalid("Legalább egy bájtot (két hexa karaktert) adj meg.")
        val service = cmd.substring(0, 2).toInt(16)
        BLOCKED_SERVICES[service]?.let { return Verdict.Blocked(it) }
        // Fehérlista: csak az olvasó diagnosztikai szolgáltatások engedélyezettek.
        if (service !in READ_ONLY_SERVICES) {
            return Verdict.Blocked("A 0x%02X szolgáltatás nem szerepel az engedélyezett, csak olvasó műveletek között.".format(service))
        }
        return Verdict.Allowed
    }
}
