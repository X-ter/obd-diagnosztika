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
    val items: List<MfrItem>
) {
    val readService: Int get() = if (protocol == MfrProtocol.UDS) 0x22 else 0x21

    fun variantForPart(part: String): String? =
        if (part in ambiguousParts) null else variants.entries.firstOrNull { part in it.value }?.key

    /** Motor-, futómű-oldali (0x6xx) vagy utastér-oldali (0x7xx) vezérlő – a KWP-session indítása ettől függ. */
    val isPowertrain: Boolean get() = requestId in 0x600..0x6FF
}

class MfrSource(val name: String, val url: String, val commit: String, val license: String)

class MfrDatabase(val source: MfrSource, val brand: String, val ecus: List<MfrEcu>, val nodeLabels: Map<String, MfrText>) {
    /** Címenként (kérés-ID, válasz-ID, protokoll) csoportosítva: ezeken a címeken keresünk vezérlőt. */
    val addresses: Map<Triple<Int, Int, MfrProtocol>, List<MfrEcu>> by lazy {
        ecus.groupBy { Triple(it.requestId, it.responseId, it.protocol) }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(json: String): MfrDatabase {
            val root = MiniJson.parse(json) as Map<String, Any?>
            require((root["format"] as? Number)?.toInt() == 1) { "Ismeretlen adatbázis-formátum" }
            fun text(o: Any?): MfrText? = (o as? Map<String, Any?>)?.mapValues { it.value.toString() }?.let { MfrText(it) }
            fun num(o: Any?): Double? = (o as? Number)?.toDouble()
            fun params(o: Any?): List<MfrParam> = (o as List<Map<String, Any?>>).map { p ->
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
            val src = root["source"] as Map<String, Any?>
            val ecus = (root["ecus"] as List<Map<String, Any?>>).map { e ->
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
                    items = (e["data"] as List<Map<String, Any?>>).map { it2 ->
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
