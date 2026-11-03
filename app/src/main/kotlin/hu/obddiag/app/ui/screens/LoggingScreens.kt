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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import hu.obddiag.app.AppContainer
import hu.obddiag.app.R
import hu.obddiag.app.data.AppSettings
import hu.obddiag.app.data.CsvExporter
import hu.obddiag.app.ui.Labels
import hu.obddiag.app.ui.components.AppCard
import hu.obddiag.app.ui.components.ChartSeries
import hu.obddiag.app.ui.components.ChartState
import hu.obddiag.app.ui.components.EmptyState
import hu.obddiag.app.ui.components.ErrorBanner
import hu.obddiag.app.ui.components.LiveChart
import hu.obddiag.app.ui.components.ScreenScaffold
import hu.obddiag.app.ui.components.SectionTitle
import hu.obddiag.app.ui.components.SimulatedBadge
import hu.obddiag.app.ui.components.SkeletonList
import hu.obddiag.app.ui.components.convertForDisplay
import hu.obddiag.app.ui.components.formatSignal
import hu.obddiag.app.ui.nav.Routes
import hu.obddiag.app.ui.nav.appViewModel
import hu.obddiag.app.ui.signalName
import hu.obddiag.app.ui.theme.AppTheme
import hu.obddiag.core.connection.ConnectionState
import hu.obddiag.core.pid.StandardPids
import hu.obddiag.data.LogSampleEntity
import hu.obddiag.data.LogSessionEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class LoggingViewModel(val c: AppContainer) : ViewModel() {
    val sessions = c.logging.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recording = c.logging.recording
    val connection = c.connection.state
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun signalChoices(): List<String> {
        val supported = c.connection.client.value?.supportedPids ?: return emptyList()
        return StandardPids.all.filter { it.pid in supported }.flatMap { p -> p.signals.map { it.id } }
    }

    fun start(name: String, signals: Set<String>) = viewModelScope.launch { c.logging.start(name, signals) }
    fun stop() = viewModelScope.launch { c.logging.stop() }
    fun delete(id: Long) = viewModelScope.launch { c.logging.delete(id) }
    fun rename(id: Long, name: String) = viewModelScope.launch { c.logging.rename(id, name) }
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun LoggingScreen(nav: NavController) {
    val vm = appViewModel { LoggingViewModel(it) }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showStart by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    ScreenScaffold(title = stringResource(R.string.logging_title), onBack = { nav.popBackStack() }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val rec = recording
                AppCard(border = if (rec != null) AppTheme.status.danger else null) {
                    if (rec != null) {
                        var now by remember { mutableStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(rec.sessionId) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.FiberManualRecord, null, tint = AppTheme.status.danger)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rec.name, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.logging_running, duration(now - rec.startedAt), rec.signals.size, rec.samples),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { vm.stop() }, Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.status.danger)) {
                            Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.logging_stop))
                        }
                    } else {
                        Text(stringResource(R.string.logging_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { showStart = true }, enabled = conn is ConnectionState.Connected, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Icon(Icons.Outlined.FiberManualRecord, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.logging_start))
                        }
                        if (conn !is ConnectionState.Connected) Text(stringResource(R.string.not_connected), style = MaterialTheme.typography.bodySmall, color = AppTheme.status.warning)
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.logging_sessions)) }
            if (sessions.isEmpty()) item {
                EmptyState(Icons.Outlined.FiberManualRecord, stringResource(R.string.logging_empty_title), stringResource(R.string.logging_empty_message))
            }
            items(sessions, key = { it.id }) { s ->
                AppCard(onClick = { nav.navigate(Routes.logDetail(s.id)) }, padding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(fmt.format(Date(s.startedAt)) + " · " + duration((s.endedAt ?: System.currentTimeMillis()) - s.startedAt) +
                                " · " + stringResource(R.string.logging_samples, s.sampleCount),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (s.simulated) SimulatedBadge()
                        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showStart) {
        val choices = remember { vm.signalChoices() }
        val context = LocalContext.current
        var name by remember { mutableStateOf(context.getString(R.string.logging_default_name, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()))) }
        var selected by remember { mutableStateOf(choices.filter { it in settings.favorites }.toSet().ifEmpty { choices.take(4).toSet() }) }
        AlertDialog(
            onDismissRequest = { showStart = false },
            title = { Text(stringResource(R.string.logging_start)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    item {
                        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.logging_choose_signals, selected.size), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            choices.forEach { id ->
                                FilterChip(id in selected, { selected = if (id in selected) selected - id else selected + id }, { Text(Labels.signalName(context, id)) })
                            }
                        }
                        Text(stringResource(R.string.logging_rate_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = selected.isNotEmpty() && name.isNotBlank(), onClick = { vm.start(name.trim(), selected); showStart = false }) {
                    Text(stringResource(R.string.logging_start))
                }
            },
            dismissButton = { TextButton(onClick = { showStart = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
fun LogDetailScreen(nav: NavController, id: Long) {
    val vm = appViewModel { LoggingViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    var session by remember { mutableStateOf<LogSessionEntity?>(null) }
    var samples by remember { mutableStateOf<List<LogSampleEntity>?>(null) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(id, reload) {
        session = vm.c.logging.session(id)
        samples = vm.c.logging.samples(id)
    }
    val s = session
    ScreenScaffold(
        title = s?.name ?: stringResource(R.string.logging_title), onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = { rename = true }, enabled = s != null) { Icon(Icons.Outlined.Edit, stringResource(R.string.rename)) }
            IconButton(enabled = s != null && samples != null, onClick = {
                val sess = s ?: return@IconButton
                val rows = samples.orEmpty().map { Triple(it.timestampMs, it.signalId, it.value) }
                scope.launch {
                    CsvExporter.share(context, "naplo_${sess.id}_${CsvExporter.fileStamp()}.csv",
                        CsvExporter.samplesCsv(rows, { Labels.signalName(context, it) }, sess.simulated, sess.name),
                        chooserTitle = context.getString(R.string.share))
                }
            }) { Icon(Icons.Outlined.Share, stringResource(R.string.export_csv)) }
            IconButton(onClick = { confirmDelete = true }, enabled = s != null) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete)) }
        }
    ) {
        val list = samples
        if (s == null || list == null) { SkeletonList(4, Modifier.fillMaxSize()); return@ScreenScaffold }
        val signalIds = s.signalIds.split(',').filter { it.isNotBlank() }
        val palette = AppTheme.status.chartPalette
        val grouped = remember(list) { list.groupBy { it.signalId } }
        val start = s.startedAt
        val end = s.endedAt ?: (list.lastOrNull()?.timestampMs ?: start)
        val chartState = remember(id) { ChartState(initialWindowMs = (end - start).coerceIn(ChartState.MIN_WINDOW, ChartState.MAX_WINDOW)).also { it.pause(end) } }
        var shown by remember(id) { mutableStateOf(signalIds.take(3).toSet()) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AppCard {
                    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                    Text(fmt.format(Date(start)) + " – " + fmt.format(Date(end)), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.logging_samples, list.size) + " · " + duration(end - start), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.simulated) { Spacer(Modifier.height(6.dp)); SimulatedBadge() }
                }
            }
            if (list.isEmpty()) item { ErrorBanner(stringResource(R.string.logging_no_samples)) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    signalIds.forEach { sid ->
                        FilterChip(sid in shown, { shown = if (sid in shown) shown - sid else if (shown.size < 6) shown + sid else shown }, { Text(signalName(sid)) })
                    }
                }
            }
            item {
                val series = signalIds.filter { it in shown }.mapIndexed { i, sid ->
                    val rows = grouped[sid].orEmpty()
                    ChartSeries(sid, signalName(sid), formatSignal(sid, 0.0, settings.units).unit, palette[i % palette.size],
                        LongArray(rows.size) { rows[it].timestampMs },
                        DoubleArray(rows.size) { convertForDisplay(sid, rows[it].value, settings.units) },
                        StandardPids.signal(sid)?.decimals ?: 1)
                }
                AppCard(padding = PaddingValues(12.dp)) {
                    LiveChart(series, chartState, end, height = 280.dp)
                    Text(stringResource(R.string.chart_gestures_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (rename && s != null) {
        var name by remember { mutableStateOf(s.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(s.id, name.trim()); rename = false; reload++ }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (confirmDelete && s != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.logging_delete_title)) },
            confirmButton = { TextButton(onClick = { vm.delete(s.id); confirmDelete = false; nav.popBackStack() }) { Text(stringResource(R.string.delete), color = AppTheme.status.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}
