package hu.obddiag.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import hu.obddiag.app.transport.AdapterTarget
import hu.obddiag.app.transport.WifiTransport
import hu.obddiag.app.ui.theme.ThemeMode
import hu.obddiag.core.elm.ObdProtocol
import hu.obddiag.core.util.PressureUnit
import hu.obddiag.core.util.SpeedUnit
import hu.obddiag.core.util.TemperatureUnit
import hu.obddiag.core.util.UnitPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class LiveViewMode { LIST, CARDS, CHART }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val units: UnitPreferences = UnitPreferences(),
    val autoReconnect: Boolean = true,
    val autoConnectOnStart: Boolean = false,
    val lastAdapter: AdapterTarget? = null,
    val wifiHost: String = WifiTransport.DEFAULT_HOST,
    val wifiPort: Int = WifiTransport.DEFAULT_PORT,
    val protocol: ObdProtocol = ObdProtocol.AUTO,
    val activeVehicleId: Long? = null,
    val favorites: Set<String> = setOf("01.0C", "01.0D", "01.05", "01.42"),
    val liveViewMode: LiveViewMode = LiveViewMode.CARDS,
    val keepScreenOn: Boolean = true,
    /** Minimális szünet két teljes lekérdezési kör között (ms); 0 = a lehető leggyorsabb. */
    val pollIntervalMs: Long = 0,
    val commLogTraffic: Boolean = true,
    val activeDashboardId: Long? = null
)

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val temp = stringPreferencesKey("unit_temp")
        val speed = stringPreferencesKey("unit_speed")
        val pressure = stringPreferencesKey("unit_pressure")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val autoConnect = booleanPreferencesKey("auto_connect")
        val adapterType = stringPreferencesKey("adapter_type")
        val adapterAddress = stringPreferencesKey("adapter_address")
        val adapterName = stringPreferencesKey("adapter_name")
        val wifiHost = stringPreferencesKey("wifi_host")
        val wifiPort = intPreferencesKey("wifi_port")
        val protocol = stringPreferencesKey("protocol")
        val vehicle = longPreferencesKey("active_vehicle")
        val favorites = stringSetPreferencesKey("favorites")
        val liveView = stringPreferencesKey("live_view")
        val keepScreen = booleanPreferencesKey("keep_screen_on")
        val pollInterval = longPreferencesKey("poll_interval")
        val commLog = booleanPreferencesKey("comm_log_traffic")
        val dashboard = longPreferencesKey("active_dashboard")
    }

    private inline fun <reified E : Enum<E>> enumOr(v: String?, default: E): E =
        v?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            theme = enumOr(p[K.theme], defaults.theme),
            units = UnitPreferences(
                enumOr(p[K.temp], TemperatureUnit.CELSIUS),
                enumOr(p[K.speed], SpeedUnit.KMH),
                enumOr(p[K.pressure], PressureUnit.KPA)
            ),
            autoReconnect = p[K.autoReconnect] ?: true,
            autoConnectOnStart = p[K.autoConnect] ?: false,
            lastAdapter = when (p[K.adapterType]) {
                "classic" -> p[K.adapterAddress]?.let { AdapterTarget.Classic(it, p[K.adapterName]) }
                "ble" -> p[K.adapterAddress]?.let { AdapterTarget.Ble(it, p[K.adapterName]) }
                "wifi" -> AdapterTarget.Wifi(p[K.wifiHost] ?: WifiTransport.DEFAULT_HOST, p[K.wifiPort] ?: WifiTransport.DEFAULT_PORT)
                "sim" -> AdapterTarget.Simulation
                else -> null
            },
            wifiHost = p[K.wifiHost] ?: WifiTransport.DEFAULT_HOST,
            wifiPort = p[K.wifiPort] ?: WifiTransport.DEFAULT_PORT,
            protocol = p[K.protocol]?.firstOrNull()?.let { ObdProtocol.fromCode(it) } ?: ObdProtocol.AUTO,
            activeVehicleId = p[K.vehicle],
            favorites = p[K.favorites] ?: defaults.favorites,
            liveViewMode = enumOr(p[K.liveView], defaults.liveViewMode),
            keepScreenOn = p[K.keepScreen] ?: true,
            pollIntervalMs = p[K.pollInterval] ?: 0L,
            commLogTraffic = p[K.commLog] ?: true,
            activeDashboardId = p[K.dashboard]
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setTheme(t: ThemeMode) = context.dataStore.edit { it[K.theme] = t.name }
    suspend fun setUnits(u: UnitPreferences) = context.dataStore.edit {
        it[K.temp] = u.temperature.name; it[K.speed] = u.speed.name; it[K.pressure] = u.pressure.name
    }
    suspend fun setAutoReconnect(v: Boolean) = context.dataStore.edit { it[K.autoReconnect] = v }
    suspend fun setAutoConnect(v: Boolean) = context.dataStore.edit { it[K.autoConnect] = v }
    suspend fun setProtocol(p: ObdProtocol) = context.dataStore.edit { it[K.protocol] = p.code.toString() }
    suspend fun setActiveVehicle(id: Long?) = context.dataStore.edit { if (id == null) it.remove(K.vehicle) else it[K.vehicle] = id }
    suspend fun setActiveDashboard(id: Long?) = context.dataStore.edit { if (id == null) it.remove(K.dashboard) else it[K.dashboard] = id }
    suspend fun setLiveViewMode(m: LiveViewMode) = context.dataStore.edit { it[K.liveView] = m.name }
    suspend fun setKeepScreenOn(v: Boolean) = context.dataStore.edit { it[K.keepScreen] = v }
    suspend fun setPollInterval(ms: Long) = context.dataStore.edit { it[K.pollInterval] = ms }
    suspend fun setCommLogTraffic(v: Boolean) = context.dataStore.edit { it[K.commLog] = v }

    suspend fun toggleFavorite(signalId: String) = context.dataStore.edit {
        val cur = it[K.favorites] ?: AppSettings().favorites
        it[K.favorites] = if (signalId in cur) cur - signalId else cur + signalId
    }

    suspend fun setLastAdapter(t: AdapterTarget) = context.dataStore.edit {
        when (t) {
            is AdapterTarget.Classic -> { it[K.adapterType] = "classic"; it[K.adapterAddress] = t.address; t.name?.let { n -> it[K.adapterName] = n } }
            is AdapterTarget.Ble -> { it[K.adapterType] = "ble"; it[K.adapterAddress] = t.address; t.name?.let { n -> it[K.adapterName] = n } }
            is AdapterTarget.Wifi -> { it[K.adapterType] = "wifi"; it[K.wifiHost] = t.host; it[K.wifiPort] = t.port }
            AdapterTarget.Simulation -> it[K.adapterType] = "sim"
        }
    }
}
