package hu.obddiag.core

import hu.obddiag.core.connection.ConnectionManager
import hu.obddiag.core.connection.ConnectionState
import hu.obddiag.core.dtc.DtcStatus
import hu.obddiag.core.elm.ElmErrorKind
import hu.obddiag.core.elm.ElmResult
import hu.obddiag.core.elm.ElmSession
import hu.obddiag.core.elm.ObdProtocol
import hu.obddiag.core.pid.Sample
import hu.obddiag.core.poll.PidHealth
import hu.obddiag.core.poll.PidPoller
import hu.obddiag.core.sim.SimulatedElm327
import hu.obddiag.core.transport.ObdTransport
import hu.obddiag.core.transport.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Előre megírt válaszokat adó transport, darabolt kézbesítéssel. */
private class ScriptedTransport(private val script: Map<String, List<String>>) : ObdTransport {
    override val type = TransportType.WIFI
    override val description = "scripted"
    override var isConnected = false
    private val ch = Channel<ByteArray>(Channel.UNLIMITED)
    override val input: ReceiveChannel<ByteArray> get() = ch
    val written = mutableListOf<String>()
    override suspend fun connect() { isConnected = true }
    override suspend fun close() { isConnected = false }
    override suspend fun write(data: ByteArray) {
        val cmd = String(data).trim()
        written += cmd
        val chunks = script[cmd] ?: return // nincs válasz → időtúllépés
        chunks.forEach { ch.trySend(it.toByteArray()) }
    }
}

class SessionAndConnectionTest {

    @Test
    fun responseSplitAcrossChunks() = runBlocking {
        val t = ScriptedTransport(mapOf("010C" to listOf("7E8 04 41", " 0C 1A F8 \r", "\r>")))
        t.connect()
        val s = ElmSession(t).apply { protocol = ObdProtocol.ISO_15765_4_CAN_11_500 }
        val r = s.obd("010C") as ElmResult.Data
        assertEquals(4, r.messages.single().data.size)
    }

    @Test
    fun timeoutIsReportedAndCounted() = runBlocking {
        val t = ScriptedTransport(mapOf("" to listOf(">")))
        t.connect()
        val s = ElmSession(t)
        val r = s.obd("010C", timeoutMs = 200)
        assertEquals(ElmErrorKind.TIMEOUT, (r as ElmResult.Error).kind)
        assertEquals(1, s.consecutiveTimeouts)
    }

    @Test
    fun lateResponseDoesNotLeakIntoNextCommand() = runBlocking {
        val t = ScriptedTransport(mapOf("010D" to listOf("7E8 03 41 0D 32 \r\r>")))
        t.connect()
        val s = ElmSession(t).apply { protocol = ObdProtocol.ISO_15765_4_CAN_11_500 }
        // Egy korábbi parancs késve érkező válasza a csatornában
        (t.input as Channel<ByteArray>).trySend("7E8 04 41 0C 1A F8 \r\r>".toByteArray())
        val r = s.obd("010D") as ElmResult.Data
        assertEquals(0x0D, r.messages.single().data[1].toInt())
    }

    private suspend fun connectedSimClient(sim: SimulatedElm327 = SimulatedElm327(responseDelayMs = 0)): ObdClient {
        sim.connect()
        val c = ObdClient(ElmSession(sim))
        c.initializeAdapter()
        c.connectVehicle()
        c.discoverSupported(0x01)
        c.probeOptimizations()
        return c
    }

    @Test
    fun simulatorFullFlow() = runBlocking {
        val sim = SimulatedElm327(responseDelayMs = 0)
        val c = connectedSimClient(sim)
        assertEquals("ELM327 v1.5", c.adapterInfo!!.elmVersion)
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, c.vehicleInfo!!.protocol)
        assertEquals(listOf("7E8"), c.vehicleInfo!!.ecus)
        assertTrue(0x0C in c.supportedPids)
        assertTrue(0x42 in c.supportedPids)
        assertTrue(c.multiPidSupported)
        assertNotNull(c.adapterInfo!!.voltage)

        val stored = c.readDtcs(DtcStatus.STORED) as DtcReadResult.Success
        assertEquals(listOf("P0301", "P0420"), stored.codes.map { it.code })
        val pending = c.readDtcs(DtcStatus.PENDING) as DtcReadResult.Success
        assertEquals(listOf("P0171"), pending.codes.map { it.code })

        val readiness = c.readReadiness()!!
        assertTrue(readiness.milOn)

        val ff = c.readFreezeFrame()!!
        assertEquals("P0301", ff.triggerDtc)
        assertTrue(ff.values.any { it.values.containsKey("01.0C") })

        assertEquals(SimulatedElm327.SIM_VIN, c.readVin())
        assertEquals("SIMCAL0000000001", c.readCalibrationIds().single().second)
        assertEquals(3, c.readMode06().size)

        assertEquals(ClearResult.Success, c.clearDtcs())
        assertTrue((c.readDtcs(DtcStatus.STORED) as DtcReadResult.Success).codes.isEmpty())
        assertTrue(!c.readReadiness()!!.milOn)
    }

    @Test
    fun pollerDeliversSamplesAndFlagsUnsupported() = runBlocking {
        val c = connectedSimClient()
        val poller = PidPoller(c)
        poller.setRequestedPids(setOf(0x0C, 0x0D, 0x05, 0x11, 0x0B))
        val job = launch(Dispatchers.Default) { poller.run() }
        val samples: List<Sample> = withTimeout(3_000) { poller.samples.first { list -> list.any { it.signalId == "01.0C" } } }
        assertTrue(samples.first { it.signalId == "01.0C" }.value in 500.0..5000.0)
        job.cancel()

        // Egy nem támogatott PID (a szimulátor 0x0A-t nem ismeri) – a felderítés kiszűri, a poller nem is kérdezi.
        val poller2 = PidPoller(c)
        poller2.setRequestedPids(setOf(0x0A))
        val job2 = launch(Dispatchers.Default) { poller2.run() }
        delay(400)
        job2.cancel()
        assertTrue(poller2.health.value[0x0A] == null)
    }

    @Test
    fun pollerMarksNoDataPidsUnsupported() = runBlocking {
        val sim = SimulatedElm327(responseDelayMs = 0)
        sim.connect()
        val c = ObdClient(ElmSession(sim))
        c.initializeAdapter(); c.connectVehicle()
        // Felderítés nélkül (üres supportedPids) a poller megpróbálja, majd NO DATA után letiltja.
        val poller = PidPoller(c)
        poller.setRequestedPids(setOf(0x0A))
        val job = launch(Dispatchers.Default) { poller.run() }
        withTimeout(3_000) { poller.health.first { it[0x0A] == PidHealth.NOT_SUPPORTED } }
        job.cancel()
    }

    @Test
    fun connectionManagerConnectsAndReconnects() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mgr = ConnectionManager(scope).apply { maxReconnectAttempts = 3 }
        var created = 0
        var lastSim: SimulatedElm327? = null
        mgr.connect({ created++; SimulatedElm327(responseDelayMs = 0).also { lastSim = it } })
        val connected = withTimeout(5_000) { mgr.state.first { it is ConnectionState.Connected } } as ConnectionState.Connected
        assertTrue(connected.simulated)
        assertNotNull(mgr.poller.value)

        lastSim!!.simulateConnectionLoss()
        withTimeout(10_000) { mgr.state.first { it is ConnectionState.Reconnecting } }
        withTimeout(10_000) { mgr.state.first { it is ConnectionState.Connected } }
        assertEquals(2, created)

        mgr.disconnect()
        withTimeout(3_000) { mgr.state.first { it is ConnectionState.Disconnected } }
        scope.cancel()
    }

    @Test
    fun connectionManagerReportsNonElmDevice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mgr = ConnectionManager(scope)
        mgr.connect({ ScriptedTransport(emptyMap()) })
        val failed = withTimeout(30_000) { mgr.state.first { it is ConnectionState.Failed } } as ConnectionState.Failed
        assertTrue(failed.reason.contains("ELM327"))
        scope.cancel()
    }
}
