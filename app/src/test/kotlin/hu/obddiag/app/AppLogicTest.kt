package hu.obddiag.app

import hu.obddiag.app.data.CsvExporter
import hu.obddiag.app.data.DtcRepository
import hu.obddiag.app.data.SeriesBuffer
import hu.obddiag.app.data.VinInfo
import hu.obddiag.app.ui.components.formatSignal
import hu.obddiag.core.dtc.Dtc
import hu.obddiag.core.dtc.DtcStatus
import hu.obddiag.core.util.PressureUnit
import hu.obddiag.core.util.UnitPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogicTest {

    @Test
    fun dtcSystemClassification() {
        assertEquals("Gyújtásrendszer vagy gyújtáskimaradás", DtcRepository.systemOf("P0301"))
        assertTrue(DtcRepository.systemOf("P1234").contains("gyártóspecifikus"))
        assertTrue(DtcRepository.systemOf("U0100").contains("szabványos"))
        assertEquals("Sebességváltó", DtcRepository.systemOf("P0700"))
    }

    @Test
    fun unknownCodeGetsGenericInfo() {
        val repo = DtcRepository(emptyList())
        val info = repo.describe(Dtc("P1ABC", DtcStatus.STORED, "7E8"))
        assertTrue(info.isGenericGroup)
        assertTrue(info.manufacturerSpecific)
        assertTrue(info.tips.isNotEmpty())
    }

    @Test
    fun vinDecoding() {
        assertEquals("Volkswagen", VinInfo.manufacturer("WVWZZZ1KZAW000001"))
        assertEquals(2010, VinInfo.modelYear("WVWZZZ1KZAW000001"))
        assertEquals(2021, VinInfo.modelYear("TMBJJ7NE5M0000001"))
    }

    @Test
    fun seriesBufferWrapsAround() {
        val b = SeriesBuffer(3)
        for (i in 1..5) b.add(i.toLong(), i * 10.0)
        val (t, v) = b.snapshot()
        assertEquals(listOf(3L, 4L, 5L), t.toList())
        assertEquals(listOf(30.0, 40.0, 50.0), v.toList())
        assertEquals(listOf(5L), b.snapshot(5).first.toList())
    }

    @Test
    fun csvContainsIsoTimestampAndSimulatedFlag() {
        val csv = CsvExporter.samplesCsv(listOf(Triple(0L, "01.0C", 800.0)), { "Fordulatszám" }, simulated = true, title = "Teszt")
        assertTrue(csv.contains("1970-01-01T00:00:00Z,0,"))
        assertTrue(csv.contains("SZIMULÁLT"))
        assertTrue(csv.contains("01.0C,Fordulatszám,800.0,1/min"))
        assertFalse(csv.contains("\"Fordulatszám\""))
    }

    @Test
    fun formattingUsesUserUnits() {
        val f = formatSignal("01.0B", 100.0, UnitPreferences(pressure = PressureUnit.BAR))
        assertEquals("bar", f.unit)
        assertEquals(1.0, f.value, 1e-9)
        assertEquals("—", formatSignal("01.0B", null, UnitPreferences()).text)
    }
}
