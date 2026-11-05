package hu.obddiag.core.mfr

/** Többnyelvű szöveg (en kötelező; hu, fr opcionális). */
class MfrText(val texts: Map<String, String>) {
    fun get(lang: String): String = texts[lang] ?: texts["en"] ?: texts.values.firstOrNull() ?: ""
    override fun toString() = get("en")
}

enum class MfrType { UINT, SINT, BOOL, ENUM, BCD, STR, BYTES, FLOAT }

/** Egy paraméter (jel) helye és értelmezése az azonosító válaszában (DBMUXEv szerint). */
class MfrParam(
    val name: String,
    val label: MfrText?,
    /** Kezdő bit a payloadban, a payload 1. bájtjának legfelső bitjétől számolva (0-tól). */
    val start: Int,
    val length: Int,
    val type: MfrType,
    val factor: Double = 1.0,
    val offset: Double = 0.0,
    val min: Double? = null,
    val max: Double? = null,
    val unit: String? = null,
    val invalid: List<Long> = emptyList(),
    val littleEndian: Boolean = false,
    val values: Map<Long, MfrText> = emptyMap()
) {
    val isNumeric: Boolean
        get() = type == MfrType.UINT || type == MfrType.SINT || type == MfrType.FLOAT ||
            (type == MfrType.ENUM && values.isEmpty())
}

/** Egy olvasható azonosító: UDS DID (0x22) vagy KWP LID (0x21). */
class MfrItem(
    val id: Int,
    val name: String?,
    val label: MfrText?,
    /** live | config | identification | counter | other */
    val kind: String,
    val length: Int?,
    val variants: List<String>,
    val params: List<MfrParam>,
    val alternatives: List<Pair<List<String>, List<MfrParam>>>
) {
    /** A szoftverváltozatra érvényes elrendezés, vagy null, ha az azonosító ezen a változaton nem létezik. */
    fun paramsFor(variant: String): List<MfrParam>? {
        alternatives.firstOrNull { (vs, _) -> matchesVariant(vs, variant) }?.let { return it.second }
        return if (variants.isEmpty() || matchesVariant(variants, variant)) params else null
    }

    companion object {
        fun matchesVariant(patterns: List<String>, variant: String) = patterns.any { p ->
            if (p.endsWith("*")) variant.startsWith(p.dropLast(1)) else p == variant
        }
    }
}

enum class MfrProtocol { UDS, KWP_PSA }

class MfrIdentRequest(val request: String, val byteOffset: Int, val byteLength: Int)

/** Egy vezérlőmodell (pl. CMM / MED17_4_4) a címzésével, azonosításával és olvasható azonosítóival. */
class MfrEcu(
    val key: String,
    val node: String,
    val model: String,
    val label: MfrText?,
    val protocol: MfrProtocol,
    val requestId: Int,
    val responseId: Int,
    val ident: MfrIdentRequest,
    /** szoftverváltozat → cikkszámok */
    val variants: Map<String, List<String>>,
    val ambiguousParts: Set<String>,
    /** Az azonosítók és a hibakód-leírások csak akkor töltődnek be, ha a vezérlőt azonosítottuk (memóriatakarékosság). */
    private val dataLoader: () -> MfrEcuData
) {
    constructor(key: String, node: String, model: String, label: MfrText?, protocol: MfrProtocol, requestId: Int, responseId: Int,
                ident: MfrIdentRequest, variants: Map<String, List<String>>, ambiguousParts: Set<String>, items: List<MfrItem>,
                dtcs: Map<Int, MfrDtcInfo> = emptyMap()) :
        this(key, node, model, label, protocol, requestId, responseId, ident, variants, ambiguousParts, { MfrEcuData(items, dtcs) })

    private val data: MfrEcuData by lazy(dataLoader)
    val items: List<MfrItem> get() = data.items
    /** Gyártói hibakódok leírása: nyers 2 bájtos kód → szöveg. */
    val dtcInfo: Map<Int, MfrDtcInfo> get() = data.dtcs

    /**
     * Biztonságkritikus vezérlő (fék, kormány, légzsák, váltó, hajtás…): folyamatos lekérdezés nincs,
     * csak egyszeri, kifejezett felhasználói kérésre olvasunk belőle.
     */
    val safetyCritical: Boolean get() = node.uppercase() in SAFETY_CRITICAL_NODES

    val readService: Int get() = if (protocol == MfrProtocol.UDS) 0x22 else 0x21

    fun variantForPart(part: String): String? =
        if (part in ambiguousParts) null else variants.entries.firstOrNull { part in it.value }?.key

    /** Motor-, futómű-oldali (0x6xx) vagy utastér-oldali (0x7xx) vezérlő – a KWP-session indítása ettől függ. */
    val isPowertrain: Boolean get() = requestId in 0x600..0x6FF
}

class MfrEcuData(val items: List<MfrItem>, val dtcs: Map<Int, MfrDtcInfo>)

/** Egy gyártói hibakód leírása (PSA-RE dtcs.yml). */
class MfrDtcInfo(val code: String?, val text: MfrText)

val SAFETY_CRITICAL_NODES = setOf(
    "ABS", "ASR", "ESP", "DAE", "FSE", "EBB", "SBW", "RBG", "AIRBAG", "BVA", "BVM", "AWD", "ARTIV",
    "AIR_SUSPENSION", "AMVAR", "VCU", "HCU1", "HCU2", "CDPL"
)

class MfrSource(val name: String, val url: String, val commit: String, val license: String)

class MfrDatabase(val source: MfrSource, val brand: String, val ecus: List<MfrEcu>, val nodeLabels: Map<String, MfrText>) {
    /** Címenként (kérés-ID, válasz-ID, protokoll) csoportosítva: ezeken a címeken keresünk vezérlőt. */
    val addresses: Map<Triple<Int, Int, MfrProtocol>, List<MfrEcu>> by lazy {
        ecus.groupBy { Triple(it.requestId, it.responseId, it.protocol) }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun text(o: Any?): MfrText? = (o as? Map<String, Any?>)?.mapValues { it.value.toString() }?.let { MfrText(it) }
        private fun num(o: Any?): Double? = (o as? Number)?.toDouble()

        @Suppress("UNCHECKED_CAST")
        private fun params(o: Any?): List<MfrParam> = (o as List<Map<String, Any?>>).map { p ->
            MfrParam(
                name = p["n"] as String,
                label = text(p["c"]),
                start = (p["s"] as Number).toInt(),
                length = (p["l"] as Number).toInt(),
                type = MfrType.valueOf((p["t"] as String).uppercase()),
                factor = num(p["f"]) ?: 1.0,
                offset = num(p["o"]) ?: 0.0,
                min = num(p["mn"]), max = num(p["mx"]),
                unit = p["u"] as String?,
                invalid = (p["inv"] as List<Any?>?)?.mapNotNull { (it as? Number)?.toLong() }.orEmpty(),
                littleEndian = p["le"] == true,
                values = (p["v"] as Map<String, Any?>?)?.entries?.mapNotNull { (k, v) -> text(v)?.let { k.toLong() to it } }?.toMap().orEmpty()
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun parseItems(o: Any?): List<MfrItem> = (o as List<Map<String, Any?>>).map { it2 ->
            MfrItem(
                id = (it2["id"] as Number).toInt(),
                name = it2["nm"] as String?,
                label = text(it2["d"]),
                kind = it2["k"] as String,
                length = (it2["len"] as Number?)?.toInt(),
                variants = (it2["var"] as List<Any?>?)?.map { v -> v.toString() }.orEmpty(),
                params = params(it2["p"]),
                alternatives = (it2["alt"] as List<Map<String, Any?>>?)?.map { a ->
                    (a["var"] as List<Any?>).map { v -> v.toString() } to params(a["p"])
                }.orEmpty()
            )
        }

        /** 1-es formátum: egyetlen JSON, minden adattal (tesztekhez, kis adatbázisokhoz). */
        @Suppress("UNCHECKED_CAST")
        fun parseDtcs(o: Any?): Map<Int, MfrDtcInfo> = (o as? Map<String, Any?>).orEmpty().mapNotNull { (raw, v) ->
            val m = v as? Map<String, Any?> ?: return@mapNotNull null
            val code = m["c"] as? String
            val t = text(m.filterKeys { it != "c" }) ?: return@mapNotNull null
            raw.toIntOrNull(16)?.let { it to MfrDtcInfo(code, t) }
        }.toMap()

        @Suppress("UNCHECKED_CAST")
        private fun parseChunk(o: Any?): MfrEcuData =
            if (o is Map<*, *>) MfrEcuData(parseItems((o as Map<String, Any?>)["d"]), parseDtcs(o["t"]))
            else MfrEcuData(parseItems(o), emptyMap())

        /** 1-es formátum: egyetlen JSON, minden adattal (tesztekhez, kis adatbázisokhoz). */
        fun fromJson(json: String): MfrDatabase = build(MiniJson.parse(json)) { e, _ -> { MfrEcuData(parseItems(e["data"]), parseDtcs(e["dtcs"])) } }

        /**
         * 2-es formátum (soronként tagolt): az első sor a fejléc (címzés, azonosítás, változatok), a további sorok
         * vezérlőnként az azonosítók. A teljes adatbázis kicsomagolva >20 MB, ezért csak a fejlécet tartjuk memóriában,
         * a vezérlő adatait a forrás újbóli olvasásával ([lineAt] = az n-edik sor, 0-tól), az azonosítás után töltjük be.
         */
        fun fromLines(lineAt: (Int) -> String): MfrDatabase =
            build(MiniJson.parse(lineAt(0))) { e, _ ->
                val chunk = (e["chunk"] as Number).toInt()
                ({ parseChunk(MiniJson.parse(lineAt(chunk + 1))) })
            }

        /** Segédfüggvény: az n-edik sor kiolvasása egy (újra megnyitható) szövegfolyamból, majd lezárás. */
        fun lineOf(open: () -> java.io.Reader, n: Int): String = java.io.BufferedReader(open(), 1 shl 16).use { r ->
            repeat(n) { r.readLine() ?: error("Sérült adatbázis (hiányzó sor: $n)") }
            r.readLine() ?: error("Sérült adatbázis (hiányzó sor: $n)")
        }

        @Suppress("UNCHECKED_CAST")
        private fun build(rootAny: Any?, loader: (Map<String, Any?>, Int) -> () -> MfrEcuData): MfrDatabase {
            val root = rootAny as Map<String, Any?>
            val fmt = (root["format"] as? Number)?.toInt()
            require(fmt == 1 || fmt == 2) { "Ismeretlen adatbázis-formátum" }
            val src = root["source"] as Map<String, Any?>
            val ecus = (root["ecus"] as List<Map<String, Any?>>).mapIndexed { idx, e ->
                val id = e["ident"] as Map<String, Any?>
                MfrEcu(
                    key = e["key"] as String,
                    node = e["node"] as String,
                    model = e["model"] as String,
                    label = text(e["d"]),
                    protocol = if (e["proto"] == "UDS") MfrProtocol.UDS else MfrProtocol.KWP_PSA,
                    requestId = (e["rq"] as Number).toInt(),
                    responseId = (e["rs"] as Number).toInt(),
                    ident = MfrIdentRequest(id["req"] as String, (id["byte"] as Number).toInt(), (id["len"] as Number).toInt()),
                    variants = (e["variants"] as Map<String, Any?>).mapValues { (_, v) -> (v as List<Any?>).map { it.toString() } },
                    ambiguousParts = (e["ambiguousParts"] as List<Any?>?)?.map { it.toString() }?.toSet().orEmpty(),
                    dataLoader = loader(e, idx)
                )
            }
            return MfrDatabase(
                MfrSource(src["name"] as String, src["url"] as String, src["commit"] as String, src["license"] as String),
                root["brand"] as String, ecus,
                (root["nodes"] as Map<String, Any?>?)?.mapNotNull { (k, v) -> text(v)?.let { k to it } }?.toMap().orEmpty()
            )
        }
    }
}
