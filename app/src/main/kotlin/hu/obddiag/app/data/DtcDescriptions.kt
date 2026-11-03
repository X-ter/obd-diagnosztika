package hu.obddiag.app.data

import android.content.Context
import hu.obddiag.core.dtc.Dtc
import hu.obddiag.core.dtc.DtcCategory
import org.json.JSONObject

/** Egy hibakód emberi olvasásra szánt leírása. */
data class DtcInfo(
    val description: String?,
    /** Igaz, ha a leírás a kódstruktúrából származó általános rendszer-besorolás, nem pontos kódleírás. */
    val isGenericGroup: Boolean,
    val system: String,
    val causes: List<String>,
    val tips: List<String>,
    val manufacturerSpecific: Boolean
)

/** Leírásforrás – gyártóspecifikus adatbázisokkal bővíthető. */
interface DtcDescriptionProvider {
    /** @param make a jármű gyártója (ha ismert), a gyártóspecifikus források ez alapján dönthetnek */
    fun describe(code: String, make: String?): DtcInfo?
}

/**
 * Szabványos (SAE J2012 / ISO 15031-6) P0, P2, B0, C0, U0 kódok magyar leírásai az
 * assets/dtc/hu.json fájlból. A lehetséges okok és teendők csak az ismert, általánosan
 * elfogadott esetekben szerepelnek.
 */
class AssetDtcProvider(private val context: Context, private val asset: String = "dtc/hu.json") : DtcDescriptionProvider {
    private val data: Map<String, Triple<String, List<String>, List<String>>> by lazy { load() }

    private fun load(): Map<String, Triple<String, List<String>, List<String>>> = try {
        val text = context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        buildMap {
            for (code in root.keys()) {
                val o = root.getJSONObject(code)
                fun arr(name: String) = o.optJSONArray(name)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
                put(code.uppercase(), Triple(o.getString("d"), arr("c"), arr("t")))
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    override fun describe(code: String, make: String?): DtcInfo? {
        val (d, c, t) = data[code.uppercase()] ?: return null
        return DtcInfo(d, false, DtcRepository.systemOf(code), c, t, false)
    }
}

class DtcRepository(private val providers: List<DtcDescriptionProvider>) {

    fun describe(dtc: Dtc, make: String? = null): DtcInfo {
        for (p in providers) p.describe(dtc.code, make)?.let { return it }
        return DtcInfo(
            description = null,
            isGenericGroup = true,
            system = systemOf(dtc.code),
            causes = emptyList(),
            tips = GENERIC_TIPS,
            manufacturerSpecific = !dtc.isGeneric
        )
    }

    companion object {
        private val GENERIC_TIPS = listOf(
            "Jegyezd fel a kódot és a freeze frame adatokat a törlés előtt.",
            "Ellenőrizd az érintett rendszer csatlakozóit, vezetékeit és biztosítékait.",
            "A pontos hibakereséshez a gyártói javítási dokumentáció szükséges."
        )

        /** A kód rendszer szerinti besorolása (SAE J2012 struktúra). */
        fun systemOf(code: String): String {
            if (code.length != 5) return "Ismeretlen"
            val cat = DtcCategory.entries.firstOrNull { it.letter == code[0] } ?: return "Ismeretlen"
            val d1 = code[1]
            val d2 = code[2]
            return when (cat) {
                DtcCategory.POWERTRAIN -> when {
                    d1 == '1' || (d1 == '3' && d2 in '0'..'3') -> "Hajtáslánc – gyártóspecifikus"
                    d2 == '0' -> "Üzemanyag- és levegőmérés, kiegészítő emissziós szabályozás"
                    d2 == '1' || d2 == '2' -> "Üzemanyag- és levegőmérés"
                    d2 == '3' -> "Gyújtásrendszer vagy gyújtáskimaradás"
                    d2 == '4' -> "Kiegészítő emissziós rendszerek"
                    d2 == '5' -> "Járműsebesség, alapjárat-szabályozás, kiegészítő bemenetek"
                    d2 == '6' -> "Vezérlőegység és kimenő áramkörök"
                    d2 in '7'..'9' -> "Sebességváltó"
                    d2 == 'A' || d2 == 'B' || d2 == 'C' -> "Hibrid / elektromos hajtás"
                    else -> "Hajtáslánc"
                }
                DtcCategory.CHASSIS -> if (d1 == '0') "Futómű (szabványos)" else "Futómű – gyártóspecifikus"
                DtcCategory.BODY -> if (d1 == '0') "Karosszéria (szabványos)" else "Karosszéria – gyártóspecifikus"
                DtcCategory.NETWORK -> if (d1 == '0') "Hálózati kommunikáció (szabványos)" else "Hálózati kommunikáció – gyártóspecifikus"
            }
        }
    }
}
