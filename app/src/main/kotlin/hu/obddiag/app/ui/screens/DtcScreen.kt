@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package hu.obddiag.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import hu.obddiag.app.data.CsvExporter
import hu.obddiag.app.data.DtcInfo
import hu.obddiag.app.data.DtcScan
import hu.obddiag.app.data.NotConnectedException
import hu.obddiag.app.ui.Labels
import hu.obddiag.app.ui.components.AppCard
import hu.obddiag.app.ui.components.EmptyState
import hu.obddiag.app.ui.components.ErrorBanner
import hu.obddiag.app.ui.components.ScreenScaffold
import hu.obddiag.app.ui.components.SectionTitle
import hu.obddiag.app.ui.components.SimulatedBadge
import hu.obddiag.app.ui.components.SkeletonList
import hu.obddiag.app.ui.components.StatusPill
import hu.obddiag.app.ui.components.formatSignal
import hu.obddiag.app.ui.nav.Routes
import hu.obddiag.app.ui.theme.AppTheme
import hu.obddiag.app.ui.nav.appViewModel
import hu.obddiag.app.ui.signalName
import hu.obddiag.app.ui.stateLabel
import hu.obddiag.app.data.AppSettings
import hu.obddiag.core.ClearResult
import hu.obddiag.core.FreezeFrame
import hu.obddiag.core.connection.ConnectionState
import hu.obddiag.core.dtc.Dtc
import hu.obddiag.core.dtc.DtcStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class DtcViewModel(private val c: AppContainer) : ViewModel() {
    data class UiState(
        val loading: Boolean = false,
        val clearing: Boolean = false,
        val error: String? = null,
        val message: String? = null,
        val freezeFrame: FreezeFrame? = null,
        val freezeLoading: Boolean = false,
        val freezeLoaded: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    val scan = c.diagnostics.lastScan
    val connection = c.connection.state
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun info(d: Dtc): DtcInfo = c.dtcInfo.describe(d)

    fun read() {
        if (_ui.value.loading) return
        _ui.value = _ui.value.copy(loading = true, error = null, message = null, freezeFrame = null, freezeLoaded = false)
        viewModelScope.launch {
            try {
                c.diagnostics.scan()
                _ui.value = _ui.value.copy(loading = false)
            } catch (e: NotConnectedException) {
                _ui.value = _ui.value.copy(loading = false, error = "not_connected")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun clear(onDone: (ClearResult) -> Unit) {
        _ui.value = _ui.value.copy(clearing = true, error = null)
        viewModelScope.launch {
            val r = try { c.diagnostics.clear() } catch (e: Exception) { ClearResult.Failure(e.message ?: "") }
            _ui.value = _ui.value.copy(clearing = false)
            onDone(r)
            if (r is ClearResult.Success) read()
        }
    }

    fun loadFreezeFrame() {
        _ui.value = _ui.value.copy(freezeLoading = true)
        viewModelScope.launch {
            val ff = try { c.diagnostics.freezeFrame() } catch (e: Exception) { null }
            _ui.value = _ui.value.copy(freezeLoading = false, freezeFrame = ff, freezeLoaded = true)
        }
    }

    fun setMessage(m: String?) { _ui.value = _ui.value.copy(message = m) }

    fun exportText(scan: DtcScan, labels: (Dtc) -> String): String = buildString {
        val fmt = DateFormat.getDateTimeInstance()
        append("OBD-II hibakódjelentés – ").append(fmt.format(Date(scan.timestampMs))).append('\n')
        if (scan.simulated) append("FIGYELEM: SZIMULÁLT ADATOK\n")
        (c.connection.state.value as? ConnectionState.Connected)?.let { append("Protokoll: ").append(it.vehicle.protocol.displayName).append('\n') }
        append("MIL: ").append(when (scan.milOn) { true -> "BE"; false -> "KI"; null -> "ismeretlen" }).append("\n\n")
        append("kód,státusz,ECU,leírás\n")
        scan.all.forEach { d -> append(d.code).append(',').append(d.status.name).append(',').append(d.ecu).append(",\"").append(labels(d).replace("\"", "'")).append("\"\n") }
    }
}

@Composable
fun DtcScreen(nav: NavController) {
    val vm = appViewModel { DtcViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf<DtcStatus?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = conn is ConnectionState.Connected

    ScreenScaffold(
        title = stringResource(R.string.dtc_title),
        subtitle = scan?.let { stringResource(R.string.dtc_last_read, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.timestampMs))) },
        actions = {
            val s = scan
            if (s != null) IconButton(onClick = {
                scope.launch {
                    val text = vm.exportText(s) { d -> vm.info(d).description ?: vm.info(d).system }
                    CsvExporter.share(context, "hibakodok_${CsvExporter.fileStamp()}.csv", text, chooserTitle = context.getString(R.string.share))
                }
            }) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
        }
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = vm::read, enabled = connected && !ui.loading, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        if (ui.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Refresh, null)
                        Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.dtc_read))
                    }
                    OutlinedButton(
                        onClick = { confirmClear = true }, enabled = connected && !ui.clearing && !ui.loading,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.status.danger)
                    ) {
                        if (ui.clearing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.DeleteSweep, null)
                        Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.dtc_clear))
                    }
                }
            }
            if (!connected) item {
                ErrorBanner(stringResource(R.string.not_connected), detail = stringResource(R.string.not_connected_hint)) { nav.navigate(Routes.ADAPTER) }
            }
            ui.error?.let { e -> item { ErrorBanner(if (e == "not_connected") stringResource(R.string.not_connected) else stringResource(R.string.dtc_read_error), detail = e.takeIf { it != "not_connected" }) } }
            ui.message?.let { m -> item { AppCard { Text(m, style = MaterialTheme.typography.bodyMedium) } } }

            val s = scan
            when {
                ui.loading && s == null -> item { SkeletonList(4) }
                s == null -> item {
                    EmptyState(Icons.Outlined.ReportProblem, stringResource(R.string.dtc_empty_title), stringResource(R.string.dtc_empty_message))
                }
                else -> {
                    item { SummaryCard(s) }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(filter == null, { filter = null }, { Text(stringResource(R.string.all) + " (${s.all.size})") })
                            FilterChip(filter == DtcStatus.STORED, { filter = DtcStatus.STORED }, { Text(stringResource(R.string.dtc_stored) + " (${s.stored.size})") })
                            FilterChip(filter == DtcStatus.PENDING, { filter = DtcStatus.PENDING }, { Text(stringResource(R.string.dtc_pending) + " (${s.pending.size})") })
                            FilterChip(filter == DtcStatus.PERMANENT, { filter = DtcStatus.PERMANENT }, { Text(stringResource(R.string.dtc_permanent) + " (${s.permanent.size})") })
                        }
                    }
                    val list = if (filter == null) s.all else s.all.filter { it.status == filter }
                    if (list.isEmpty()) item {
                        EmptyState(Icons.Outlined.CheckCircle, stringResource(R.string.dtc_none_title), stringResource(R.string.dtc_none_message))
                    }
                    items(list, key = { "${it.code}-${it.status}-${it.ecu}" }) { d -> DtcCard(d, vm.info(d)) }
                    if (s.notSupported.isNotEmpty()) item {
                        Text(stringResource(R.string.dtc_not_supported, s.notSupported.map { statusName(it) }.joinToString()),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (s.stored.isNotEmpty()) item {
                        SectionTitle(stringResource(R.string.freeze_frame))
                        FreezeFrameCard(ui.freezeFrame, ui.freezeLoading, ui.freezeLoaded, settings, onLoad = vm::loadFreezeFrame)
                    }
                }
            }
            if (connected) item {
                AppCard {
                    Text(stringResource(R.string.dtc_other_ecus_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.dtc_other_ecus_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { nav.navigate(Routes.MFR) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.dtc_other_ecus_open))
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Outlined.WarningAmber, null, tint = AppTheme.status.warning) },
            title = { Text(stringResource(R.string.dtc_clear_confirm_title)) },
            text = { Text(stringResource(R.string.dtc_clear_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clear { r ->
                        vm.setMessage(when (r) {
                            ClearResult.Success -> context.getString(R.string.dtc_clear_ok)
                            is ClearResult.Rejected -> context.getString(R.string.dtc_clear_rejected, r.reason)
                            is ClearResult.Failure -> context.getString(R.string.dtc_clear_failed, r.reason)
                        })
                    }
                }) { Text(stringResource(R.string.dtc_clear_confirm), color = AppTheme.status.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun statusName(s: DtcStatus) = when (s) {
    DtcStatus.STORED -> stringResource(R.string.dtc_stored)
    DtcStatus.PENDING -> stringResource(R.string.dtc_pending)
    DtcStatus.PERMANENT -> stringResource(R.string.dtc_permanent)
}

@Composable
private fun SummaryCard(s: DtcScan) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val mil = s.milOn
            Icon(Icons.Outlined.WarningAmber, null, tint = if (mil == true) AppTheme.status.danger else AppTheme.status.ok, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(when (mil) {
                    true -> stringResource(R.string.mil_on)
                    false -> stringResource(R.string.mil_off)
                    null -> stringResource(R.string.mil_unknown)
                }, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.dtc_summary, s.stored.size, s.pending.size, s.permanent.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.simulated) SimulatedBadge()
        }
    }
}

@Composable
private fun DtcCard(d: Dtc, info: DtcInfo) {
    var expanded by rememberSaveable(d.code, d.status) { mutableStateOf(false) }
    val statusColor = when (d.status) {
        DtcStatus.STORED -> AppTheme.status.danger
        DtcStatus.PENDING -> AppTheme.status.warning
        DtcStatus.PERMANENT -> AppTheme.status.info
    }
    AppCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = statusColor)
            Spacer(Modifier.width(10.dp))
            StatusPill(statusName(d.status), statusColor)
            Spacer(Modifier.weight(1f))
            Text("ECU ${d.ecu}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Text(info.description ?: stringResource(R.string.dtc_no_description), style = MaterialTheme.typography.bodyLarge)
        Text(info.system + if (info.manufacturerSpecific) " · " + stringResource(R.string.dtc_manufacturer_specific) else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 10.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (info.causes.isNotEmpty()) {
                    Text(stringResource(R.string.dtc_causes), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    info.causes.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                } else if (info.isGenericGroup) {
                    Text(stringResource(R.string.dtc_no_causes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                }
                if (info.tips.isNotEmpty()) {
                    Text(stringResource(R.string.dtc_tips), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    info.tips.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                }
                Text(stringResource(R.string.dtc_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun FreezeFrameCard(ff: FreezeFrame?, loading: Boolean, loaded: Boolean, settings: AppSettings, onLoad: () -> Unit) {
    AppCard {
        when {
            loading -> SkeletonList(3)
            ff == null && !loaded -> {
                Text(stringResource(R.string.freeze_frame_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onLoad, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.freeze_frame_load))
                }
            }
            ff == null -> Text(stringResource(R.string.freeze_frame_none), style = MaterialTheme.typography.bodyMedium)
            else -> {
                ff.triggerDtc?.let { Text(stringResource(R.string.freeze_frame_trigger, it), style = MaterialTheme.typography.titleSmall) }
                Spacer(Modifier.height(8.dp))
                ff.values.forEach { p ->
                    p.values.forEach { (id, v) ->
                        val f = formatSignal(id, v, settings.units)
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(signalName(id), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("${f.text} ${f.unit}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                    p.states.forEach { (id, code) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(signalName(id), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(stateLabel(code), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private fun labelFor(context: android.content.Context, id: String) = Labels.signalName(context, id)
