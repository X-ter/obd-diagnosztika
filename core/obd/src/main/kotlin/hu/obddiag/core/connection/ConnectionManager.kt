package hu.obddiag.core.connection

import hu.obddiag.core.AdapterInfo
import hu.obddiag.core.ObdClient
import hu.obddiag.core.ObdException
import hu.obddiag.core.VehicleConnectionInfo
import hu.obddiag.core.elm.ElmSession
import hu.obddiag.core.elm.ObdProtocol
import hu.obddiag.core.poll.PidPoller
import hu.obddiag.core.transport.ObdTransport
import hu.obddiag.core.transport.TransportException
import hu.obddiag.core.transport.TransportType
import hu.obddiag.core.util.CommLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A kapcsolat állapotai, a felületen ezek alapján jelenik meg az animált kijelző. */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class ConnectingAdapter(val target: String) : ConnectionState()
    data class InitializingAdapter(val target: String) : ConnectionState()
    data class ConnectingVehicle(val adapter: AdapterInfo) : ConnectionState()
    /** Az adapter él, de a jármű (ECU) nem válaszol – pl. ki van kapcsolva a gyújtás. */
    data class AdapterOnly(val adapter: AdapterInfo, val reason: String) : ConnectionState()
    data class Connected(val adapter: AdapterInfo, val vehicle: VehicleConnectionInfo, val simulated: Boolean) : ConnectionState()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int, val reason: String) : ConnectionState()
    data class Failed(val reason: String, val detail: String?) : ConnectionState()

    val isVehicleConnected: Boolean get() = this is Connected
    val isAdapterConnected: Boolean get() = this is Connected || this is AdapterOnly || this is ConnectingVehicle
}

/** Hogyan hozható létre (újra) a transport – az újracsatlakozáshoz kell. */
fun interface TransportFactory {
    fun create(): ObdTransport
}

/**
 * Életciklus-kezelő: kapcsolódás, inicializálás, járműkapcsolat, figyelés és automatikus újracsatlakozás.
 * Egy példány az alkalmazás teljes élettartama alatt él.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    val commLog: CommLog = CommLog(capacity = 3000)
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _client = MutableStateFlow<ObdClient?>(null)
    /** Az aktív kliens; csak [ConnectionState.Connected] vagy [ConnectionState.AdapterOnly] állapotban nem null. */
    val client: StateFlow<ObdClient?> = _client.asStateFlow()

    private val _poller = MutableStateFlow<PidPoller?>(null)
    val poller: StateFlow<PidPoller?> = _poller.asStateFlow()

    private val _mfr = MutableStateFlow<hu.obddiag.core.mfr.MfrRegistry?>(null)
    /** Az azonosított gyártói (PSA) vezérlők jelkészlete; null, ha nincs azonosítás. */
    val mfr: StateFlow<hu.obddiag.core.mfr.MfrRegistry?> = _mfr.asStateFlow()
    private var mfrSession: hu.obddiag.core.mfr.MfrSession? = null

    /**
     * Gyártói vezérlők azonosítása (csak olvasó kérésekkel). Pontos cikkszám-egyezés esetén a vezérlő
     * értékei elérhetővé válnak az élő adatok között.
     */
    suspend fun identifyManufacturer(db: hu.obddiag.core.mfr.MfrDatabase, onProgress: (Int, Int) -> Unit = { _, _ -> }): hu.obddiag.core.mfr.MfrIdentification {
        val c = _client.value ?: return hu.obddiag.core.mfr.MfrIdentification(emptyList(), 0, true, "Nincs járműkapcsolat")
        val session = hu.obddiag.core.mfr.MfrSession(c, commLog)
        commLog.add(CommLog.Direction.INFO, "Gyártói azonosítás indul (${db.source.name} ${db.source.commit.take(8)})")
        val res = hu.obddiag.core.mfr.MfrIdentifier(db, session).identify(onProgress)
        // az azonosításhoz megnyitott KWP-sessionök szabályos lezárása (ne maradjon vezérlő diagnosztikai módban)
        try { session.exclusive { closeSessions() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        commLog.add(CommLog.Direction.INFO, "Gyártói azonosítás: ${res.identified.size} vezérlő azonosítva, ${res.detections.size} válaszolt")
        // az azonosított vezérlők adatai itt töltődnek be (fájlművelet) – ne a hívó szálán
        val reg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { hu.obddiag.core.mfr.MfrRegistry(res, db.source) }
        mfrSession = session
        hu.obddiag.core.mfr.MfrSignals.registry = reg
        _mfr.value = reg
        _poller.value?.mfrPoller = hu.obddiag.core.mfr.MfrPoller(reg, session, isSimulated)
        return res
    }

    /** Az utolsó gyártói olvasások naplója (a jelentésbe). */
    val mfrReadLog: List<String> get() = mfrSession?.readLog.orEmpty()

    /** Egy azonosított vezérlő gyártói hibakódjainak olvasása (a session a végén lezárul). */
    suspend fun readManufacturerDtcs(ecu: hu.obddiag.core.mfr.MfrEcu): hu.obddiag.core.mfr.MfrDtcResult {
        val session = mfrSession ?: return hu.obddiag.core.mfr.MfrDtcResult.Failed("Nincs azonosított vezérlő")
        return session.exclusive {
            try { select(ecu); readDtcs(ecu) } finally { if (ecu.safetyCritical) closeSessions() }
        }
    }

    /** Gyártói hibakódok törlése – csak a felhasználó megerősítése után hívható. */
    suspend fun clearManufacturerDtcs(ecu: hu.obddiag.core.mfr.MfrEcu): hu.obddiag.core.mfr.MfrReadResult {
        val session = mfrSession ?: return hu.obddiag.core.mfr.MfrReadResult.Failed("Nincs azonosított vezérlő")
        return session.exclusive {
            try { select(ecu, forceSession = true); clearDtcs(ecu) } finally { closeSessions() }
        }
    }

    /** Egyszeri olvasás egy azonosított vezérlő nem élő (konfigurációs, azonosító) adataiból. */
    suspend fun readManufacturerOnce(ecu: hu.obddiag.core.mfr.MfrEcu, items: List<hu.obddiag.core.mfr.MfrItem>): Map<Int, hu.obddiag.core.mfr.MfrReadResult> {
        val session = mfrSession ?: return emptyMap()
        val out = LinkedHashMap<Int, hu.obddiag.core.mfr.MfrReadResult>()
        session.exclusive {
            try {
                select(ecu)
                for (it in items) out[it.id] = read(ecu, itemRequest(ecu, it))
            } finally {
                if (ecu.safetyCritical) closeSessions()
            }
        }
        return out
    }

    private var transport: ObdTransport? = null
    private var factory: TransportFactory? = null
    private var protocol: ObdProtocol = ObdProtocol.AUTO
    private var connectJob: Job? = null
    private var watchdogJob: Job? = null
    private val lifecycle = Mutex()

    @Volatile var autoReconnect: Boolean = true
    var maxReconnectAttempts: Int = 5

    val isSimulated: Boolean get() = transport?.type == TransportType.SIMULATION

    fun connect(factory: TransportFactory, protocol: ObdProtocol = ObdProtocol.AUTO) {
        connectJob?.cancel()
        this.factory = factory
        this.protocol = protocol
        connectJob = scope.launch {
            lifecycle.withLock {
                teardown()
                establish(isReconnect = false)
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        factory = null
        scope.launch {
            lifecycle.withLock {
                teardown()
                _state.value = ConnectionState.Disconnected
                commLog.add(CommLog.Direction.INFO, "Kapcsolat bontva")
            }
        }
    }

    /** Újrapróbálkozás a járműkapcsolattal (pl. gyújtás bekapcsolása után), az adapter újraindítása nélkül. */
    fun retryVehicle() {
        val c = _client.value ?: return
        connectJob?.cancel()
        connectJob = scope.launch {
            lifecycle.withLock { connectVehicle(c, c.adapterInfo ?: return@withLock) }
        }
    }

    private suspend fun establish(isReconnect: Boolean): Boolean {
        val f = factory ?: return false
        val t = f.create()
        transport = t
        if (!isReconnect) _state.value = ConnectionState.ConnectingAdapter(t.description)
        commLog.add(CommLog.Direction.INFO, "Kapcsolódás: ${t.description}")
        try {
            t.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            commLog.add(CommLog.Direction.ERROR, "Kapcsolódási hiba: ${e.message}")
            if (!isReconnect) _state.value = ConnectionState.Failed("Nem sikerült kapcsolódni az adapterhez", e.message)
            safeClose(t)
            return false
        }
        if (!isReconnect) _state.value = ConnectionState.InitializingAdapter(t.description)
        val session = ElmSession(t, commLog)
        val client = ObdClient(session)
        val adapter = try {
            client.initializeAdapter()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            commLog.add(CommLog.Direction.ERROR, "Inicializálási hiba: ${e.message}")
            if (!isReconnect) _state.value = ConnectionState.Failed(
                "Az eszköz nem válaszol ELM327-ként",
                "Ellenőrizd, hogy OBD-II adapterhez csatlakoztál-e. (${e.message})"
            )
            safeClose(t)
            return false
        }
        commLog.add(CommLog.Direction.INFO, "Adapter: ${adapter.elmVersion}${adapter.stnVersion?.let { " / $it" } ?: ""}")
        _client.value = client
        connectVehicle(client, adapter)
        startWatchdog()
        return true
    }

    private suspend fun connectVehicle(client: ObdClient, adapter: AdapterInfo) {
        _state.value = ConnectionState.ConnectingVehicle(adapter)
        try {
            val vehicle = client.connectVehicle(protocol)
            client.discoverSupported(0x01)
            client.probeOptimizations()
            commLog.add(CommLog.Direction.INFO,
                "Jármű: ${vehicle.protocol.displayName}, ECU-k: ${vehicle.ecus.joinToString()}, " +
                    "PID-ek: ${client.supportedPids.size}, multi-PID: ${client.multiPidSupported}")
            val poller = PidPoller(client, simulated = isSimulated)
            _poller.value = poller
            _state.value = ConnectionState.Connected(adapter, vehicle, isSimulated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ObdException) {
            _state.value = ConnectionState.AdapterOnly(adapter, e.message ?: "A jármű nem válaszol")
        } catch (e: TransportException) {
            onConnectionLost(e.message ?: "A kapcsolat megszakadt")
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val t = transport ?: break
                val session = _client.value?.session
                val lost = !t.isConnected || (session != null && session.consecutiveTimeouts >= TIMEOUTS_BEFORE_LOSS)
                if (lost) {
                    onConnectionLost(if (!t.isConnected) "Az adapter kapcsolata megszakadt" else "Az adapter nem válaszol")
                    break
                }
            }
        }
    }

    private fun onConnectionLost(reason: String) {
        commLog.add(CommLog.Direction.ERROR, "Kapcsolatvesztés: $reason")
        connectJob?.cancel()
        connectJob = scope.launch {
            lifecycle.withLock {
                teardown(keepFactory = true)
                if (!autoReconnect || factory == null) {
                    _state.value = ConnectionState.Failed(reason, null)
                    return@withLock
                }
                for (attempt in 1..maxReconnectAttempts) {
                    _state.value = ConnectionState.Reconnecting(attempt, maxReconnectAttempts, reason)
                    delay(backoffMs(attempt))
                    if (establish(isReconnect = true)) return@withLock
                }
                _state.value = ConnectionState.Failed(reason, "Az automatikus újracsatlakozás $maxReconnectAttempts próbálkozás után sem sikerült.")
            }
        }
    }

    private fun backoffMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(15_000L)

    private suspend fun teardown(keepFactory: Boolean = true) {
        watchdogJob?.cancel()
        watchdogJob = null
        _poller.value = null
        _mfr.value = null
        // nyitott gyártói KWP-sessionök szabályos lezárása (legfeljebb 3 s, ha a kapcsolat még él)
        mfrSession?.let { s ->
            try { kotlinx.coroutines.withTimeoutOrNull(3_000) { s.exclusive { closeSessions() } } } catch (_: Exception) { }
        }
        mfrSession = null
        hu.obddiag.core.mfr.MfrSignals.registry = null
        _client.value = null
        transport?.let { safeClose(it) }
        transport = null
        if (!keepFactory) factory = null
    }

    private suspend fun safeClose(t: ObdTransport) {
        try { t.close() } catch (_: Exception) { }
    }

    companion object {
        const val TIMEOUTS_BEFORE_LOSS = 4
    }
}
