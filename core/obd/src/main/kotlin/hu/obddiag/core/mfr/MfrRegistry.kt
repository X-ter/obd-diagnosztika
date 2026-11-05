package hu.obddiag.core.mfr

import hu.obddiag.core.pid.ObdUnit
import hu.obddiag.core.pid.PidCategory
import hu.obddiag.core.pid.SignalDef

/** Egy azonosított vezérlő egy olvasható paramétere, élő jelként. */
class MfrSignal(
    val id: String,
    val ecu: MfrEcu,
    val item: MfrItem,
    val param: MfrParam,
    /** Csak numerikus paraméternél: a megjelenítési definíció (mértékegység az app egységrendszerében). */
    val def: SignalDef?,
    /** A nyers értéket ennyivel kell szorozni az [ObdUnit] szerinti egységhez (pl. bar → kPa: 100). */
    val unitMultiplier: Double
) {
    /** Folyamatosan lekérdezhető élő érték. Biztonságkritikus vezérlőnél (fék, kormány…) soha. */
    val isLive: Boolean get() = item.kind == "live" && !ecu.safetyCritical
}

/**
 * Az azonosított vezérlők alapján felépített jelkészlet. Csak a pontosan azonosított modell és
 * szoftverváltozat szerint érvényes azonosítók kerülnek bele.
 */
class MfrRegistry(val identification: MfrIdentification, val source: MfrSource) {

    val signals: List<MfrSignal>
    private val byId: Map<String, MfrSignal>

    init {
        val list = mutableListOf<MfrSignal>()
        for (det in identification.identified) {
            val ecu = det.ecu
            val svc = if (ecu.protocol == MfrProtocol.UDS) "22%04X" else "21%02X"
            for (item in ecu.items) {
                val params = item.paramsFor(det.variant) ?: continue
                for (p in params) {
                    val id = "mfr.%03X.%s.%s".format(ecu.requestId, svc.format(item.id), p.name)
                    if (p.isNumeric) {
                        val (unit, mult) = mapUnit(p.unit)
                        val rawMax = if (p.length in 1..52) Math.pow(2.0, p.length.toDouble()) - 1 else 1e9
                        val lo = (p.min ?: if (p.type == MfrType.SINT) -(rawMax / 2) * p.factor + p.offset else p.offset) * mult
                        val hi = (p.max ?: (rawMax * p.factor + p.offset)) * mult
                        val dec = decimalsFor(p.factor * mult)
                        val def = SignalDef(id, "mfr", unit, PidCategory.MANUFACTURER, minOf(lo, hi), maxOf(lo, hi), dec,
                            labels = labelOf(item, p), unitText = if (unit == ObdUnit.CUSTOM) p.unit.orEmpty() else null) { null }
                        list += MfrSignal(id, ecu, item, p, def, mult)
                    } else {
                        list += MfrSignal(id, ecu, item, p, null, 1.0)
                    }
                }
            }
        }
        signals = list
        byId = list.associateBy { it.id }
    }

    fun signal(id: String): MfrSignal? = byId[id]

    /** A kért jelekhez tartozó olvasások vezérlőnként csoportosítva (egy azonosító csak egyszer). */
    fun plan(signalIds: Collection<String>): Map<MfrEcu, List<Pair<MfrItem, List<MfrSignal>>>> =
        signalIds.mapNotNull { byId[it] }
            .filter { !it.ecu.safetyCritical }   // biztonsági okból: fék-, kormány-, légzsák-vezérlőt nem kérdezünk folyamatosan
            .groupBy { it.ecu }
            .mapValues { (_, sigs) -> sigs.groupBy { it.item }.map { (item, s) -> item to s } }

    companion object {
        /** Ha nincs külön paraméterleírás, az azonosító neve és a paraméter neve együtt adja a megnevezést. */
        fun labelOf(item: MfrItem, p: MfrParam): MfrText {
            val pl = p.label
            val il = item.label
            return when {
                pl != null && il != null && item.params.size > 1 && pl.get("en") != il.get("en") -> pl
                pl != null -> pl
                il != null -> il
                else -> MfrText(mapOf("en" to p.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }))
            }
        }

        fun decimalsFor(step: Double): Int {
            val s = kotlin.math.abs(step)
            return when {
                s == 0.0 || s >= 1.0 -> 0
                s >= 0.1 -> 1
                s >= 0.01 -> 2
                else -> 3
            }
        }

        /** PSA-egység → az app egységrendszere (így a mértékegység-beállítás rájuk is érvényes). */
        fun mapUnit(u: String?): Pair<ObdUnit, Double> = when (u?.trim()) {
            "°C" -> ObdUnit.CELSIUS to 1.0
            "km/h" -> ObdUnit.KMH to 1.0
            "rpm" -> ObdUnit.RPM to 1.0
            "%" -> ObdUnit.PERCENT to 1.0
            "V" -> ObdUnit.VOLT to 1.0
            "mA" -> ObdUnit.MILLIAMP to 1.0
            "kPa" -> ObdUnit.KPA to 1.0
            "bar" -> ObdUnit.KPA to 100.0
            "mbar", "hPa" -> ObdUnit.KPA to 0.1
            "km" -> ObdUnit.KM to 1.0
            "Nm" -> ObdUnit.NEWTON_METER to 1.0
            "s" -> ObdUnit.SECONDS to 1.0
            "min" -> ObdUnit.MINUTES to 1.0
            "h" -> ObdUnit.HOURS to 1.0
            "kg/h" -> ObdUnit.KG_PER_HOUR to 1.0
            "L/h" -> ObdUnit.LITERS_PER_HOUR to 1.0
            "L/100km" -> ObdUnit.L_PER_100KM to 1.0
            "ppm" -> ObdUnit.PPM to 1.0
            "kW" -> ObdUnit.KILOWATT to 1.0
            "°" -> ObdUnit.DEGREE to 1.0
            "mg/stroke" -> ObdUnit.MG_PER_STROKE to 1.0
            null, "" -> ObdUnit.NONE to 1.0
            else -> ObdUnit.CUSTOM to 1.0
        }
    }
}

/** Az éppen aktív gyártói jelkészlet (a megjelenítés és a naplózás innen kéri le a jeldefiníciókat). */
object MfrSignals {
    @Volatile var registry: MfrRegistry? = null
    /** A szöveges értékek nyelve (az app állítja be a rendszer nyelve szerint). */
    @Volatile var language: String = "hu"

    fun def(id: String): SignalDef? = if (id.startsWith("mfr.")) registry?.signal(id)?.def else null
}
