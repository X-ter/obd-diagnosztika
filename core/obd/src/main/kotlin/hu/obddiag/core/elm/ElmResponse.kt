package hu.obddiag.core.elm

/** Egy vezérlőegység (ECU) által küldött, összeállított üzenet. A [data] az OBD szolgáltatásbájttal kezdődik (pl. 0x41). */
class EcuMessage(val ecu: String, val data: ByteArray) {
    override fun toString(): String = "$ecu: " + data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    override fun equals(other: Any?): Boolean = other is EcuMessage && other.ecu == ecu && other.data.contentEquals(data)
    override fun hashCode(): Int = ecu.hashCode() * 31 + data.contentHashCode()
}

/** Negatív válasz (0x7F): a jármű elutasította a kérést. */
data class NegativeResponse(val ecu: String, val service: Int, val nrc: Int) {
    val description: String
        get() = when (nrc) {
            0x10 -> "Általános elutasítás"
            0x11 -> "A szolgáltatás nem támogatott"
            0x12 -> "Az alfunkció nem támogatott"
            0x13 -> "Hibás üzenethossz vagy formátum"
            0x21 -> "Foglalt, ismételje meg később"
            0x22 -> "A feltételek nem megfelelőek"
            0x31 -> "A kérés tartományon kívül esik"
            0x33 -> "Biztonsági hozzáférés megtagadva"
            0x7E, 0x7F -> "Az aktuális munkamenetben nem támogatott"
            else -> "Ismeretlen ok (0x%02X)".format(nrc)
        }
}

enum class ElmErrorKind(val userMessage: String) {
    UNKNOWN_COMMAND("Az adapter nem ismeri a parancsot (?)"),
    UNABLE_TO_CONNECT("Az adapter nem tud kapcsolódni a járműhöz. Ellenőrizd, hogy be van-e kapcsolva a gyújtás."),
    CAN_ERROR("CAN-busz hiba. Lehet, hogy rossz a protokoll, vagy nem csatlakozik megfelelően az adapter."),
    BUS_ERROR("Buszhiba az adapter és a jármű között."),
    BUS_BUSY("A jármű busza foglalt."),
    BUFFER_FULL("Megtelt az adapter puffere. Túl gyors a lekérdezés vagy túl hosszú a válasz."),
    DATA_ERROR("Hibás adat érkezett a járműtől (ellenőrzőösszeg-hiba)."),
    FB_ERROR("Visszacsatolási hiba az adapter vonalmeghajtóján."),
    LV_RESET("Alacsony feszültség miatt újraindult az adapter."),
    STOPPED("A lekérdezést megszakította az adapter."),
    INTERNAL_ERROR("Az adapter belső hibát jelzett."),
    TIMEOUT("Időtúllépés: az adapter nem válaszolt időben."),
    MALFORMED("Értelmezhetetlen válasz érkezett az adaptertől.")
}

sealed class ElmResult {
    abstract val raw: String

    data class Data(
        val messages: List<EcuMessage>,
        val negatives: List<NegativeResponse>,
        override val raw: String
    ) : ElmResult() {
        /** Az első pozitív üzenet a megadott szolgáltatásra (pl. 0x41). */
        fun firstFor(responseService: Int): EcuMessage? =
            messages.firstOrNull { (it.data.firstOrNull()?.toInt() ?: -1) and 0xFF == responseService }
    }

    data class NoData(override val raw: String) : ElmResult()
    data class Error(val kind: ElmErrorKind, override val raw: String) : ElmResult()
    /** AT parancsokra adott szöveges válasz (pl. "ELM327 v1.5", "12.4V", "OK"). */
    data class Text(val text: String, override val raw: String) : ElmResult()
}
