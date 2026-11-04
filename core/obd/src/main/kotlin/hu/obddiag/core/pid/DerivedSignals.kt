package hu.obddiag.core.pid

/**
 * Számított (származtatott) értékek: más, ténylegesen mért PID-ekből képzett mennyiségek,
 * pl. teljesítmény, nyomaték, fogyasztás, túltöltési nyomás. Ezek mindig „számított” jelölést kapnak;
 * csak akkor jelennek meg, ha a bemeneti PID-eket a jármű támogatja, és friss mért érték van hozzájuk.
 */
class DerivedDef(
    val signal: SignalDef,
    /** Mind szükséges. */
    val required: List<String>,
    /** Ezek közül legalább egy szükséges (ha nem üres). */
    val anyOf: List<String> = emptyList(),
    /** Ha meg van adva, csak ilyen motortípusnál számolható (true = dízel, false = benzines). */
    val requiresDiesel: Boolean? = null,
    val compute: (inputs: Map<String, Double>, diesel: Boolean?) -> Double?
) {
    val id: String get() = signal.id
    val inputIds: List<String> get() = required + anyOf
}

object DerivedSignals {
    /** A benzin és a dízel sztöchiometrikus keverékaránya és sűrűsége (g/L). */
    private const val AFR_PETROL = 14.7
    private const val AFR_DIESEL = 14.5
    private const val DENSITY_PETROL = 745.0
    private const val DENSITY_DIESEL = 832.0

    private fun d(id: String, key: String, unit: ObdUnit, min: Double, max: Double, dec: Int,
                  required: List<String>, anyOf: List<String> = emptyList(), diesel: Boolean? = null,
                  f: (Map<String, Double>, Boolean?) -> Double?) =
        DerivedDef(SignalDef("calc.$id", key, unit, PidCategory.CALCULATED, min, max, dec) { null }, required, anyOf, diesel, f)

    val all: List<DerivedDef> = listOf(
        // Túltöltési nyomás: szívócsőnyomás − légköri nyomás
        d("boost", "calc_boost", ObdUnit.KPA, -100.0, 300.0, 0, listOf("01.0B", "01.33")) { m, _ ->
            m.getValue("01.0B") - m.getValue("01.33")
        },
        d("trim_b1", "calc_total_trim_b1", ObdUnit.PERCENT, -50.0, 50.0, 1, listOf("01.06", "01.07")) { m, _ ->
            m.getValue("01.06") + m.getValue("01.07")
        },
        d("trim_b2", "calc_total_trim_b2", ObdUnit.PERCENT, -50.0, 50.0, 1, listOf("01.08", "01.09")) { m, _ ->
            m.getValue("01.08") + m.getValue("01.09")
        },
        // Pillanatnyi fogyasztás: a jármű által jelentett érték (0x5E), vagy benzinesnél a légtömegáramból
        d("fuel_lph", "calc_fuel_lph", ObdUnit.LITERS_PER_HOUR, 0.0, 60.0, 2, emptyList(), listOf("01.5E", "01.10", "01.44")) { m, diesel ->
            m["01.5E"] ?: m["01.10"]?.takeIf { diesel == false }?.let { maf ->
                val lambda = m["01.44"]?.takeIf { it in 0.5..2.0 } ?: 1.0
                maf * 3600.0 / (AFR_PETROL * lambda) / DENSITY_PETROL
            }
        },
        d("fuel_l100", "calc_fuel_l100", ObdUnit.L_PER_100KM, 0.0, 30.0, 1, listOf("01.0D"), listOf("01.5E", "01.10", "01.44")) { m, _ ->
            val lph = m["calc.fuel_lph"] ?: return@d null
            val speed = m.getValue("01.0D")
            if (speed < 5.0) null else lph / speed * 100.0
        },
        d("afr", "calc_afr", ObdUnit.AFR, 5.0, 30.0, 2, listOf("01.44")) { m, diesel ->
            m.getValue("01.44") * if (diesel == true) AFR_DIESEL else AFR_PETROL
        },
        d("torque", "calc_torque", ObdUnit.NEWTON_METER, -200.0, 1000.0, 0, listOf("01.62", "01.63")) { m, _ ->
            m.getValue("01.62") / 100.0 * m.getValue("01.63")
        },
        d("power_kw", "calc_power_kw", ObdUnit.KILOWATT, 0.0, 400.0, 1, listOf("01.62", "01.63", "01.0C")) { m, _ ->
            val nm = m.getValue("01.62") / 100.0 * m.getValue("01.63")
            (nm * m.getValue("01.0C") / 9549.0).coerceAtLeast(0.0)
        },
        d("power_hp", "calc_power_hp", ObdUnit.HORSEPOWER, 0.0, 550.0, 0, listOf("01.62", "01.63", "01.0C")) { m, _ ->
            val nm = m.getValue("01.62") / 100.0 * m.getValue("01.63")
            (nm * m.getValue("01.0C") / 9549.0 * 1.35962).coerceAtLeast(0.0)
        },
        // Fogyasztás tömegáramból (0x9D, g/s) literre, ha a jármű nem adja a 0x5E-t
        d("fuel_lph_mass", "calc_fuel_lph_mass", ObdUnit.LITERS_PER_HOUR, 0.0, 60.0, 2, listOf("01.9D.E")) { m, diesel ->
            m.getValue("01.9D.E") * 3600.0 / if (diesel == true) DENSITY_DIESEL else DENSITY_PETROL
        },
    )

    private val byId = all.associateBy { it.id }

    fun def(id: String): DerivedDef? = byId[id]
    fun signal(id: String): SignalDef? = byId[id]?.signal

    /** A számított érték bemenetéhez szükséges PID-ek (a hivatkozott számított értékeket is feloldva). */
    fun requiredPids(id: String): Set<Int> = def(id)?.inputIds.orEmpty().flatMap { input ->
        StandardPids.pidOfSignal(input)?.let { listOf(it.pid) } ?: requiredPids(input).toList()
    }.toSet()

    /** Mely számított értékek elérhetők a támogatott PID-ek alapján. */
    fun available(supported: Set<Int>): List<DerivedDef> = all.filter { def ->
        fun ok(id: String) = StandardPids.pidOfSignal(id)?.pid?.let { it in supported } ?: false
        def.required.all(::ok) && (def.anyOf.isEmpty() || def.anyOf.any(::ok))
    }

    /**
     * A számított értékek kiszámítása a legfrissebb mért értékekből.
     * @param latest jelazonosító → (érték, időbélyeg)
     * @param changed az utolsó csomagban frissült jelek (csak az ezektől függő értékek számolódnak újra)
     * @param maxAgeMs ennél régebbi bemeneti érték nem használható
     */
    fun computeAll(latest: Map<String, Pair<Double, Long>>, changed: Set<String>, nowMs: Long, diesel: Boolean?, maxAgeMs: Long = 5_000): Map<String, Double> {
        val fresh = HashMap<String, Double>()
        latest.forEach { (id, p) -> if (nowMs - p.second <= maxAgeMs) fresh[id] = p.first }
        val touched = changed.toMutableSet()
        val out = LinkedHashMap<String, Double>()
        for (def in all) {
            if (def.inputIds.none { it in touched }) continue
            if (!def.required.all { it in fresh }) continue
            if (def.anyOf.isNotEmpty() && def.anyOf.none { it in fresh }) continue
            if (def.requiresDiesel != null && def.requiresDiesel != diesel) continue
            val v = try { def.compute(fresh, diesel) } catch (_: Exception) { null }
            if (v != null && v.isFinite()) {
                out[def.id] = v
                fresh[def.id] = v
                touched += def.id
            }
        }
        return out
    }
}
