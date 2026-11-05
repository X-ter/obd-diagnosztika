package hu.obddiag.core

import hu.obddiag.core.elm.ElmResult
import hu.obddiag.core.elm.ElmSession
import hu.obddiag.core.mfr.MfrDatabase
import hu.obddiag.core.mfr.MfrDecoder
import hu.obddiag.core.mfr.MfrDetection
import hu.obddiag.core.mfr.MfrEcu
import hu.obddiag.core.mfr.MfrIdentifier
import hu.obddiag.core.mfr.MfrItem
import hu.obddiag.core.mfr.MfrParam
import hu.obddiag.core.mfr.MfrPoller
import hu.obddiag.core.mfr.MfrRegistry
import hu.obddiag.core.mfr.MfrSafetyException
import hu.obddiag.core.mfr.MfrSession
import hu.obddiag.core.mfr.MfrSignals
import hu.obddiag.core.mfr.MfrText
import hu.obddiag.core.mfr.MfrType
import hu.obddiag.core.mfr.MfrValue
import hu.obddiag.core.pid.ObdUnit
import hu.obddiag.core.pid.StandardPids
import hu.obddiag.core.poll.PidHealth
import hu.obddiag.core.sim.SimulatedElm327
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import hu.obddiag.core.mfr.MfrProtocol
import hu.obddiag.core.sim.SimulatedManufacturerEcu
import kotlin.random.Random

class MfrTest {

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ───── Dekódolás (a PSA-RE README példája: 0x305 kormányszög) ─────
    @Test
    fun decodeReadmeExample() {
        val payload = bytes("FF9C1C00A0F000")
        val angle = MfrParam("ANGLE", null, 0, 16, MfrType.SINT, factor = 0.1)
        val speed = MfrParam("SPEED", null, 16, 8, MfrType.UINT, factor = 4.0)
        val dir = MfrParam("DIR", null, 24, 1, MfrType.BOOL)
        assertEquals(-10.0, (MfrDecoder.decode(angle, payload) as MfrValue.Num).value, 1e-9)
        assertEquals(112.0, (MfrDecoder.decode(speed, payload) as MfrValue.Num).value, 1e-9)
        assertEquals(MfrValue.Flag(false), MfrDecoder.decode(dir, payload))
    }

    @Test
    fun decodeBitFieldsEnumsInvalidAndOthers() {
        val p = bytes("A55A7FFF")
        assertEquals(0b010L, MfrDecoder.raw(p, 1, 3)) // 1.6-1.4 (0xA5 = 1010 0101)
        val e = MfrParam("E", null, 8, 8, MfrType.ENUM, values = mapOf(0x5AL to MfrText(mapOf("en" to "On", "hu" to "Be"))))
        assertEquals("Be", (MfrDecoder.decode(e, p) as MfrValue.Label).text.get("hu"))
        val inv = MfrParam("I", null, 16, 16, MfrType.UINT, invalid = listOf(0x7FFF))
        assertEquals(MfrValue.Invalid, MfrDecoder.decode(inv, p))
        val le = MfrParam("L", null, 0, 16, MfrType.UINT, littleEndian = true)
        assertEquals(0x5AA5.toDouble(), (MfrDecoder.decode(le, p) as MfrValue.Num).value, 0.0)
        assertEquals("12AB34", MfrDecoder.bcd(bytes("12AB34")))
        val str = MfrParam("S", null, 0, 32, MfrType.STR)
        assertEquals("ABC", (MfrDecoder.decode(str, "ABC ".toByteArray()) as MfrValue.Text).text)
        val f = MfrParam("F", null, 0, 32, MfrType.FLOAT)
        assertEquals(1.5, (MfrDecoder.decode(f, bytes("3FC00000")) as MfrValue.Num).value, 0.0)
        // túl rövid válasz → érvénytelen, nem kitalált érték
        assertEquals(MfrValue.Invalid, MfrDecoder.decode(MfrParam("X", null, 0, 16, MfrType.UINT), bytes("01")))
    }

    @Test
    fun variantAndAlternativeSelection() {
        val base = listOf(MfrParam("A", null, 0, 8, MfrType.UINT))
        val alt = listOf(MfrParam("A", null, 0, 16, MfrType.UINT))
        val item = MfrItem(0xD400, null, null, "live", 2, listOf("B78_*", "T9_V1"), base, listOf(listOf("B78_V2") to alt))
        assertEquals(base, item.paramsFor("B78_V1"))
        assertEquals(alt, item.paramsFor("B78_V2"))
        assertEquals(base, item.paramsFor("T9_V1"))
        assertNull(item.paramsFor("T9_V2"))
    }

    // ───── Az adatbázis általános invariánsai (minden vezérlőmodellre) ─────
    private val db get() = TestSupport.db

    @Test
    fun realDatabaseLoadsAndEveryParamDecodes() {
        assertTrue(db.ecus.size > 300)
        var n = 0
        for (e in db.ecus) for (it in e.items) {
            val len = it.length ?: 64
            for (params in listOf(it.params) + it.alternatives.map { a -> a.second }) for (p in params) {
                MfrDecoder.decode(p, ByteArray(len) { 0x5A }) // nem dobhat kivételt
                n++
            }
            // biztonság: csak olvasó kérés készülhet belőle
            assertTrue(if (e.protocol == MfrProtocol.UDS) it.id in 0..0xFFFF else it.id in 0..0xFF)
        }
        assertTrue(n > 30_000)
    }

    /** A protokoll a ténylegesen használt azonosító szolgáltatással egyezik (a forrás címkéjétől függetlenül). */
    @Test
    fun protocolIsConsistentWithIdentificationService() {
        for (e in db.ecus) {
            val svc = e.ident.request.take(2)
            if (svc == "21" || svc == "1A") assertTrue("${e.key}: ${e.ident.request}", e.protocol == MfrProtocol.KWP_PSA)
            if (svc == "22") assertTrue("${e.key}: ${e.ident.request}", e.protocol == MfrProtocol.UDS)
        }
    }

    // ───── Generált vezérlők: azonosítás, profilválasztás, olvasás ─────

    /** Sok, véletlenszerűen választott vezérlőmodell (UDS és KWP, különböző csomópontok) – ugyanaz a logika mindre. */
    @Test
    fun manyGeneratedEcusAreIdentifiedExactly() = runBlocking {
        val rnd = Random(20261105)
        val seen = mutableSetOf<String>()
        repeat(24) { i ->
            val sim = TestSupport.pick(rnd) { it.key !in seen }
            seen += sim.ecu.key
            val (c, t) = TestSupport.simClient(listOf(sim))
            val res = MfrIdentifier(db, MfrSession(c)).identify()
            val d = res.identified.singleOrNull()
            assertTrue("#$i ${sim.ecu.key} ${sim.partNumber}: ${res.trace.takeLast(3)}", d != null)
            assertEquals(sim.ecu.key, d!!.ecu.key)
            assertEquals(sim.ecu.variantForPart(sim.partNumber), d.variant)
            assertEquals(sim.partNumber, d.partNumber)
            assertTrue("${sim.ecu.key}: ${res.detections}", res.detections.size == 1)
            assertTrue(TestSupport.nonReadOnly(t.sent).isEmpty())
            // a profil csak az azonosított változatra érvényes azonosítókat adja
            val reg = MfrRegistry(res, db.source)
            assertTrue(reg.signals.all { it.item.paramsFor(d.variant) != null })
            // élő érték csak nem biztonságkritikus vezérlőnél, és csak ha a változatnak van élő azonosítója
            val expectLive = !sim.ecu.safetyCritical && sim.ecu.items.any { it.kind == "live" && it.paramsFor(d.variant) != null && it.paramsFor(d.variant)!!.isNotEmpty() }
            assertTrue(sim.ecu.key, expectLive == reg.signals.any { it.isLive })
        }
        assertTrue(seen.map { it.substringBeforeLast('/') }.toSet().size > 5)
    }

    @Test
    fun liveValuesOfGeneratedEcuAreReadAndInRange() = runBlocking {
        val rnd = Random(7)
        val sim = TestSupport.pick(rnd, variantFilter = { e, v -> TestSupport.hasNumericLive(e, v, 3) }, filter = { !it.safetyCritical })
        val (c, _) = TestSupport.simClient(listOf(sim))
        val session = MfrSession(c)
        val res = MfrIdentifier(db, session).identify()
        val reg = MfrRegistry(res, db.source)
        val live = reg.signals.filter { it.isLive && it.def != null }.take(15)
        assertTrue("${sim.ecu.key}: ${res.detections}", live.isNotEmpty())
        val r = MfrPoller(reg, session).cycle(live.map { it.id }.toSet())
        for (s in live) {
            assertTrue(s.id, r.health[s.id] == PidHealth.OK)
            val v = r.samples.single { it.signalId == s.id }.value / s.unitMultiplier
            s.param.min?.let { assertTrue("${s.id}: $v < $it", v >= it - 1e-6 - Math.abs(s.param.factor)) }
            s.param.max?.let { assertTrue("${s.id}: $v > $it", v <= it + 1e-6 + Math.abs(s.param.factor)) }
        }
        // a gyártói kör után a szabványos OBD-lekérdezés ugyanúgy működik
        val std = c.readPids(listOf(0x0C, 0x0D)) as ElmResult.Data
        assertTrue(std.messages.all { it.ecu == "7E8" })
    }

    @Test
    fun severalEcusOnDifferentAddressesAtOnce() = runBlocking {
        val sims = TestSupport.pickDistinct(Random(99), 4)
        val (c, _) = TestSupport.simClient(sims)
        val res = MfrIdentifier(db, MfrSession(c)).identify()
        assertEquals(sims.map { it.ecu.key }.toSet(), res.identified.map { it.ecu.key }.toSet())
    }

    /** Ismeretlen cikkszám: a vezérlő válaszol, de nincs profil – nem találgatunk, értéket nem mutatunk. */
    @Test
    fun unknownPartIsNotGuessed() = runBlocking {
        val rnd = Random(3)
        repeat(5) {
            val base = TestSupport.pick(rnd)
            val sim = SimulatedManufacturerEcu(base.ecu, base.variant, TestSupport.unknownPart(base.ecu, rnd))
            val (c, _) = TestSupport.simClient(listOf(sim))
            val res = MfrIdentifier(db, MfrSession(c)).identify()
            assertTrue("${base.ecu.key}: ${res.detections} ${res.trace}", res.detections.size == 1)
            val d = res.detections.single()
            assertTrue("${base.ecu.key}: $d", d is MfrDetection.Unknown)
            assertTrue((d as MfrDetection.Unknown).partNumbers.contains(sim.partNumber))
            assertTrue(MfrRegistry(res, db.source).signals.isEmpty())
        }
    }

    /** Több modellhez illő cikkszám: nem választunk. */
    @Test
    fun ambiguousPartIsNotChosen() = runBlocking {
        val withAmb = db.ecus.filter { it.requestId <= 0x7FF && it.requestId !in 0x7DF..0x7EF }.filter { e -> e.ambiguousParts.any { it.length == e.ident.byteLength * 2 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } } }
        assertTrue(withAmb.isNotEmpty())
        val e = withAmb[Random(5).nextInt(withAmb.size)]
        val part = e.ambiguousParts.first { it.length == e.ident.byteLength * 2 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
        val v = e.variants.entries.first { part in it.value }.key
        val (c, _) = TestSupport.simClient(listOf(SimulatedManufacturerEcu(e, v, part)))
        val res = MfrIdentifier(db, MfrSession(c)).identify()
        assertTrue("${e.key} $part: ${res.detections} ${res.trace.filter { !it.contains("nincs válasz") }}", res.identified.isEmpty() && res.detections.single() is MfrDetection.Ambiguous)
        assertTrue(MfrRegistry(res, db.source).signals.isEmpty())
    }

    /** Biztonságkritikus vezérlő (fék, kormány, légzsák…): azonosítható, de élő lekérdezés soha. */
    @Test
    fun safetyCriticalEcusAreNeverPolled() = runBlocking {
        val rnd = Random(11)
        repeat(4) {
            val sim = TestSupport.pick(rnd) { it.safetyCritical }
            val (c, _) = TestSupport.simClient(listOf(sim))
            val res = MfrIdentifier(db, MfrSession(c)).identify()
            val reg = MfrRegistry(res, db.source)
            assertEquals(sim.ecu.key, res.identified.single().ecu.key)
            assertEquals(0, reg.signals.count { it.isLive })
            assertTrue(reg.plan(reg.signals.map { it.id }).isEmpty())
        }
    }

    @Test
    fun safetyWhitelistBlocksWritesAndControls() = runBlocking {
        val sim = TestSupport.pick(Random(1))
        val (c, _) = TestSupport.simClient(listOf(sim))
        val s = MfrSession(c)
        for (bad in listOf("2ED42901", "3101FF00", "2701", "1003", "1102", "2F D4 29 03", "14FFFFFF", "14FF00", "85 02", "3400", "04", "3BA0FF")) {
            var blocked = false
            try { s.exclusive { select(sim.ecu); send(sim.ecu, bad) } } catch (e: MfrSafetyException) { blocked = true }
            assertTrue("nem tiltott: $bad", blocked)
        }
        assertTrue(c.readPids(listOf(0x0C)) is ElmResult.Data)
    }

    @Test
    fun cancelledManufacturerCycleStillRestoresObdAddressing() = runBlocking {
        val sim = TestSupport.pick(Random(21), variantFilter = { e, v -> TestSupport.hasNumericLive(e, v, 5) }, filter = { !it.safetyCritical })
        val (c, _) = TestSupport.simClient(listOf(sim), delayMs = 15)
        val session = MfrSession(c)
        val reg = MfrRegistry(MfrIdentifier(db, session).identify(), db.source)
        val ids = reg.signals.filter { it.isLive }.take(20).map { it.id }.toSet()
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) { MfrPoller(reg, session).cycle(ids) }
        kotlinx.coroutines.delay(200)
        job.cancel(); job.join()
        val r = c.readPids(listOf(0x0C)) as ElmResult.Data
        assertEquals("7E8", r.messages.single().ecu)
    }

    @Test
    fun terminalCommandDuringManufacturerCycleGoesToObdAddress() = runBlocking {
        val sim = TestSupport.pick(Random(22), variantFilter = { e, v -> TestSupport.hasNumericLive(e, v, 5) }, filter = { !it.safetyCritical })
        val (c, _) = TestSupport.simClient(listOf(sim), delayMs = 15)
        val session = MfrSession(c)
        val reg = MfrRegistry(MfrIdentifier(db, session).identify(), db.source)
        val ids = reg.signals.filter { it.isLive }.take(20).map { it.id }.toSet()
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) { MfrPoller(reg, session).cycle(ids) }
        kotlinx.coroutines.delay(150)
        val raw = c.sendRawExclusive("0100")
        job.join()
        assertTrue("válasz: $raw", raw.contains("7E8") && raw.contains("41 00"))
    }

    /** Előre megírt ELM-válaszok (CAN 11 bit, fejlécekkel). */
    private class ScriptTransport(private val answers: Map<String, String>,
                                  private val dyn: ((String, List<String>) -> String?)? = null) : hu.obddiag.core.transport.ObdTransport {
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
            val a = dyn?.invoke(cmd, sent) ?: answers[cmd] ?: if (cmd.startsWith("AT")) "OK" else "NO DATA"
            ch.trySend("$a\r\r>".toByteArray())
        }
    }

    /** ISO-TP keretezés a megadott fejléccel. */
    private fun isoTp(header: String, payload: List<Int>): String {
        fun h(b: List<Int>) = header + " " + b.joinToString(" ") { "%02X".format(it) }
        if (payload.size <= 7) return h(listOf(payload.size) + payload)
        val out = mutableListOf(h(listOf(0x10, payload.size) + payload.take(6)))
        payload.drop(6).chunked(7).forEachIndexed { i, c -> out += h(listOf(0x20 + (i + 1) % 16) + c + List(7 - c.size) { 0 }) }
        return out.joinToString("\r")
    }


    /** Ugyanazon a címen UDS- és KWP-modell is van: a KWP-azonosítás előtt mindig új session nyílik. */
    @Test
    fun kwpSessionIsStartedAfterUdsProbeOnSameAddress() = runBlocking {
        val pairs = db.ecus.filter { it.protocol == MfrProtocol.KWP_PSA && it.isPowertrain }.mapNotNull { k ->
            db.ecus.firstOrNull { it.protocol == MfrProtocol.UDS && it.requestId == k.requestId && it.responseId == k.responseId }?.let { it to k }
        }
        assertTrue(pairs.isNotEmpty())
        val (uds, kwp) = pairs[Random(4).nextInt(pairs.size)]
        val cand = TestSupport.candidates.first { it.first === kwp }
        val sim = SimulatedManufacturerEcu(kwp, cand.second, cand.third)
        var sessionOpen = false
        val rsHex = "%03X".format(kwp.responseId)
        val t = ScriptTransport(emptyMap()) { cmd, _ ->
            when {
                cmd == "0100" -> "7E8 06 41 00 BE 3F A8 13"
                cmd == "ATDPN" -> "A6"
                cmd.startsWith("AT") -> "OK"
                cmd == "81" -> { sessionOpen = true; "$rsHex 03 C1 EF 8F" }
                cmd.startsWith("22") -> "$rsHex 03 7F 22 11"
                // session nélkül a KWP-vezérlő elutasítja az olvasást
                cmd.startsWith("21") || cmd.startsWith("1A") -> if (sessionOpen) sim.answer(cmd.chunked(2).map { it.toInt(16) })?.let { isoTp(rsHex, it) } ?: "NO DATA" else "$rsHex 03 7F ${cmd.take(2)} 80"
                else -> "NO DATA"
            }
        }
        t.connect()
        val c = ObdClient(ElmSession(t)).apply { connectVehicle() }
        val res = MfrIdentifier(MfrDatabase(db.source, db.brand, listOf(uds, kwp), emptyMap()), MfrSession(c)).identify()
        assertEquals(cand.third, res.identified.single().partNumber)
        assertTrue(t.sent.indexOf("81") in 0 until t.sent.indexOf(kwp.ident.request))
    }

    @Test
    fun dtcParsing() {
        val kwp = MfrSession.parseDtcs(listOf(0x57, 0x02, 0x05, 0x62, 0xA0, 0x53, 0x15, 0x20), uds = false)
        assertEquals(listOf("P0562", "C1315"), kwp.map { it.code })
        assertEquals(0xA0, kwp[0].status)
        val uds = MfrSession.parseDtcs(listOf(0x59, 0x02, 0xFF, 0x05, 0x62, 0x16, 0x09, 0xD1, 0x18, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00), uds = true)
        assertEquals(listOf("P0562", "U1118"), uds.map { it.code })
        assertEquals(true, uds[0].activeNow); assertEquals(true, uds[0].confirmed); assertEquals(false, uds[1].activeNow)
    }


    @Test
    fun dtcReadAndClearOnlyThroughExplicitApi() = runBlocking {
        val rnd = Random(31)
        for (proto in MfrProtocol.values()) {
            val sim = TestSupport.pick(rnd) { it.protocol == proto && it.dtcInfo.isNotEmpty() }
            val (c, t) = TestSupport.simClient(listOf(sim))
            val session = MfrSession(c)
            session.exclusive {
                select(sim.ecu)
                try { send(sim.ecu, if (proto == MfrProtocol.UDS) "14FFFFFF" else "14FF00"); org.junit.Assert.fail("a törlés nem mehet át az általános küldésen") }
                catch (_: MfrSafetyException) { }
                val r = readDtcs(sim.ecu) as hu.obddiag.core.mfr.MfrDtcResult.Ok
                val code = r.dtcs.single()
                assertTrue("${sim.ecu.key}: ${code.code}", sim.ecu.dtcInfo[code.raw] != null)
                assertTrue(clearDtcs(sim.ecu) is hu.obddiag.core.mfr.MfrReadResult.Ok)
                assertTrue((readDtcs(sim.ecu) as hu.obddiag.core.mfr.MfrDtcResult.Ok).dtcs.isEmpty())
            }
            assertTrue(t.sent.any { it.startsWith("14") })
        }
    }

    /** Ha a vezérlő a megszokott (rövid) időzítéssel nem válaszol, hosszabb várakozással újrapróbálunk. */
    @Test
    fun slowEcuGetsLongerTimeout() = runBlocking {
        val sim = TestSupport.pick(Random(41), variantFilter = { e, v -> TestSupport.hasNumericLive(e, v) }, filter = { !it.safetyCritical })
        val item = sim.ecu.items.first { it.kind == "live" && it.paramsFor(sim.variant) != null }
        val req = MfrSession(ObdClient(ElmSession(ScriptTransport(emptyMap())))).itemRequest(sim.ecu, item)
        val rsHex = "%03X".format(sim.ecu.responseId)
        val t = ScriptTransport(mapOf("0100" to "7E8 06 41 00 BE 3F A8 13", "ATDPN" to "A6")) { cmd, sent ->
            when {
                cmd == req -> if (sent.lastIndexOf("ATAT0") > sent.lastIndexOf("ATAT1")) isoTp(rsHex, sim.answer(cmd.chunked(2).map { it.toInt(16) })!!) else "NO DATA"
                !cmd.startsWith("AT") && cmd != "0100" -> sim.answer(cmd.chunked(2).map { it.toInt(16) })?.let { isoTp(rsHex, it) }
                else -> null
            }
        }
        t.connect()
        val c = ObdClient(ElmSession(t)).apply { connectVehicle() }
        val session = MfrSession(c)
        val r = session.exclusive { select(sim.ecu); read(sim.ecu, req) }
        assertTrue(r is hu.obddiag.core.mfr.MfrReadResult.Ok)
        assertTrue("ATST${MfrSession.SLOW_TIMEOUT_HEX}" in t.sent)
        assertEquals("ATAT1", t.sent.last { it.startsWith("ATAT") })
    }
}
