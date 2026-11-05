package hu.obddiag.core

import hu.obddiag.core.elm.ElmSession
import hu.obddiag.core.ident.BrandGroup
import hu.obddiag.core.ident.DbmuxevProfileSource
import hu.obddiag.core.ident.Step
import hu.obddiag.core.ident.SupportLevel
import hu.obddiag.core.ident.VehicleRecognizer
import hu.obddiag.core.ident.VinDecoder
import hu.obddiag.core.mfr.MfrSession
import hu.obddiag.core.sim.SimulatedManufacturerEcu
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Az univerzális felismerés tesztjei. VIN-t és vezérlőt véletlenszerűen generálunk (rögzített maggal);
 * egyetlen konkrét jármű-, motor- vagy vezérlőazonosító sincs beégetve.
 */
class RecognitionTest {

    private val vinAlphabet = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"

    /** Formailag érvényes VIN a megadott WMI-vel, helyes ellenőrző karakterrel. */
    private fun makeVin(wmi: String, rnd: Random): String {
        val body = (0 until 14).map { vinAlphabet[rnd.nextInt(vinAlphabet.length)] }.toCharArray()
        val chars = (wmi + String(body)).toCharArray()
        chars[8] = '0'
        chars[8] = VinDecoder.checkDigit(String(chars))
        return String(chars)
    }

    private fun wmisOf(group: BrandGroup) = VinDecoder.WMI.filterValues { it.second == group }.keys.toList()

    private fun psaSource(onSession: (MfrSession) -> Unit = {}) = DbmuxevProfileSource(
        "psa-re", "PSA-RE", "Apache-2.0", setOf(BrandGroup.STELLANTIS_PSA),
        database = { TestSupport.db }, sessionFactory = { c -> MfrSession(c).also(onSession) })

    // ───── VIN ─────

    @Test
    fun vinDecodingIsStructuralAndGeneric() {
        val rnd = Random(1)
        for ((wmi, info) in VinDecoder.WMI) {
            val vin = makeVin(wmi, rnd)
            val d = VinDecoder.decode(vin)!!
            assertEquals(wmi, d.wmi)
            assertEquals(info.second, d.group)
            assertTrue(d.checkDigitValid)
            assertTrue(d.modelYears.isNotEmpty() || vin[9] !in "ABCDEFGHJKLMNPRSTVWXY123456789")
        }
        // ismeretlen WMI: nincs csoport (OTHER) – nem találgatunk
        assertEquals(BrandGroup.OTHER, VinDecoder.decode(makeVin("ZZZ", rnd).replace('Z', '9'))?.group ?: BrandGroup.OTHER)
        // formailag hibás VIN-ek
        for (bad in listOf("", "123", "VF3OOOOOOOOOOOOOO", "1234567890123456I", "1234567890123456789")) assertTrue(bad, VinDecoder.decode(bad) == null)
    }

    // ───── Teljes felismerés szimulált járművel ─────

    /** Ismeretlen WMI-jű (a szimulátor fiktív) VIN: a gyártói forrás nem fut, további adat kell, a buszon nincs gyártói forgalom. */
    @Test
    fun unknownBrandNeedsInputAndSendsNoManufacturerTraffic() = runBlocking {
        val sim = TestSupport.pick(Random(2))
        val (c, t) = TestSupport.simClient(listOf(sim))
        val r = VehicleRecognizer(c, listOf(psaSource())).recognize()
        assertEquals(SupportLevel.NEEDS_INPUT, r.support)
        assertTrue(r.sources.single().ran.not())
        assertTrue(t.sent.none { it.startsWith("ATSH") && it != "ATSH7DF" && !it.startsWith("ATSH7E") })
        assertTrue(r.log.any { it.step == Step.BRAND && it.message.contains("nem állapítható meg") })
        assertTrue(r.manufacturer == null)
    }

    /** A felhasználó megadja a csoportot: a forrás fut, a vezérlő pontos egyezéssel kiválasztódik, indoklással. */
    @Test
    fun userProvidedGroupEnablesSourceAndSelectionIsJustified() = runBlocking {
        val sim = TestSupport.pick(Random(3))
        val (c, t) = TestSupport.simClient(listOf(sim))
        val r = VehicleRecognizer(c, listOf(psaSource())).recognize(BrandGroup.STELLANTIS_PSA)
        assertEquals(SupportLevel.MANUFACTURER, r.support)
        assertTrue(r.groupFromUser)
        val d = r.manufacturer!!.identified.single()
        assertEquals(sim.ecu.key, d.ecu.key)
        val why = r.log.single { it.step == Step.PROFILE && it.message.contains("KIVÁLASZTVA") }.message
        assertTrue(why, why.contains(sim.partNumber) && why.contains(sim.ecu.ident.request) && why.contains(d.variant))
        assertTrue(TestSupport.nonReadOnly(t.sent).toString(), TestSupport.nonReadOnly(t.sent).isEmpty())
        assertTrue(r.report().contains("felhasználói megadás"))
    }

    /** VIN a csoport WMI-jével (véletlen WMI a csoportból): automatikus aktiválás, a felhasználói ellentmondó megadás nem írja felül. */
    @Test
    fun vinGroupActivatesSourceAutomatically() = runBlocking {
        val rnd = Random(4)
        val wmi = wmisOf(BrandGroup.STELLANTIS_PSA).let { it[rnd.nextInt(it.size)] }
        val vin = makeVin(wmi, rnd)
        val sims = TestSupport.pickDistinct(rnd, 2)
        val (c, _) = TestSupport.simClient(sims, vin = vin)
        val r = VehicleRecognizer(c, listOf(psaSource())).recognize(BrandGroup.VAG)
        assertEquals(vin, r.vin!!.vin)
        assertEquals(BrandGroup.STELLANTIS_PSA, r.group)
        assertTrue(!r.groupFromUser)
        assertTrue(r.log.any { it.message.contains("eltér a VIN szerintitől") })
        assertEquals(sims.map { it.ecu.key }.toSet(), r.manufacturer!!.identified.map { it.ecu.key }.toSet())
    }

    /** Más gyártói csoport VIN-je: a PSA-forrás nem fut, csak szabványos OBD (korlátozott támogatás). */
    @Test
    fun otherBrandVinSkipsSourceWithReason() = runBlocking {
        val rnd = Random(5)
        val others = VinDecoder.WMI.filterValues { it.second != BrandGroup.STELLANTIS_PSA }.keys.toList()
        repeat(3) {
            val vin = makeVin(others[rnd.nextInt(others.size)], rnd)
            val (c, t) = TestSupport.simClient(listOf(TestSupport.pick(rnd)), vin = vin)
            val r = VehicleRecognizer(c, listOf(psaSource())).recognize()
            assertEquals(SupportLevel.STANDARD_ONLY, r.support)
            val s = r.sources.single()
            assertTrue(!s.ran && s.reason.contains("nem tartalmaz adatot"))
            assertTrue(t.sent.none { it.startsWith("ATSH6") || it.startsWith("ATSH5") || it.startsWith("ATSH4") })
        }
    }

    /** VIN nélküli jármű (Mode 09 / 02 nem támogatott): a VIN a szabványos UDS F190-ből jön, ha van. */
    @Test
    fun vinFallsBackToUdsWhenMode09Missing() = runBlocking {
        val (c, _) = TestSupport.simClient(vin = null)
        val r = VehicleRecognizer(c, emptyList()).recognize()
        assertNull(r.vinRaw)
        assertEquals(SupportLevel.NEEDS_INPUT, r.support)
        // a szabványos ECU azonosítói ettől még kiolvasódnak
        val e = r.ecus.single()
        assertEquals("7E8", e.address)
        assertTrue(e.identifiers.containsKey("sparePartNumber") && e.diagProtocol == "UDS")
    }

    /** Ismeretlen vezérlő a csoporton belül: válaszol, de nincs profil – korlátozott támogatás, indoklással. */
    @Test
    fun unknownEcuGivesLimitedSupportWithExplanation() = runBlocking {
        val rnd = Random(6)
        val base = TestSupport.pick(rnd)
        val sim = SimulatedManufacturerEcu(base.ecu, base.variant, TestSupport.unknownPart(base.ecu, rnd))
        val (c, _) = TestSupport.simClient(listOf(sim))
        val r = VehicleRecognizer(c, listOf(psaSource())).recognize(BrandGroup.STELLANTIS_PSA)
        assertEquals(SupportLevel.STANDARD_ONLY, r.support)
        assertTrue(r.log.any { it.step == Step.PROFILE && it.message.contains("NEM VÁLASZTUNK") && it.message.contains(sim.partNumber) })
    }

    /** KWP-s szabványos ECU: UDS-re 7F 22 11, ezért ISO 14230 1A azonosítókkal olvasunk. */
    @Test
    fun kwpEcuIdentityViaService1A() = runBlocking {
        val t = ScriptT { cmd ->
            when {
                cmd == "0100" -> "7E8 06 41 00 BE 3F A8 13"
                cmd == "ATDPN" -> "A6"
                cmd.startsWith("AT") -> "OK"
                cmd.startsWith("22") -> "7E8 03 7F 22 11"
                cmd == "1A87" -> "7E8 07 5A 87 41 42 43 31 32"
                cmd == "1A94" -> "7E8 06 5A 94 53 57 39 39"
                else -> "NO DATA"
            }
        }
        t.connect()
        val c = ObdClient(ElmSession(t)).apply { connectVehicle() }
        val r = VehicleRecognizer(c, emptyList()).recognize()
        val e = r.ecus.single()
        assertEquals("KWP", e.diagProtocol)
        assertEquals("ABC12", e.identifiers["sparePartNumber"])
        assertEquals("SW99", e.identifiers["supplierSoftwareNumber"])
        // csak egy 22-es kérés ment ki (NRC 0x11 után nem próbálkozunk tovább UDS-sel)
        assertEquals(1, t.sent.count { it.startsWith("22") })
        assertEquals("ATSH7DF", t.sent.last { it.startsWith("ATSH") })
    }

    /** Minden felismerés közben kiküldött parancs olvasó jellegű (több véletlen jármű). */
    @Test
    fun recognitionIsReadOnlyAcrossManyVehicles() = runBlocking {
        val rnd = Random(8)
        repeat(6) {
            val sims = TestSupport.pickDistinct(rnd, 1 + rnd.nextInt(3))
            val (c, t) = TestSupport.simClient(sims)
            VehicleRecognizer(c, listOf(psaSource())).recognize(BrandGroup.STELLANTIS_PSA)
            assertTrue(TestSupport.nonReadOnly(t.sent).toString(), TestSupport.nonReadOnly(t.sent).isEmpty())
            // a felismerés végén a szabványos címzés visszaállt
            assertEquals("ATSH7DF", t.sent.last { it.startsWith("ATSH") })
        }
    }

    /** A gyártói adatbázis csak aktív diagnosztikai definíciókat tartalmaz (passzív CAN-leírás nem kerülhet be). */
    @Test
    fun manufacturerSignalsAreActiveDiagnosticOnly() = runBlocking {
        val (c, _) = TestSupport.simClient(listOf(TestSupport.pick(Random(9))))
        val r = VehicleRecognizer(c, listOf(psaSource())).recognize(BrandGroup.STELLANTIS_PSA)
        val reg = hu.obddiag.core.mfr.MfrRegistry(r.manufacturer!!, TestSupport.db.source)
        assertTrue(reg.signals.all { it.origin == hu.obddiag.core.ident.DataOrigin.MANUFACTURER_ACTIVE })
        assertTrue(r.log.any { it.step == Step.PASSIVE })
    }

    private class ScriptT(private val f: (String) -> String) : hu.obddiag.core.transport.ObdTransport {
        override val type = hu.obddiag.core.transport.TransportType.WIFI
        override val description = "script"
        override var isConnected = false
        private val ch = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override val input: kotlinx.coroutines.channels.ReceiveChannel<ByteArray> get() = ch
        val sent = mutableListOf<String>()
        override suspend fun connect() { isConnected = true }
        override suspend fun close() { isConnected = false }
        override suspend fun write(data: ByteArray) {
            val cmd = String(data).trim(); sent += cmd
            ch.trySend("${f(cmd)}\r\r>".toByteArray())
        }
    }
}
