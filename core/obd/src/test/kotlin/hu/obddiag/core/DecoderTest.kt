package hu.obddiag.core

import hu.obddiag.core.diag.Mode06Decoder
import hu.obddiag.core.diag.Mode09Decoder
import hu.obddiag.core.diag.ReadinessDecoder
import hu.obddiag.core.dtc.DtcCategory
import hu.obddiag.core.dtc.DtcParser
import hu.obddiag.core.dtc.DtcStatus
import hu.obddiag.core.elm.EcuMessage
import hu.obddiag.core.pid.PidDecoder
import hu.obddiag.core.pid.StandardPids
import hu.obddiag.core.pid.SupportedPidParser
import hu.obddiag.core.util.PressureUnit
import hu.obddiag.core.util.RawCommandGuard
import hu.obddiag.core.util.TemperatureUnit
import hu.obddiag.core.util.UnitConverter
import hu.obddiag.core.util.UnitPreferences
import hu.obddiag.core.pid.ObdUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderTest {

    private fun msg(vararg b: Int, ecu: String = "7E8") = EcuMessage(ecu, b.map { it.toByte() }.toByteArray())
    private fun value(id: String, vararg data: Int): Double? {
        val def = StandardPids.pidOfSignal(id)!!
        return PidDecoder.decodeOne(def, "7E8", data).values[id]
    }

    @Test fun rpm() = assertEquals(1726.0, value("01.0C", 0x1A, 0xF8)!!, 0.001)
    @Test fun speed() = assertEquals(50.0, value("01.0D", 0x32)!!, 0.001)
    @Test fun coolant() = assertEquals(83.0, value("01.05", 0x7B)!!, 0.001)
    @Test fun maf() = assertEquals(3.0, value("01.10", 0x01, 0x2C)!!, 0.001)
    @Test fun throttleFull() = assertEquals(100.0, value("01.11", 0xFF)!!, 0.001)
    @Test fun fuelTrimNegative() = assertEquals(-6.25, value("01.06", 0x78)!!, 0.001)
    @Test fun timingAdvance() = assertEquals(5.0, value("01.0E", 0x8A)!!, 0.001)
    @Test fun moduleVoltage() = assertEquals(13.9, value("01.42", 0x36, 0x4C)!!, 0.001)
    @Test fun catalystTemp() = assertEquals(460.0, value("01.3C", 0x13, 0x88)!!, 0.001)
    @Test fun o2Voltage() = assertEquals(0.45, value("01.15.V", 0x5A, 0x80)!!, 0.001)
    @Test fun o2TrimNotUsed() = assertNull(value("01.15.T", 0x5A, 0xFF))
    @Test fun lambdaWideband() = assertEquals(1.0, value("01.24.L", 0x80, 0x00, 0x00, 0x00)!!, 0.0001)
    @Test fun evapVaporPressureSigned() = assertEquals(-4.0, value("01.32", 0xFF, 0xF0)!!, 0.001)
    @Test fun boostUnsupportedFieldIsNull() = assertNull(value("01.70.AA", 0x01, 0x0C, 0x80, 0x0C, 0x80, 0, 0, 0, 0, 0))
    @Test fun boostSupportedField() = assertEquals(100.0, value("01.70.CA", 0x01, 0x0C, 0x80, 0, 0, 0, 0, 0, 0, 0)!!, 0.001)

    @Test
    fun multiPidResponse() {
        val decoded = PidDecoder.decode(listOf(msg(0x41, 0x0C, 0x1A, 0xF8, 0x0D, 0x32, 0x05, 0x7B)))
        assertEquals(listOf(0x0C, 0x0D, 0x05), decoded.map { it.pid })
        assertEquals(50.0, decoded[1].values["01.0D"]!!, 0.0)
    }

    @Test
    fun freezeFrameSkipsFrameByte() {
        val decoded = PidDecoder.decode(listOf(msg(0x42, 0x0C, 0x00, 0x1A, 0xF8)), responseService = 0x42, freezeFrame = true)
        assertEquals(1726.0, decoded.single().values["01.0C"]!!, 0.001)
    }

    @Test
    fun truncatedPidIgnored() {
        assertTrue(PidDecoder.decode(listOf(msg(0x41, 0x0C, 0x1A))).isEmpty())
    }

    @Test
    fun supportedPidMask() {
        val s = SupportedPidParser.parse(0x00, intArrayOf(0xBE, 0x3F, 0xA8, 0x13))
        assertTrue(0x01 in s); assertFalse(0x02 in s); assertTrue(0x03 in s)
        assertTrue(0x0C in s); assertTrue(0x0D in s); assertTrue(0x20 in s)
        assertTrue(SupportedPidParser.hasNextRange(0x00, s))
        assertEquals(setOf(0x41), SupportedPidParser.parse(0x40, intArrayOf(0x80, 0, 0, 0)))
    }

    @Test
    fun dtcDecode() {
        assertEquals("P0133", DtcParser.decode(0x01, 0x33))
        assertEquals("C0300", DtcParser.decode(0x43, 0x00))
        assertEquals("B1234", DtcParser.decode(0x92, 0x34))
        assertEquals("U0100", DtcParser.decode(0xC1, 0x00))
        assertNull(DtcParser.decode(0, 0))
        assertEquals(0x92 to 0x34, DtcParser.encode("B1234"))
    }

    @Test
    fun dtcCanWithCount() {
        val codes = DtcParser.parse(listOf(msg(0x43, 0x02, 0x03, 0x01, 0x04, 0x20)), DtcStatus.STORED, isCan = true)
        assertEquals(listOf("P0301", "P0420"), codes.map { it.code })
        assertEquals(DtcCategory.POWERTRAIN, codes[0].category)
        assertTrue(codes[0].isGeneric)
    }

    @Test
    fun dtcCanZeroCount() {
        assertTrue(DtcParser.parse(listOf(msg(0x43, 0x00)), DtcStatus.STORED, isCan = true).isEmpty())
    }

    @Test
    fun dtcLegacyWithPadding() {
        val codes = DtcParser.parse(
            listOf(msg(0x43, 0x01, 0x33, 0x00, 0x00, 0x00, 0x00, ecu = "10"),
                   msg(0x43, 0xC1, 0x00, 0x11, 0x23, 0x00, 0x00, ecu = "1A")),
            DtcStatus.STORED, isCan = false)
        assertEquals(listOf("P0133", "U0100", "P1123"), codes.map { it.code })
        assertFalse(codes[2].isGeneric)
        assertEquals("1A", codes[1].ecu)
    }

    @Test
    fun pendingResponseServiceIs47() {
        val codes = DtcParser.parse(listOf(msg(0x47, 0x01, 0x01, 0x71)), DtcStatus.PENDING, isCan = true)
        assertEquals("P0171", codes.single().code)
        assertEquals(DtcStatus.PENDING, codes.single().status)
    }

    @Test
    fun readinessSpark() {
        // MIL be, 2 kód; misfire/fuel/comp elérhető és kész; catalyst, evap, O2, O2 heater, EGR elérhető; evap befejezetlen
        val r = ReadinessDecoder.decode(intArrayOf(0x82, 0x07, 0xE5, 0x04))
        assertTrue(r.milOn); assertEquals(2, r.dtcCount); assertFalse(r.compressionIgnition)
        assertEquals(false, r.monitors.first { it.key == "evap" }.complete)
        assertEquals(true, r.monitors.first { it.key == "catalyst" }.complete)
        assertFalse(r.monitors.first { it.key == "secondary_air" }.available)
        assertFalse(r.allComplete)
    }

    @Test
    fun readinessDiesel() {
        val r = ReadinessDecoder.decode(intArrayOf(0x00, 0x0F, 0x48, 0x40))
        assertTrue(r.compressionIgnition)
        assertTrue(r.monitors.first { it.key == "pm_filter" }.available)
        assertFalse(r.monitors.first { it.key == "pm_filter" }.complete)
        assertTrue(r.monitors.first { it.key == "boost_pressure" }.complete)
    }

    @Test
    fun vinCan() {
        val payload = intArrayOf(0x49, 0x02, 0x01) + "1D4GP00R55B123456".map { it.code }.toIntArray()
        assertEquals("1D4GP00R55B123456", Mode09Decoder.vin(listOf(msg(*payload)), isCan = true))
    }

    @Test
    fun vinLegacy() {
        val vin = "1D4GP00R55B123456"
        val chunks = listOf(0, 0, 0) + vin.map { it.code }
        val msgs = chunks.chunked(4).mapIndexed { i, c -> msg(*(intArrayOf(0x49, 0x02, i + 1) + c.toIntArray()), ecu = "10") }
        assertEquals(5, msgs.size)
        assertEquals(vin, Mode09Decoder.vin(msgs, isCan = false))
    }

    @Test
    fun vinValidation() {
        assertFalse(Mode09Decoder.isPlausibleVin("1D4GP00R55B12345O"))
        assertFalse(Mode09Decoder.isPlausibleVin("SHORT"))
    }

    @Test
    fun mode06() {
        val r = Mode06Decoder.decode(listOf(msg(0x46, 0x01, 0x01, 0x0A, 0x02, 0xE4, 0x02, 0x58, 0xFF, 0xFF))).single()
        assertEquals(0x01, r.mid)
        assertEquals("mV", r.unit)
        assertEquals(740 * 0.122, r.value, 0.001)
        assertTrue(r.passed)
    }

    @Test
    fun mode06SignedFail() {
        val r = Mode06Decoder.decode(listOf(msg(0x46, 0x21, 0x80, 0x81, 0xFF, 0xF6, 0x00, 0x00, 0x00, 0x64))).single()
        assertEquals(-10.0, r.value, 0.0)
        assertFalse(r.passed)
    }

    @Test
    fun units() {
        val p = UnitPreferences(temperature = TemperatureUnit.FAHRENHEIT, pressure = PressureUnit.BAR)
        assertEquals(212.0, UnitConverter.convert(100.0, ObdUnit.CELSIUS, p).value, 0.001)
        assertEquals(1.0, UnitConverter.convert(100.0, ObdUnit.KPA, p).value, 0.001)
        assertEquals("bar", UnitConverter.convert(100.0, ObdUnit.KPA, p).symbol)
    }

    @Test
    fun rawGuard() {
        assertTrue(RawCommandGuard.check("010C") is RawCommandGuard.Verdict.Allowed)
        assertTrue(RawCommandGuard.check("ATRV") is RawCommandGuard.Verdict.Allowed)
        assertTrue(RawCommandGuard.check("22 F1 90") is RawCommandGuard.Verdict.Allowed)
        assertTrue(RawCommandGuard.check("04") is RawCommandGuard.Verdict.Blocked)
        assertTrue(RawCommandGuard.check("2E F1 90 00") is RawCommandGuard.Verdict.Blocked)
        assertTrue(RawCommandGuard.check("3101FF00") is RawCommandGuard.Verdict.Blocked)
        assertTrue(RawCommandGuard.check("ATPP 0C SV 23") is RawCommandGuard.Verdict.Blocked)
        assertTrue(RawCommandGuard.check("AA") is RawCommandGuard.Verdict.Blocked)
        assertTrue(RawCommandGuard.check("hello") is RawCommandGuard.Verdict.Invalid)
    }

    @Test
    fun allSignalIdsUnique() {
        val ids = StandardPids.allSignals.map { it.id } + StandardPids.allStates.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val keys = StandardPids.allSignals.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
