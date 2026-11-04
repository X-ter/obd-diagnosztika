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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

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
        assertEquals("932130", MfrDecoder.bcd(bytes("932130")))
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

    // ───── A valódi, konvertált Peugeot-adatbázis ─────
    private fun realDb(): MfrDatabase {
        val f = listOf("app/src/main/assets/mfr/psa_peugeot.json.gz", "../../app/src/main/assets/mfr/psa_peugeot.json.gz",
            "/home/claude/repo/app/src/main/assets/mfr/psa_peugeot.json.gz").map(::File).first { it.exists() }
        return MfrDatabase.fromJson(GZIPInputStream(f.inputStream()).bufferedReader().readText())
    }

    @Test
    fun realDatabaseLoadsAndEveryParamDecodes() {
        val db = realDb()
        assertTrue(db.ecus.size > 300)
        assertEquals("Apache-2.0", db.source.license)
        var n = 0
        for (e in db.ecus) for (it in e.items) {
            val len = it.length ?: 64
            for (params in listOf(it.params) + it.alternatives.map { a -> a.second }) for (p in params) {
                MfrDecoder.decode(p, ByteArray(len) { 0x5A }) // nem dobhat kivételt
                n++
            }
            // biztonság: csak olvasó kérés készülhet belőle
            assertTrue(if (e.protocol == hu.obddiag.core.mfr.MfrProtocol.UDS) it.id in 0..0xFFFF else it.id in 0..0xFF)
        }
        assertTrue(n > 30_000)
    }

    private suspend fun simClient(sim: SimulatedElm327): ObdClient {
        sim.connect()
        return ObdClient(ElmSession(sim)).apply { initializeAdapter(); connectVehicle(); discoverSupported(0x01); probeOptimizations() }
    }

    @Test
    fun safetyWhitelistBlocksWritesAndControls() = runBlocking {
        val sim = SimulatedElm327(responseDelayMs = 0)
        val c = simClient(sim)
        val ecu = realDb().ecus.first { it.key == "AEE2010.ev/CMM/CMM_MG1CS042" }
        val s = MfrSession(c)
        for (bad in listOf("2ED42901", "3101FF00", "2701", "1003", "1102", "2F D4 29 03", "14FFFFFF", "85 02", "3400", "04")) {
            var blocked = false
            try { s.exclusive { select(ecu); send(ecu, bad) } } catch (e: MfrSafetyException) { blocked = true }
            assertTrue("nem tiltott: $bad", blocked)
        }
        // a szabványos címzés a hiba után is visszaállt
        assertTrue(c.readPids(listOf(0x0C)) is ElmResult.Data)
    }

    @Test
    fun identifyReadAndRestoreWithSimulatedPeugeotEngine() = runBlocking {
        val sim = SimulatedElm327(responseDelayMs = 0)
        val c = simClient(sim)
        val db = realDb()
        val session = MfrSession(c)
        val ident = MfrIdentifier(db, session).identify()
        val engine = ident.identified.single { it.ecu.node == "CMM" }
        assertEquals("CMM_MG1CS042", engine.ecu.model)
        assertEquals("932130", engine.partNumber)
        assertTrue(engine.variant.isNotEmpty())
        // a többi címen nincs vezérlő a szimulátorban
        assertEquals(1, ident.detections.size)

        val reg = MfrRegistry(ident, db.source)
        MfrSignals.registry = reg
        val oilId = "mfr.6A8.22D429.RAW_ENGINE_OIL_PRESSURE"
        val oil = reg.signal(oilId)
        assertNotNull(oil)
        assertEquals(ObdUnit.KPA, StandardPids.signal(oilId)!!.unit) // bar → kPa, így a felhasználói egység is érvényes
        val pump = reg.signals.first { it.item.id == 0xD651 }
        val unsupported = reg.signals.first { it.item.id == 0xD405 }

        val poller = MfrPoller(reg, session)
        val r = poller.cycle(setOf(oilId, pump.id, unsupported.id, "mfr.6A8.22D400.ENGINE_SPEED"))
        val oilSample = r.samples.single { it.signalId == oilId }
        assertTrue("olajnyomás: ${oilSample.value} kPa", oilSample.value in 100.0..800.0)
        val rpm = r.samples.single { it.signalId == "mfr.6A8.22D400.ENGINE_SPEED" }.value
        assertTrue(rpm in 500.0..6000.0)
        assertTrue(r.states[pump.id]!!.startsWith("txt:"))
        assertEquals(PidHealth.NOT_SUPPORTED, r.health[unsupported.id])
        assertEquals(PidHealth.OK, r.health[oilId])

        // a gyártói kör után a szabványos OBD-lekérdezés ugyanúgy működik
        val std = c.readPids(listOf(0x0C, 0x0D)) as ElmResult.Data
        assertTrue(std.messages.all { it.ecu == "7E8" })
        MfrSignals.registry = null
    }

    @Test
    fun unknownPartIsNotGuessed() = runBlocking {
        // Egy olyan „vezérlő”, amelynek a cikkszáma nincs az adatbázisban: nem kerülhet be érték.
        val db = realDb()
        val ecu = db.ecus.first { it.key == "AEE2010.ev/CMM/CMM_MG1CS042" }
        val fake = MfrDatabase(db.source, db.brand, listOf(MfrEcu(ecu.key, ecu.node, ecu.model, ecu.label, ecu.protocol,
            ecu.requestId, ecu.responseId, ecu.ident, mapOf("X" to listOf("111111")), emptySet(), ecu.items)), emptyMap())
        val c = simClient(SimulatedElm327(responseDelayMs = 0))
        val ident = MfrIdentifier(fake, MfrSession(c)).identify()
        val d = ident.detections.single()
        assertTrue(d is MfrDetection.Unknown)
        assertTrue(MfrRegistry(ident, db.source).signals.isEmpty())
    }

    /** Előre megírt ELM-válaszok (CAN 11 bit, fejlécekkel). */
    private class ScriptTransport(private val answers: Map<String, String>) : hu.obddiag.core.transport.ObdTransport {
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
            val a = answers[cmd] ?: if (cmd.startsWith("AT")) "OK" else "NO DATA"
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

    @Test
    fun kwpPsaEngineIdentificationAndLidRead() = runBlocking {
        val db = realDb()
        val ecu = db.ecus.first { it.key == "AEE2001.full/CMM/EDC16C3DV4" }
        val only = MfrDatabase(db.source, db.brand, listOf(ecu), emptyMap())
        val ident = listOf(0x61, 0xFE) + List(21) { 0 } + listOf(0x54, 0x98, 0x53)
        // LID C7: fordulatszám (×32,1255), akkumulátor (×0,1004 V) …
        val lid = listOf(0x61, 0xC7, 25, 138) + List(14) { 0 }
        val t = ScriptTransport(mapOf(
            "0100" to "7E8 06 41 00 BE 3F A8 13", "ATDPN" to "A6",
            "81" to "688 03 C1 EF 8F",
            "21FE" to isoTp("688", ident),
            "21C7" to isoTp("688", lid)
        ))
        t.connect()
        val c = ObdClient(ElmSession(t)).apply { connectVehicle() }
        val session = MfrSession(c)
        val res = MfrIdentifier(only, session).identify()
        val d = res.identified.single()
        assertEquals("549853", d.partNumber)
        assertEquals("EDC16C3_A7_V6_1", d.variant)
        // KWP olvasás előtt kommunikációindítás (81) ment ki, írás/biztonsági kérés nem
        assertTrue("81" in t.sent)
        assertTrue(t.sent.none { it.startsWith("27") || it.startsWith("3B") || it.startsWith("2E") || it.startsWith("31") })

        val reg = MfrRegistry(res, db.source)
        val rpmId = "mfr.6A8.21C7.ENGINE_SPEED"
        val r = MfrPoller(reg, session).cycle(setOf(rpmId, "mfr.6A8.21C7.BATTERY_VOLTAGE"))
        assertEquals(25 * 32.1255, r.samples.single { it.signalId == rpmId }.value, 1e-6)
        assertEquals(138 * 0.1004, r.samples.single { it.signalId == "mfr.6A8.21C7.BATTERY_VOLTAGE" }.value, 1e-6)
        // a végén visszaállt a szabványos címzés
        assertEquals("ATSH7DF", t.sent.last { it.startsWith("ATSH") })
    }
}
