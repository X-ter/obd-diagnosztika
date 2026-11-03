@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package hu.obddiag.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Troubleshoot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import hu.obddiag.app.AppContainer
import hu.obddiag.app.R
import hu.obddiag.app.ui.Labels
import hu.obddiag.app.ui.components.AppCard
import hu.obddiag.app.ui.components.EmptyState
import hu.obddiag.app.ui.components.ScreenScaffold
import hu.obddiag.app.ui.components.SectionTitle
import hu.obddiag.app.ui.components.SkeletonList
import hu.obddiag.app.ui.components.StatusPill
import hu.obddiag.app.ui.nav.appViewModel
import hu.obddiag.app.ui.theme.AppTheme
import hu.obddiag.app.ui.theme.ValueText
import hu.obddiag.core.connection.ConnectionState
import hu.obddiag.core.diag.Mode06Decoder
import hu.obddiag.core.diag.Mode06Result
import hu.obddiag.core.diag.ReadinessInfo
import hu.obddiag.core.pid.StandardPids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class AdvancedViewModel(private val c: AppContainer) : ViewModel() {
    data class EcuInfo(
        val vin: String?,
        val calIds: List<Pair<String, String>>,
        val cvns: List<Pair<String, String>>,
        val names: Map<String, String>,
        val mode09Supported: Set<Int>
    )
    data class Ui(
        val readiness: ReadinessInfo? = null, val readinessLoading: Boolean = false, val readinessLoaded: Boolean = false,
        val ecu: EcuInfo? = null, val ecuLoading: Boolean = false,
        val mode06: List<Mode06Result>? = null, val mode06Loading: Boolean = false
    )

    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()
    val connection = c.connection.state
    fun supportedPids(): Set<Int> = c.connection.client.value?.supportedPids.orEmpty()
    fun multiPid(): Boolean = c.connection.client.value?.multiPidSupported == true

    fun loadReadiness() {
        val client = c.connection.client.value ?: return
        _ui.value = _ui.value.copy(readinessLoading = true)
        viewModelScope.launch {
            val r = runCatching { client.readReadiness() }.getOrNull()
            _ui.value = _ui.value.copy(readiness = r, readinessLoading = false, readinessLoaded = true)
        }
    }

    fun loadEcu() {
        val client = c.connection.client.value ?: return
        _ui.value = _ui.value.copy(ecuLoading = true)
        viewModelScope.launch {
            val info = runCatching {
                val supported = client.discoverSupported(0x09)
                EcuInfo(client.readVin(), client.readCalibrationIds(), client.readCvns(), client.readEcuNames(), supported)
            }.getOrNull()
            _ui.value = _ui.value.copy(ecu = info, ecuLoading = false)
        }
    }

    fun loadMode06() {
        val client = c.connection.client.value ?: return
        _ui.value = _ui.value.copy(mode06Loading = true)
        viewModelScope.launch {
            val r = runCatching { client.readMode06() }.getOrDefault(emptyList())
            _ui.value = _ui.value.copy(mode06 = r, mode06Loading = false)
        }
    }
}

@Composable
fun AdvancedScreen(nav: NavController) {
    val vm = appViewModel { AdvancedViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val connected = conn as? ConnectionState.Connected

    ScreenScaffold(title = stringResource(R.string.advanced_title), onBack = { nav.popBackStack() }) {
        if (connected == null) {
            EmptyState(Icons.Outlined.Troubleshoot, stringResource(R.string.not_connected), stringResource(R.string.advanced_connect_hint))
            return@ScreenScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionTitle(stringResource(R.string.protocol_info)) }
            item {
                AppCard {
                    InfoRow(stringResource(R.string.protocol), connected.vehicle.protocol.displayName)
                    InfoRow(stringResource(R.string.adapter_version), connected.adapter.elmVersion)
                    connected.adapter.stnVersion?.let { InfoRow("STN", it) }
                    connected.adapter.deviceDescription?.let { InfoRow(stringResource(R.string.adapter_description), it) }
                    InfoRow(stringResource(R.string.responding_ecus), connected.vehicle.ecus.joinToString())
                    InfoRow(stringResource(R.string.multi_pid), if (vm.multiPid()) stringResource(R.string.yes) else stringResource(R.string.no))
                }
            }

            item {
                SectionTitle(stringResource(R.string.readiness)) {
                    IconButton(onClick = vm::loadReadiness) { Icon(Icons.Outlined.Refresh, null) }
                }
            }
            item {
                AppCard {
                    when {
                        ui.readinessLoading -> SkeletonList(3)
                        ui.readiness == null && !ui.readinessLoaded -> LoadButton(stringResource(R.string.readiness_load), vm::loadReadiness)
                        ui.readiness == null -> Text(stringResource(R.string.not_supported_by_vehicle))
                        else -> {
                            val r = ui.readiness!!
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(if (r.compressionIgnition) R.string.engine_diesel else R.string.engine_spark), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                StatusPill(if (r.allComplete) stringResource(R.string.readiness_all_ready) else stringResource(R.string.readiness_not_ready),
                                    if (r.allComplete) AppTheme.status.ok else AppTheme.status.warning)
                            }
                            Spacer(Modifier.height(8.dp))
                            r.monitors.forEach { m ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val (icon, color) = when {
                                        !m.available -> Icons.Outlined.RemoveCircleOutline to MaterialTheme.colorScheme.outline
                                        m.complete -> Icons.Outlined.CheckCircle to AppTheme.status.ok
                                        else -> Icons.Outlined.HourglassEmpty to AppTheme.status.warning
                                    }
                                    Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(Labels.monitor(context, m.key), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(stringResource(when { !m.available -> R.string.monitor_na; m.complete -> R.string.monitor_complete; else -> R.string.monitor_incomplete }),
                                        style = MaterialTheme.typography.labelMedium, color = color)
                                }
                            }
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.ecu_info)) }
            item {
                AppCard {
                    val e = ui.ecu
                    when {
                        ui.ecuLoading -> SkeletonList(3)
                        e == null -> LoadButton(stringResource(R.string.ecu_info_load), vm::loadEcu)
                        else -> {
                            InfoRow("VIN", e.vin ?: stringResource(R.string.not_available))
                            e.names.forEach { (ecu, n) -> InfoRow(stringResource(R.string.ecu_name) + " ($ecu)", n) }
                            e.calIds.forEach { (ecu, id) -> InfoRow("CAL ID ($ecu)", id) }
                            e.cvns.forEach { (ecu, v) -> InfoRow("CVN ($ecu)", v) }
                            if (e.calIds.isEmpty() && e.cvns.isEmpty() && e.names.isEmpty()) {
                                Text(stringResource(R.string.ecu_info_partial), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.mode06_title)) }
            item {
                AppCard {
                    val list = ui.mode06
                    when {
                        ui.mode06Loading -> SkeletonList(3)
                        list == null -> {
                            Text(stringResource(R.string.mode06_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            LoadButton(stringResource(R.string.mode06_load), vm::loadMode06)
                        }
                        list.isEmpty() -> Text(stringResource(if (connected.vehicle.protocol.isCan) R.string.mode06_none else R.string.mode06_can_only))
                        else -> list.forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(Labels.mid(context, Mode06Decoder.midKey(r.mid)) + " – MID %02X / TID %02X".format(r.mid, r.tid),
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    val f = { v: Double -> String.format(Locale.getDefault(), "%.3f", v).trimEnd('0').trimEnd(',', '.') }
                                    Text("${f(r.value)} ${r.unit}   [${f(r.min)} … ${f(r.max)}]", style = ValueText.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                StatusPill(stringResource(if (r.passed) R.string.passed else R.string.failed), if (r.passed) AppTheme.status.ok else AppTheme.status.danger)
                            }
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.supported_pids)) }
            item {
                AppCard {
                    val pids = vm.supportedPids().sorted()
                    Text(stringResource(R.string.supported_pids_count, pids.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pids.forEach { p ->
                            val known = StandardPids.pid(p) != null
                            StatusPill("%02X".format(p), if (known) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.supported_pids_legend), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.55f))
    }
}

@Composable
private fun LoadButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

@Suppress("unused")
@Composable
private fun Loading() = CircularProgressIndicator()
