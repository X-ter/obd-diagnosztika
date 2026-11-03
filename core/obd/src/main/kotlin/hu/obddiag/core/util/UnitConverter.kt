package hu.obddiag.core.util

import hu.obddiag.core.pid.ObdUnit

enum class TemperatureUnit { CELSIUS, FAHRENHEIT }
enum class SpeedUnit { KMH, MPH }
enum class PressureUnit { KPA, BAR, PSI }

data class UnitPreferences(
    val temperature: TemperatureUnit = TemperatureUnit.CELSIUS,
    val speed: SpeedUnit = SpeedUnit.KMH,
    val pressure: PressureUnit = PressureUnit.KPA
)

/** Mértékegység-átváltás a megjelenítéshez. A mentett adatok mindig SI (alap) egységben maradnak. */
object UnitConverter {
    data class Converted(val value: Double, val symbol: String)

    fun convert(value: Double, unit: ObdUnit, prefs: UnitPreferences): Converted = when (unit) {
        ObdUnit.CELSIUS -> if (prefs.temperature == TemperatureUnit.FAHRENHEIT) Converted(value * 9 / 5 + 32, "°F") else Converted(value, "°C")
        ObdUnit.KMH -> if (prefs.speed == SpeedUnit.MPH) Converted(value / 1.609344, "mph") else Converted(value, "km/h")
        ObdUnit.KM -> if (prefs.speed == SpeedUnit.MPH) Converted(value / 1.609344, "mi") else Converted(value, "km")
        ObdUnit.KPA -> when (prefs.pressure) {
            PressureUnit.KPA -> Converted(value, "kPa")
            PressureUnit.BAR -> Converted(value / 100.0, "bar")
            PressureUnit.PSI -> Converted(value * 0.145037738, "psi")
        }
        else -> Converted(value, unit.symbol)
    }

    /** Hány tizedesjegy legyen az átváltott érték mellett (bar esetén több kell). */
    fun decimals(unit: ObdUnit, base: Int, prefs: UnitPreferences): Int = when {
        unit == ObdUnit.KPA && prefs.pressure == PressureUnit.BAR -> maxOf(base, 2)
        unit == ObdUnit.KPA && prefs.pressure == PressureUnit.PSI -> maxOf(base, 1)
        else -> base
    }
}
