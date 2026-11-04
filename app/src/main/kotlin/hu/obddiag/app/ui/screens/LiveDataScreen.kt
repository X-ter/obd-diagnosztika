@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package hu.obddiag.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import hu.obddiag.app.data.LiveValue
import hu.obddiag.app.data.LiveViewMode
import hu.obddiag.app.ui.Labels
import hu.obddiag.app.ui.components.AppCard
import hu.obddiag.app.ui.components.ChartSeries
import hu.obddiag.app.ui.components.ChartState
import hu.obddiag.app.ui.components.EmptyState
import hu.obddiag.app.ui.components.LiveChart
import hu.obddiag.app.ui.components.ScreenScaffold
import hu.obddiag.app.ui.components.SimulatedBadge
import hu.obddiag.app.ui.components.StatusPill
import hu.obddiag.app.ui.components.convertForDisplay
import hu.obddiag.app.ui.components.formatSignal
import hu.obddiag.app.ui.nav.Routes
import hu.obddiag.app.ui.nav.appViewModel
import hu.obddiag.app.ui.signalName
import hu.obddiag.app.ui.stateLabel
import hu.obddiag.app.ui.theme.AppTheme
import hu.obddiag.app.ui.theme.ValueText
import hu.obddiag.core.connection.ConnectionState
import hu.obddiag.core.pid.PidCategory
import hu.obddiag.core.pid.StandardPids
import hu.obddiag.core.poll.PidHealth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A listában megjeleníthető elem: numerikus jel vagy állapot. */
data class LiveItem(val id: String, val pid: Int, val category: PidCategory, val isState: Boolean)

class LiveDataViewModel(private val c: AppContainer) : ViewModel() {
    val connection = c.connection.state
    val values = c.live.values
    val states = c.live.states
    val health = c.live.health
    val cycleRate = c.live.cycleRate
    val tick = c.live.tick
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _monitored = MutableStateFlow<Set<String>?>(null)
    val monitored: StateFlow<Set<String>?> = _monitored.asStateFlow()

    /** Az ECU által támogatott szabványos jelek. */
    fun available(): List<LiveItem> {
        val supported = c.connection.client.value?.supportedPids ?: return emptyList()
        return StandardPids.all.filter { it.pid in supported && it.pid !in StandardPids.SUPPORT_PIDS }.flatMap { p ->
            p.signals.map { LiveItem(it.id, p.pid, it.category, false) } + p.states.map { LiveItem(it.id, p.pid, it.category, true) }
        }
    }

    fun initMonitored(items: List<LiveItem>) {
        if (_monitored.value != null || items.isEmpty()) return
        // Alapból minden, a jármű által támogatott érték figyelve van; a felhasználó kapcsolhatja ki őket.
        _monitored.value = items.map { it.id }.toSet()
    }

    fun toggleMonitor(id: String) {
        val cur = _monitored.value.orEmpty()
        _monitored.value = if (id in cur) cur - id else cur + id
    }

    fun setMonitored(ids: Set<String>) { _monitored.value = ids }

    fun toggleFavorite(id: String) = viewModelScope.launch { c.settings.toggleFavorite(id) }
    fun setViewMode(m: LiveViewMode) = viewModelScope.launch { c.settings.setLiveViewMode(m) }
    fun subscribe(ids: Set<String>) = c.live.subscribe("live", ids)
    fun unsubscribe() = c.live.unsubscribe("live")
    fun resetMinMax() = c.live.resetMinMax()
    fun history(id: String) = c.live.history(id)
    fun isSimulated() = c.connection.isSimulated
}

@Composable
fun LiveDataScreen(nav: NavController) {
    val vm = appViewModel { LiveDataViewModel(it) }
    val conn by vm.connection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val values by vm.values.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val monitoredOrNull by vm.monitored.collectAsStateWithLifecycle()
    val rate by vm.cycleRate.collectAsStateWithLifecycle()
    val items = remember(conn) { vm.available() }
    val monitored = monitoredOrNull.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<PidCategory?>(null) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(items) { vm.initMonitored(items) }
    DisposableEffect(monitored) {
        vm.subscribe(monitored)
        onDispose { vm.unsubscribe() }
    }

    val connected = conn is ConnectionState.Connected
    ScreenScaffold(
        title = stringResource(R.string.live_title),
        subtitle = if (connected && monitored.isNotEmpty()) stringResource(R.string.live_rate, monitored.size, rate) else null,
        actions = {
            if ((conn as? ConnectionState.Connected)?.simulated == true) SimulatedBadge(Modifier.padding(end = 4.dp))
            IconButton(onClick = vm::resetMinMax) { Icon(Icons.Outlined.RestartAlt, stringResource(R.string.reset_min_max)) }
        }
    ) {
        if (!connected) {
            EmptyState(Icons.Outlined.ShowChart, stringResource(R.string.not_connected), stringResource(R.string.live_connect_hint)) {
                TextButton(onClick = { nav.navigate(Routes.ADAPTER) }) { Text(stringResource(R.string.adapter_choose)) }
            }
            return@ScreenScaffold
        }
        if (items.isEmpty()) {
            EmptyState(Icons.Outlined.ShowChart, stringResource(R.string.live_no_pids_title), stringResource(R.string.live_no_pids_message))
            return@ScreenScaffold
        }
        val filtered = items.filter { item ->
            (category == null || item.category == category) &&
                (!favoritesOnly || item.id in settings.favorites) &&
                (query.isBlank() || Labels.signalName(context, item.id).contains(query, ignoreCase = true) || item.id.contains(query, ignoreCase = true))
        }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_pid)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, null) } },
                    shape = MaterialTheme.shapes.small
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(favoritesOnly, { favoritesOnly = !favoritesOnly }, { Text(stringResource(R.string.favorites)) },
                        leadingIcon = { Icon(Icons.Outlined.Star, null, Modifier.size(16.dp)) })
                    FilterChip(category == null, { category = null }, { Text(stringResource(R.string.all)) })
                    items.map { it.category }.distinct().forEach { cat ->
                        FilterChip(category == cat, { category = if (category == cat) null else cat }, { Text(Labels.category(context, cat)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        val modes = listOf(LiveViewMode.LIST to Icons.Outlined.ViewList, LiveViewMode.CARDS to Icons.Outlined.GridView, LiveViewMode.CHART to Icons.Outlined.ShowChart)
                        modes.forEachIndexed { i, (m, icon) ->
                            SegmentedButton(
                                selected = settings.liveViewMode == m, onClick = { vm.setViewMode(m) },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                icon = { Icon(icon, null, Modifier.size(18.dp)) }
                            ) {
                                Text(stringResource(when (m) { LiveViewMode.LIST -> R.string.view_list; LiveViewMode.CARDS -> R.string.view_cards; LiveViewMode.CHART -> R.string.view_chart }))
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.live_monitored_count, monitored.size, items.size), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.setMonitored(monitored + filtered.map { it.id }) }) { Text(stringResource(R.string.monitor_all)) }
                    TextButton(onClick = { vm.setMonitored(monitored - filtered.map { it.id }.toSet()) }) { Text(stringResource(R.string.monitor_none)) }
                }
            }
            when (settings.liveViewMode) {
                LiveViewMode.LIST -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.id }) { item ->
                        LiveRow(item, values[item.id], states[item.id], health[item.pid], item.id in monitored, item.id in settings.favorites, settings,
                            onToggle = { vm.toggleMonitor(item.id) }, onFavorite = { vm.toggleFavorite(item.id) })
                    }
                }
                LiveViewMode.CARDS -> LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filtered, key = { it.id }) { item ->
                        LiveCard(item, values[item.id], states[item.id], health[item.pid], item.id in monitored, item.id in settings.favorites, settings,
                            onToggle = { vm.toggleMonitor(item.id) }, onFavorite = { vm.toggleFavorite(item.id) })
                    }
                }
                LiveViewMode.CHART -> LiveChartPanel(vm, filtered.filter { it.id in monitored && !it.isState }, settings)
            }
        }
    }
}

@Composable
private fun healthPill(h: PidHealth?, value: LiveValue?, monitored: Boolean) {
    val now = System.currentTimeMillis()
    when {
        !monitored -> StatusPill(stringResource(R.string.pid_paused), MaterialTheme.colorScheme.outline)
        h == PidHealth.NOT_SUPPORTED -> StatusPill(stringResource(R.string.pid_not_supported), AppTheme.status.danger)
        h == PidHealth.NO_RESPONSE -> StatusPill(stringResource(R.string.pid_no_response), AppTheme.status.warning)
        value == null -> StatusPill(stringResource(R.string.pid_waiting), AppTheme.status.info)
        now - value.timestampMs > 5_000 -> StatusPill(stringResource(R.string.pid_stale), AppTheme.status.warning)
        else -> StatusPill(stringResource(R.string.pid_live), AppTheme.status.ok)
    }
}

@Composable
private fun LiveRow(item: LiveItem, value: LiveValue?, state: String?, health: PidHealth?, monitored: Boolean, favorite: Boolean,
                    settings: AppSettings, onToggle: () -> Unit, onFavorite: () -> Unit) {
    AppCard(onClick = onToggle, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(signalName(item.id), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PID ${"%02X".format(item.pid)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    healthPill(health, value, monitored)
                }
                if (value != null && monitored && !item.isState) {
                    val mn = formatSignal(item.id, value.min, settings.units); val mx = formatSignal(item.id, value.max, settings.units)
                    Text("min ${mn.text}  ·  max ${mx.text}", style = ValueText.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (item.isState) {
                    Text(state?.let { stateLabel(it) } ?: "—", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                } else {
                    val f = formatSignal(item.id, if (monitored) value?.value else null, settings.units)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(f.text, style = ValueText.medium)
                        Spacer(Modifier.width(3.dp))
                        Text(f.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
            }
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, stringResource(R.string.favorites),
                    tint = if (favorite) AppTheme.status.warning else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LiveCard(item: LiveItem, value: LiveValue?, state: String?, health: PidHealth?, monitored: Boolean, favorite: Boolean,
                     settings: AppSettings, onToggle: () -> Unit, onFavorite: () -> Unit) {
    AppCard(onClick = onToggle, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(signalName(item.id), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).height(40.dp))
            IconButton(onClick = onFavorite, modifier = Modifier.size(32.dp)) {
                Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, stringResource(R.string.favorites),
                    tint = if (favorite) AppTheme.status.warning else MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        if (item.isState) {
            Text(state?.let { stateLabel(it) } ?: "—", style = MaterialTheme.typography.titleSmall, maxLines = 2, modifier = Modifier.height(34.dp))
        } else {
            val f = formatSignal(item.id, if (monitored) value?.value else null, settings.units)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(f.text, style = ValueText.large, maxLines = 1)
                Spacer(Modifier.width(3.dp))
                Text(f.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            healthPill(health, value, monitored)
            Spacer(Modifier.weight(1f))
            Icon(if (monitored) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LiveChartPanel(vm: LiveDataViewModel, items: List<LiveItem>, settings: AppSettings) {
    val tick by vm.tick.collectAsStateWithLifecycle()
    val chartState = remember { ChartState() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showScale by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = AppTheme.status.chartPalette
    LaunchedEffect(chartState.paused) {
        while (!chartState.paused) { now = System.currentTimeMillis(); delay(100) }
    }
    val chosen = items.take(6)
    val series = remember(tick, chosen, settings.units, chartState.paused) {
        chosen.mapIndexedNotNull { i, item ->
            val buf = vm.history(item.id) ?: return@mapIndexedNotNull null
            val (t, v) = buf.snapshot()
            val def = StandardPids.signal(item.id)
            ChartSeries(item.id, Labels.signalName(context, item.id), formatSignal(item.id, 0.0, settings.units).unit, palette[i % palette.size],
                t, DoubleArray(v.size) { k -> convertForDisplay(item.id, v[k], settings.units) }, def?.decimals ?: 1)
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            AppCard(padding = PaddingValues(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.chart_window, (chartState.windowMs / 1000).toInt()), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showScale = true }) { Icon(Icons.Outlined.Tune, stringResource(R.string.chart_scale)) }
                    IconButton(onClick = {
                        scope.launch {
                            val rows = chosen.flatMap { item ->
                                val (t, v) = vm.history(item.id)?.snapshot() ?: (LongArray(0) to DoubleArray(0))
                                t.indices.map { Triple(t[it], item.id, v[it]) }
                            }.sortedBy { it.first }
                            CsvExporter.share(context, "grafikon_${CsvExporter.fileStamp()}.csv",
                                CsvExporter.samplesCsv(rows, { Labels.signalName(context, it) }, vm.isSimulated(), "Élő grafikon"),
                                chooserTitle = context.getString(R.string.share))
                        }
                    }) { Icon(Icons.Outlined.Share, stringResource(R.string.export_csv)) }
                    IconButton(onClick = { chartState.toggle(System.currentTimeMillis()) }) {
                        Icon(if (chartState.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null)
                    }
                }
                if (chosen.isEmpty()) {
                    Text(stringResource(R.string.chart_select_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LiveChart(series, chartState, now, height = 280.dp)
                }
                Text(stringResource(R.string.chart_gestures), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                if (items.size > 6) Text(stringResource(R.string.chart_max_series), style = MaterialTheme.typography.bodySmall, color = AppTheme.status.warning)
            }
        }
    }
    if (showScale) {
        var auto by remember { mutableStateOf(chartState.autoY) }
        var minText by remember { mutableStateOf(chartState.manualMin.toString()) }
        var maxText by remember { mutableStateOf(chartState.manualMax.toString()) }
        AlertDialog(
            onDismissRequest = { showScale = false },
            title = { Text(stringResource(R.string.chart_scale)) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.chart_auto_scale), Modifier.weight(1f)); Switch(auto, { auto = it })
                    }
                    if (!auto) {
                        OutlinedTextField(minText, { minText = it }, label = { Text("Min") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        OutlinedTextField(maxText, { maxText = it }, label = { Text("Max") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        Text(stringResource(R.string.chart_manual_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    chartState.autoY = auto
                    val mn = minText.replace(',', '.').toDoubleOrNull(); val mx = maxText.replace(',', '.').toDoubleOrNull()
                    if (mn != null && mx != null && mx > mn) { chartState.manualMin = mn; chartState.manualMax = mx }
                    showScale = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showScale = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}
