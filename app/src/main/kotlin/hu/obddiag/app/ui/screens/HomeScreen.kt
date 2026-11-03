@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package hu.obddiag.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import hu.obddiag.app.AppContainer
import hu.obddiag.app.R
import hu.obddiag.app.data.AppSettings
import hu.obddiag.app.ui.components.AppCard
import hu.obddiag.app.ui.components.ConnectionIndicator
import hu.obddiag.app.ui.components.SectionTitle
import hu.obddiag.app.ui.components.SimulatedBadge
import hu.obddiag.app.ui.components.StatusPill
import hu.obddiag.app.ui.nav.Routes
import hu.obddiag.app.ui.nav.appViewModel
import hu.obddiag.app.ui.nav.navigateTab
import hu.obddiag.app.ui.theme.AppTheme
import hu.obddiag.app.ui.theme.ValueText
import hu.obddiag.core.connection.ConnectionState
import hu.obddiag.core.diag.ReadinessInfo
import hu.obddiag.data.DiagnosticSessionEntity
import hu.obddiag.data.VehicleEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val state = c.connection.state
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val recent = c.diagnostics.recentSessions(5).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val vehicle: StateFlow<VehicleEntity?> = c.settings.settings
        .flatMapLatest { s -> s.activeVehicleId?.let { id -> c.vehicles.vehicles.combineFirst(id) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _voltage = MutableStateFlow<Double?>(null)
    val voltage: StateFlow<Double?> = _voltage
    private val _readiness = MutableStateFlow<ReadinessInfo?>(null)
    val readiness: StateFlow<ReadinessInfo?> = _readiness
    val lastScan = c.diagnostics.lastScan

    private fun kotlinx.coroutines.flow.Flow<List<VehicleEntity>>.combineFirst(id: Long) =
        combine(this, flowOf(id)) { list, i -> list.firstOrNull { it.id == i } }

    /** A képernyő látható ideje alatt 5 másodpercenként frissíti a feszültséget és a MIL állapotot. */
    suspend fun refreshLoop() {
        while (true) {
            val client = c.connection.client.value
            if (client != null) {
                _voltage.value = runCatching { client.readAdapterVoltage() }.getOrNull() ?: _voltage.value
                if (c.connection.state.value is ConnectionState.Connected) {
                    _readiness.value = runCatching { client.readReadiness() }.getOrNull() ?: _readiness.value
                }
            } else {
                _voltage.value = null
                _readiness.value = null
            }
            delay(5_000)
        }
    }

    fun connectLast(onNoAdapter: () -> Unit) {
        viewModelScope.launch {
            val last = c.settings.current().lastAdapter
            if (last == null) onNoAdapter() else c.connect(last)
        }
    }

    fun disconnect() = c.connection.disconnect()
    fun retryVehicle() = c.connection.retryVehicle()
}

@Composable
fun connectionTitle(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> stringResource(R.string.conn_disconnected)
    is ConnectionState.ConnectingAdapter -> stringResource(R.string.conn_connecting_adapter)
    is ConnectionState.InitializingAdapter -> stringResource(R.string.conn_initializing)
    is ConnectionState.ConnectingVehicle -> stringResource(R.string.conn_connecting_vehicle)
    is ConnectionState.AdapterOnly -> stringResource(R.string.conn_adapter_only)
    is ConnectionState.Connected -> stringResource(R.string.conn_connected)
    is ConnectionState.Reconnecting -> stringResource(R.string.conn_reconnecting, state.attempt, state.maxAttempts)
    is ConnectionState.Failed -> stringResource(R.string.conn_failed)
}

@Composable
fun HomeScreen(nav: NavController) {
    val vm = appViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val vehicle by vm.vehicle.collectAsStateWithLifecycle()
    val voltage by vm.voltage.collectAsStateWithLifecycle()
    val readiness by vm.readiness.collectAsStateWithLifecycle()
    val lastScan by vm.lastScan.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshLoop() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        vehicle?.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.home_no_vehicle),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if ((state as? ConnectionState.Connected)?.simulated == true) SimulatedBadge()
            }
        }
        item { ConnectionCard(state, onConnect = { vm.connectLast { nav.navigate(Routes.ADAPTER) } }, onDisconnect = vm::disconnect,
            onRetry = vm::retryVehicle, onAdapter = { nav.navigate(Routes.ADAPTER) }) }
        item {
            val mil = readiness?.milOn ?: lastScan?.milOn
            val codes = lastScan?.let { it.stored.size + it.pending.size } ?: readiness?.dtcCount
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Icons.Outlined.BatteryChargingFull, stringResource(R.string.home_voltage),
                    voltage?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—", "V",
                    color = when { voltage == null -> MaterialTheme.colorScheme.onSurfaceVariant; voltage!! < 11.8 -> AppTheme.status.danger; voltage!! < 12.3 -> AppTheme.status.warning; else -> AppTheme.status.ok },
                    modifier = Modifier.weight(1f))
                StatTile(Icons.Outlined.ReportProblem, stringResource(R.string.home_dtc_count), codes?.toString() ?: "—", "",
                    color = if ((codes ?: 0) > 0) AppTheme.status.warning else AppTheme.status.ok, modifier = Modifier.weight(1f))
                StatTile(Icons.Outlined.WarningAmber, stringResource(R.string.home_mil),
                    when (mil) { true -> stringResource(R.string.on); false -> stringResource(R.string.off); null -> "—" }, "",
                    color = when (mil) { true -> AppTheme.status.danger; false -> AppTheme.status.ok; null -> MaterialTheme.colorScheme.onSurfaceVariant },
                    modifier = Modifier.weight(1f))
            }
        }
        item { SectionTitle(stringResource(R.string.home_quick_actions)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickAction(Icons.Outlined.ReportProblem, stringResource(R.string.home_read_dtc), Modifier.weight(1f)) { nav.navigateTab(Routes.DTC) }
                QuickAction(Icons.Outlined.ShowChart, stringResource(R.string.tab_live), Modifier.weight(1f)) { nav.navigateTab(Routes.LIVE) }
                QuickAction(Icons.Outlined.Speed, stringResource(R.string.tab_dash), Modifier.weight(1f)) { nav.navigateTab(Routes.DASH) }
            }
        }
        item { SectionTitle(stringResource(R.string.home_recent_sessions)) }
        if (recent.isEmpty()) {
            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.home_no_sessions), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(recent, key = { it.id }) { s -> SessionRow(s) { nav.navigateTab(Routes.DTC) } }
        }
    }
}

@Composable
private fun ConnectionCard(state: ConnectionState, onConnect: () -> Unit, onDisconnect: () -> Unit, onRetry: () -> Unit, onAdapter: () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectionIndicator(state, size = 96.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(connectionTitle(state), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                val detail = when (state) {
                    is ConnectionState.Connected -> state.vehicle.protocol.displayName
                    is ConnectionState.AdapterOnly -> state.reason
                    is ConnectionState.Failed -> listOfNotNull(state.reason, state.detail).joinToString("\n")
                    is ConnectionState.Reconnecting -> state.reason
                    is ConnectionState.ConnectingAdapter -> state.target
                    is ConnectionState.InitializingAdapter -> state.target
                    is ConnectionState.ConnectingVehicle -> state.adapter.elmVersion
                    ConnectionState.Disconnected -> stringResource(R.string.home_connect_hint)
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                val adapter = when (state) {
                    is ConnectionState.Connected -> state.adapter
                    is ConnectionState.AdapterOnly -> state.adapter
                    else -> null
                }
                if (adapter != null) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(stringResource(R.string.home_adapter_ok), AppTheme.status.ok)
                        StatusPill(if (state is ConnectionState.Connected) stringResource(R.string.home_ecu_ok) else stringResource(R.string.home_ecu_missing),
                            if (state is ConnectionState.Connected) AppTheme.status.ok else AppTheme.status.warning)
                        StatusPill(adapter.stnVersion ?: adapter.elmVersion, MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state) {
                is ConnectionState.Connected -> OutlinedButton(onDisconnect, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.disconnect)) }
                is ConnectionState.AdapterOnly -> {
                    Button(onRetry, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.retry_vehicle)) }
                    OutlinedButton(onDisconnect, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.disconnect)) }
                }
                ConnectionState.Disconnected, is ConnectionState.Failed -> {
                    Button(onConnect, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.connect)) }
                    OutlinedButton(onAdapter, Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Bluetooth, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.adapter_choose))
                    }
                }
                else -> OutlinedButton(onDisconnect, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, label: String, value: String, unit: String, color: Color, modifier: Modifier = Modifier) {
    AppCard(modifier, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = ValueText.medium, maxLines = 1)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AppCard(modifier.heightIn(min = 96.dp), onClick = onClick, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SessionRow(s: DiagnosticSessionEntity, onClick: () -> Unit) {
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    AppCard(onClick = onClick, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (s.cleared) Icons.Outlined.History else Icons.Outlined.DirectionsCar, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (s.cleared) stringResource(R.string.session_cleared) else stringResource(R.string.session_scan, s.storedCount + s.pendingCount + s.permanentCount),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                )
                Text(fmt.format(Date(s.timestampMs)) + (s.protocol?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (s.simulated) SimulatedBadge()
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
