package hu.obddiag.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import hu.obddiag.app.data.AssetDtcProvider
import hu.obddiag.app.data.DashboardRepository
import hu.obddiag.app.data.DiagnosticsRepository
import hu.obddiag.app.data.DtcRepository
import hu.obddiag.app.data.LiveDataRepository
import hu.obddiag.app.data.LoggingRepository
import hu.obddiag.app.data.SettingsRepository
import hu.obddiag.app.data.VehicleRepository
import hu.obddiag.app.transport.AdapterTarget
import hu.obddiag.app.transport.BleTransport
import hu.obddiag.app.transport.BluetoothClassicTransport
import hu.obddiag.app.transport.WifiTransport
import hu.obddiag.core.connection.ConnectionManager
import hu.obddiag.core.connection.ConnectionState
import hu.obddiag.core.connection.TransportFactory
import hu.obddiag.core.sim.SimulatedElm327
import hu.obddiag.data.ObdDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Kézi függőségkezelés: az alkalmazás teljes élettartamára szóló objektumok. */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: ObdDatabase by lazy { ObdDatabase.create(context) }
    val settings = SettingsRepository(context)
    val connection = ConnectionManager(appScope)
    val live = LiveDataRepository(appScope, connection)
    val dtcInfo = DtcRepository(listOf(AssetDtcProvider(context)))
    val diagnostics by lazy { DiagnosticsRepository(db, connection, settings) }
    val vehicles by lazy { VehicleRepository(db, settings) }
    val logging by lazy { LoggingRepository(appScope, db, live, connection, settings) }
    val dashboards by lazy { DashboardRepository(db) }
    val mfr by lazy { hu.obddiag.app.data.MfrRepository(context, connection) }

    fun transportFactory(target: AdapterTarget): TransportFactory = when (target) {
        is AdapterTarget.Classic -> TransportFactory { BluetoothClassicTransport(context, target.address, target.name) }
        is AdapterTarget.Ble -> TransportFactory { BleTransport(context, target.address, target.name) }
        is AdapterTarget.Wifi -> TransportFactory { WifiTransport(context, target.host, target.port) }
        AdapterTarget.Simulation -> TransportFactory { SimulatedElm327(manufacturerEcus = { mfr.simulationEcus() }) }
    }

    fun connect(target: AdapterTarget) {
        appScope.launch {
            val s = settings.current()
            settings.setLastAdapter(target)
            connection.autoReconnect = s.autoReconnect
            connection.connect(transportFactory(target), s.protocol)
        }
    }
}

class ObdApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val c = container

        c.appScope.launch {
            c.dashboards.ensureDefaults()
            val s = c.settings.current()
            if (s.autoConnectOnStart && s.lastAdapter != null && s.lastAdapter !is AdapterTarget.Simulation) {
                c.connect(s.lastAdapter)
            }
        }
        c.appScope.launch {
            c.settings.settings.collectLatest { s ->
                c.connection.autoReconnect = s.autoReconnect
                c.connection.commLog.trafficEnabled = s.commLogTraffic
                c.live.minCycleIntervalMs = s.pollIntervalMs
            }
        }
        // Ha a gyártói azonosítás már futott, újracsatlakozáskor megismételjük (a jelkészlet a kapcsolathoz kötött).
        c.appScope.launch {
            c.connection.state.collectLatest { st ->
                if (st is ConnectionState.Connected && c.mfr.autoIdentify) c.mfr.identify()
                if (st !is ConnectionState.Connected) { if (c.mfr.state.value !is hu.obddiag.app.data.MfrState.Running) c.mfr.reset() }
            }
        }
        // A sikeresen kiolvasott VIN-t a járműprofilhoz rendeljük.
        c.appScope.launch {
            c.connection.state.collectLatest { st ->
                if (st is ConnectionState.Connected && !st.simulated) {
                    val vin = runCatching { c.connection.client.value?.readVin() }.getOrNull()
                    if (vin != null) runCatching { c.vehicles.attachVin(vin) }
                }
            }
        }

        // Háttérben szünetel az élő lekérdezés, kivéve, ha éppen naplózás fut.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = c.live.setPaused("background", false)
            override fun onStop(owner: LifecycleOwner) {
                if (c.logging.recording.value == null) c.live.setPaused("background", true)
            }
        })
    }
}
