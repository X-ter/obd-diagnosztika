package hu.obddiag.core.pid

/** Megjelenítési kategória. A fordítást az alkalmazás végzi a kulcs alapján. */
enum class PidCategory { ENGINE, FUEL, AIR, OXYGEN, EXHAUST, TEMPERATURE, ELECTRICAL, VEHICLE, TRANSMISSION, STATUS, DIESEL, CALCULATED, MANUFACTURER }

/** Mértékegység (SI alapú); az átváltásról a [hu.obddiag.core.util.UnitConverter] gondoskodik. */
enum class ObdUnit(val symbol: String) {
    RPM("1/min"), KMH("km/h"), CELSIUS("°C"), PERCENT("%"), KPA("kPa"), PA("Pa"), GRAMS_PER_SEC("g/s"),
    DEGREE("°"), VOLT("V"), MILLIAMP("mA"), SECONDS("s"), MINUTES("min"), KM("km"), RATIO("λ"),
    LITERS_PER_HOUR("L/h"), NEWTON_METER("Nm"), COUNT(""), NONE(""),
    HOURS("h"), PPM("ppm"), MG_PER_M3("mg/m³"), MG_PER_STROKE("mg/löket"), KG_PER_HOUR("kg/h"),
    L_PER_100KM("L/100km"), KILOWATT("kW"), HORSEPOWER("LE"), AFR("AFR"), GEAR(""), CUSTOM("")
}

/**
 * Egy mérhető jel (signal). Egy PID több jelet is tartalmazhat
 * (pl. a 0x14-es lambdaszonda-PID feszültséget és korrekciót is).
 *
 * @param id globálisan egyedi azonosító, pl. "01.0C" vagy "01.14.V"
 * @param key a fordítási kulcs (strings.xml: pid_<key>)
 * @param decode a PID adatbájtjaiból (szolgáltatásbájt és PID-bájt nélkül) számolt érték,
 *               vagy null, ha az érték nem érvényes vagy nem támogatott
 */
class SignalDef(
    val id: String,
    val key: String,
    val unit: ObdUnit,
    val category: PidCategory,
    val min: Double,
    val max: Double,
    val decimals: Int = 0,
    /** Közvetlen megnevezés (gyártói jeleknél); szabványos PID-nél null, ott a [key] alapján fordít az app. */
    val labels: hu.obddiag.core.mfr.MfrText? = null,
    /** Egyedi mértékegység-szöveg, ha [unit] == [ObdUnit.CUSTOM]. */
    val unitText: String? = null,
    val decode: (IntArray) -> Double?
)

/** Állapotjellegű (nem numerikus) PID-értékek: az alkalmazás a kód alapján fordítja szövegre. */
class StateDef(val id: String, val key: String, val category: PidCategory, val decode: (IntArray) -> String?)

/** Szabványos OBD-II PID (SAE J1979 / ISO 15031-5) definíciója. */
class PidDef(
    val mode: Int,
    val pid: Int,
    /** A válasz adatbájtjainak száma (szolgáltatás- és PID-bájt nélkül). */
    val bytes: Int,
    val signals: List<SignalDef> = emptyList(),
    val states: List<StateDef> = emptyList()
) {
    val hex: String get() = "%02X".format(pid)
}

/** Egy jel mért értéke. Az időbélyeg UTC epoch milliszekundum. */
data class Sample(val signalId: String, val value: Double, val timestampMs: Long, val simulated: Boolean = false)
