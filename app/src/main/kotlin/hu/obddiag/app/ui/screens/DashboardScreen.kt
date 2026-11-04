@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package hu.obddiag.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import hu.obddiag.app.data.Dashboard
import hu.obddiag.app.data.DashboardWidget
import hu.obddiag.app.data.GaugeType
import hu.obddiag.app.data.LiveValue
import hu.obddiag.app.data.WidgetSize
import hu.obddiag.app.ui.Labels
import hu.obddiag.app.ui.components.AnalogGauge
import hu.obddiag.app.ui.components.AppCard
import hu.obddiag.app.ui.components.BarGauge
import hu.obddiag.app.ui.components.DigitalGauge
import hu.obddiag.app.ui.components.EmptyState
import hu.obddiag.app.ui.components.GaugeModel
import hu.obddiag.app.ui.components.NumberTile
import hu.obddiag.app.ui.components.RingGauge
import hu.obddiag.app.ui.components.ScreenScaffold
import hu.obddiag.app.ui.components.SimulatedBadge
import hu.obddiag.app.ui.components.SparklineTile
import hu.obddiag.app.ui.components.convertForDisplay
import hu.obddiag.app.ui.components.displayRange
import hu.obddiag.app.ui.components.formatSignal
import hu.obddiag.app.ui.nav.appViewModel
import hu.obddiag.app.ui.signalName
import hu.obddiag.app.ui.theme.AppTheme
import hu.obddiag.core.connection.ConnectionState
import hu.obddiag.core.pid.StandardPids
import hu.obddiag.core.util.UnitConverter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(private val c: AppContainer) : ViewModel() {
    val dashboards = c.dashboards.dashboards.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val values = c.live.values
    val tick = c.live.tick
    val connection = c.connection.state

    fun select(id: Long) = viewModelScope.launch { c.settings.setActiveDashboard(id) }
    fun save(d: Dashboard) = viewModelScope.launch { c.dashboards.save(d) }
    fun create(name: String, order: Int) = viewModelScope.launch { val id = c.dashboards.create(name, order); c.settings.setActiveDashboard(id) }
    fun delete(id: Long) = viewModelScope.launch { c.dashboards.delete(id); c.settings.setActiveDashboard(null) }
    fun subscribe(ids: Set<String>) = c.live.subscribe("dashboard", ids)
    fun unsubscribe() = c.live.unsubscribe("dashboard")
    fun history(id: String) = c.live.history(id)

    /** A választható jelek: kapcsolat esetén a támogatottak, különben az összes szabványos. */
    fun selectableSignals(): List<String> {
        val supported = c.connection.client.value?.supportedPids
        val standard = StandardPids.all.filter { supported == null || supported.isEmpty() || it.pid in supported }.flatMap { p -> p.signals.map { it.id } }
        val derived = if (supported.isNullOrEmpty()) hu.obddiag.core.pid.DerivedSignals.all else hu.obddiag.core.pid.DerivedSignals.available(supported)
        return derived.map { it.id } + standard
    }
}

@Composable
fun DashboardScreen(@Suppress("UNUSED_PARAMETER") nav: NavController) {
    val vm = appViewModel { DashboardViewModel(it) }
    val dashboards by vm.dashboards.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val values by vm.values.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var editWidget by remember { mutableStateOf<DashboardWidget?>(null) }
    var addingWidget by remember { mutableStateOf(false) }
    var newDashboard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val active = dashboards.firstOrNull { it.id == settings.activeDashboardId } ?: dashboards.firstOrNull()
    val signals = active?.widgets?.map { it.signalId }?.toSet().orEmpty()
    DisposableEffect(signals) {
        vm.subscribe(signals)
        onDispose { vm.unsubscribe() }
    }

    ScreenScaffold(
        title = stringResource(R.string.dash_title),
        subtitle = active?.name,
        actions = {
            if ((conn as? ConnectionState.Connected)?.simulated == true) SimulatedBadge(Modifier.padding(end = 4.dp))
            if (editing) {
                IconButton(onClick = { addingWidget = true }) { Icon(Icons.Outlined.Add, stringResource(R.string.dash_add_widget)) }
                IconButton(onClick = { confirmDelete = true }, enabled = dashboards.size > 1) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete)) }
            }
            IconButton(onClick = { editing = !editing }) {
                Icon(if (editing) Icons.Outlined.Check else Icons.Outlined.Edit, stringResource(if (editing) R.string.done else R.string.edit))
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dashboards.forEach { d -> FilterChip(d.id == active?.id, { vm.select(d.id) }, { Text(d.name) }) }
                FilterChip(false, { newDashboard = true }, { Text(stringResource(R.string.dash_new)) }, leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) })
            }
            if (conn !is ConnectionState.Connected) {
                Text(stringResource(R.string.dash_not_connected), style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.status.warning, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (active == null || active.widgets.isEmpty()) {
                EmptyState(Icons.Outlined.Speed, stringResource(R.string.dash_empty_title), stringResource(R.string.dash_empty_message)) {
                    TextButton(onClick = { editing = true; addingWidget = true }) { Text(stringResource(R.string.dash_add_widget)) }
                }
                return@Column
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Fekvő módban / tableten több oszlop
                val cols = when {
                    maxWidth >= 840.dp -> 6
                    maxWidth >= 600.dp -> 4
                    else -> 2
                }
                LazyVerticalGrid(GridCells.Fixed(cols), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(active.widgets, key = { _, w -> w.id }, span = { _, w ->
                        GridItemSpan(when (w.size) { WidgetSize.SMALL -> 1; WidgetSize.WIDE -> minOf(2, cols); WidgetSize.FULL -> cols })
                    }) { index, w ->
                        WidgetCard(w, values[w.signalId], settings, vm, editing,
                            onMove = { delta ->
                                val list = active.widgets.toMutableList()
                                val target = (index + delta).coerceIn(0, list.lastIndex)
                                list.add(target, list.removeAt(index))
                                vm.save(active.copy(widgets = list))
                            },
                            onResize = {
                                val next = WidgetSize.entries[(w.size.ordinal + 1) % WidgetSize.entries.size]
                                vm.save(active.copy(widgets = active.widgets.map { if (it.id == w.id) it.copy(size = next) else it }))
                            },
                            onEdit = { editWidget = w },
                            onDelete = { vm.save(active.copy(widgets = active.widgets.filter { it.id != w.id })) })
                    }
                }
            }
        }
    }

    if (addingWidget || editWidget != null) {
        WidgetEditor(
            initial = editWidget, signals = vm.selectableSignals(), settings = settings,
            onDismiss = { addingWidget = false; editWidget = null },
            onSave = { w ->
                val d = active
                if (d != null) {
                    val list = if (d.widgets.any { it.id == w.id }) d.widgets.map { if (it.id == w.id) w else it } else d.widgets + w
                    vm.save(d.copy(widgets = list))
                }
                addingWidget = false; editWidget = null
            }
        )
    }
    if (newDashboard) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newDashboard = false },
            title = { Text(stringResource(R.string.dash_new)) },
            text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) vm.create(name.trim(), dashboards.size); newDashboard = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { newDashboard = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (confirmDelete && active != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.dash_delete_title, active.name)) },
            confirmButton = { TextButton(onClick = { vm.delete(active.id); confirmDelete = false; editing = false }) { Text(stringResource(R.string.delete), color = AppTheme.status.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun WidgetCard(
    w: DashboardWidget, value: LiveValue?, settings: AppSettings, vm: DashboardViewModel, editing: Boolean,
    onMove: (Int) -> Unit, onResize: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit
) {
    val def = StandardPids.signal(w.signalId)
    val f = formatSignal(w.signalId, value?.value, settings.units)
    val (lo, hi) = displayRange(w.signalId, settings.units, w.min, w.max)
    fun disp(v: Double?) = v?.let { convertForDisplay(w.signalId, it, settings.units) }
    val model = GaugeModel(signalName(w.signalId), f.text, f.unit, f.value, lo, hi, disp(w.warnLow), disp(w.warnHigh),
        stale = value == null || System.currentTimeMillis() - value.timestampMs > 5_000)
    val border = if (model.alarm) AppTheme.status.danger else null
    AppCard(padding = PaddingValues(10.dp), border = border) {
        when (w.type) {
            GaugeType.ANALOG -> AnalogGauge(model)
            GaugeType.DIGITAL -> DigitalGauge(model)
            GaugeType.TEMPERATURE -> RingGauge(model, gradient = listOf(AppTheme.status.info, AppTheme.status.ok, AppTheme.status.warning, AppTheme.status.danger))
            GaugeType.PRESSURE -> RingGauge(model, gradient = listOf(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.primary))
            GaugeType.ROUND -> RingGauge(model)
            GaugeType.BAR -> BarGauge(model)
            GaugeType.NUMBER -> NumberTile(model)
            GaugeType.SPARKLINE -> {
                val tick by vm.tick.collectAsStateWithLifecycle()
                val pts = remember(tick, settings.units) {
                    val (_, v) = vm.history(w.signalId)?.snapshot(System.currentTimeMillis() - 30_000) ?: (LongArray(0) to DoubleArray(0))
                    DoubleArray(v.size) { convertForDisplay(w.signalId, v[it], settings.units) }
                }
                SparklineTile(model, pts)
            }
        }
        if (def == null) Text(stringResource(R.string.dash_unknown_signal, w.signalId), style = MaterialTheme.typography.bodySmall, color = AppTheme.status.danger)
        if (editing) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { onMove(-1) }, Modifier.size(36.dp)) { Icon(Icons.Outlined.ArrowBack, stringResource(R.string.move_back), Modifier.size(18.dp)) }
                IconButton(onClick = onResize, Modifier.size(36.dp)) { Icon(Icons.Outlined.OpenInFull, stringResource(R.string.resize), Modifier.size(18.dp)) }
                IconButton(onClick = onEdit, Modifier.size(36.dp)) { Icon(Icons.Outlined.Settings, stringResource(R.string.edit), Modifier.size(18.dp)) }
                IconButton(onClick = onDelete, Modifier.size(36.dp)) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete), Modifier.size(18.dp), tint = AppTheme.status.danger) }
                IconButton(onClick = { onMove(1) }, Modifier.size(36.dp)) { Icon(Icons.Outlined.ArrowForward, stringResource(R.string.move_forward), Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun gaugeTypeName(t: GaugeType) = stringResource(when (t) {
    GaugeType.ANALOG -> R.string.gauge_analog
    GaugeType.DIGITAL -> R.string.gauge_digital
    GaugeType.TEMPERATURE -> R.string.gauge_temperature
    GaugeType.PRESSURE -> R.string.gauge_pressure
    GaugeType.ROUND -> R.string.gauge_round
    GaugeType.BAR -> R.string.gauge_bar
    GaugeType.NUMBER -> R.string.gauge_number
    GaugeType.SPARKLINE -> R.string.gauge_sparkline
})

/** Widget hozzáadása / szerkesztése. A határértékeket a felhasználó megjelenítési egységében adja meg, SI-ben tároljuk. */
@Composable
private fun WidgetEditor(initial: DashboardWidget?, signals: List<String>, settings: AppSettings, onDismiss: () -> Unit, onSave: (DashboardWidget) -> Unit) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(initial?.type ?: GaugeType.ROUND) }
    var signal by remember { mutableStateOf(initial?.signalId ?: signals.firstOrNull() ?: "01.0C") }
    var size by remember { mutableStateOf(initial?.size ?: WidgetSize.SMALL) }
    var search by remember { mutableStateOf("") }
    val unit = StandardPids.signal(signal)?.unit
    fun toDisp(v: Double?) = v?.let { if (unit != null) UnitConverter.convert(it, unit, settings.units).value else it }
    fun fromDisp(v: Double?): Double? {
        if (v == null || unit == null) return v
        // Az átváltás lineáris: két pontból visszaszámolható
        val a = UnitConverter.convert(0.0, unit, settings.units).value
        val b = UnitConverter.convert(1.0, unit, settings.units).value
        return (v - a) / (b - a)
    }
    fun numText(v: Double?) = v?.let { String.format(java.util.Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') } ?: ""
    var warnLow by remember { mutableStateOf(numText(toDisp(initial?.warnLow))) }
    var warnHigh by remember { mutableStateOf(numText(toDisp(initial?.warnHigh))) }
    var min by remember { mutableStateOf(numText(toDisp(initial?.min))) }
    var max by remember { mutableStateOf(numText(toDisp(initial?.max))) }
    fun parse(s: String) = s.replace(',', '.').trim().toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.dash_add_widget else R.string.dash_edit_widget)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                item {
                    Text(stringResource(R.string.dash_widget_type), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GaugeType.entries.forEach { t -> FilterChip(type == t, { type = t }, { Text(gaugeTypeName(t)) }) }
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.dash_widget_size), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WidgetSize.entries.forEach { s -> FilterChip(size == s, { size = s }, { Text(stringResource(when (s) {
                            WidgetSize.SMALL -> R.string.size_small; WidgetSize.WIDE -> R.string.size_wide; WidgetSize.FULL -> R.string.size_full })) }) }
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.dash_widget_signal) + ": " + signalName(signal), style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(search, { search = it }, placeholder = { Text(stringResource(R.string.search_pid)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                val filtered = signals.filter { search.isBlank() || Labels.signalName(context, it).contains(search, true) }.take(40)
                items(filtered) { id ->
                    TextButton(onClick = { signal = id }, modifier = Modifier.fillMaxWidth()) {
                        Text(Labels.signalName(context, id), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (id == signal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        if (id == signal) Icon(Icons.Outlined.Check, null)
                    }
                }
                item {
                    val u = formatSignal(signal, 0.0, settings.units).unit
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.dash_thresholds, u), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(warnLow, { warnLow = it }, label = { Text(stringResource(R.string.warn_low)) }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(warnHigh, { warnHigh = it }, label = { Text(stringResource(R.string.warn_high)) }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(min, { min = it }, label = { Text(stringResource(R.string.scale_min)) }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(max, { max = it }, label = { Text(stringResource(R.string.scale_max)) }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave((initial ?: DashboardWidget(type = type, signalId = signal)).copy(
                    type = type, signalId = signal, size = size,
                    warnLow = fromDisp(parse(warnLow)), warnHigh = fromDisp(parse(warnHigh)),
                    min = fromDisp(parse(min)), max = fromDisp(parse(max))
                ))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
