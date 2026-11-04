@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package hu.obddiag.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import hu.obddiag.app.AppContainer
import hu.obddiag.app.BuildConfig
import hu.obddiag.app.R
import hu.obddiag.app.data.AppSettings
import hu.obddiag.app.data.CsvExporter
import hu.obddiag.app.ui.components.AppCard
import hu.obddiag.app.ui.components.EmptyState
import hu.obddiag.app.ui.components.ScreenScaffold
import hu.obddiag.app.ui.components.SectionTitle
import hu.obddiag.app.ui.components.StatusPill
import hu.obddiag.app.ui.nav.appViewModel
import hu.obddiag.app.ui.theme.AppTheme
import hu.obddiag.app.ui.theme.ThemeMode
import hu.obddiag.app.ui.theme.ValueText
import hu.obddiag.core.connection.ConnectionState
import hu.obddiag.core.util.CommLog
import hu.obddiag.core.util.PressureUnit
import hu.obddiag.core.util.RawCommandGuard
import hu.obddiag.core.util.SpeedUnit
import hu.obddiag.core.util.TemperatureUnit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────── Szakértői terminál ─────────────────────────

class TerminalViewModel(private val c: AppContainer) : ViewModel() {
    data class Line(val text: String, val kind: Int) // 0 = parancs, 1 = válasz, 2 = hiba/info
    val lines = mutableStateListOf<Line>()
    val connection = c.connection.state
    var busy by mutableStateOf(false)
        private set

    /** A terminál ideje alatt az élő lekérdezés szünetel, hogy a válaszok ne keveredjenek. */
    fun enter() = c.live.setPaused("terminal", true)

    fun leave() {
        c.live.setPaused("terminal", false)
        val client = c.connection.client.value ?: return
        c.appScope.launch { runCatching { client.restoreSessionSettings() } }
    }

    fun send(cmd: String, blockedPrefix: String, invalidPrefix: String) {
        val trimmed = cmd.trim()
        when (val v = RawCommandGuard.check(trimmed)) {
            is RawCommandGuard.Verdict.Blocked -> { lines += Line("> $trimmed", 0); lines += Line("$blockedPrefix ${v.reason}", 2); return }
            is RawCommandGuard.Verdict.Invalid -> { lines += Line("> $trimmed", 0); lines += Line("$invalidPrefix ${v.reason}", 2); return }
            RawCommandGuard.Verdict.Allowed -> Unit
        }
        val client = c.connection.client.value ?: run { lines += Line("Nincs kapcsolat az adapterrel.", 2); return }
        lines += Line("> $trimmed", 0)
        busy = true
        viewModelScope.launch {
            val r = runCatching { client.session.sendRaw(trimmed.uppercase(), 5_000) }
            r.onSuccess { raw ->
                val out = raw.replace(">", "").split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (out.isEmpty()) lines += Line("(üres válasz)", 2) else out.forEach { lines += Line(it, 1) }
            }.onFailure { lines += Line("Hiba: ${it.message ?: "időtúllépés"}", 2) }
            busy = false
        }
    }

    fun clear() = lines.clear()
}

@Composable
fun TerminalScreen(nav: NavController) {
    val vm = appViewModel { TerminalViewModel(it) }
    val conn by vm.connection.collectAsStateWithLifecycle()
    var accepted by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val blocked = stringResource(R.string.terminal_blocked)
    val invalid = stringResource(R.string.terminal_invalid)

    DisposableEffect(accepted) {
        if (accepted) vm.enter()
        onDispose { if (accepted) vm.leave() }
    }
    LaunchedEffect(vm.lines.size) { if (vm.lines.isNotEmpty()) listState.animateScrollToItem(vm.lines.lastIndex) }

    ScreenScaffold(title = stringResource(R.string.terminal_title), onBack = { nav.popBackStack() },
        actions = { IconButton(onClick = vm::clear) { Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.clear)) } }) {
        if (conn !is ConnectionState.Connected && conn !is ConnectionState.AdapterOnly) {
            EmptyState(Icons.Outlined.Terminal, stringResource(R.string.not_connected), stringResource(R.string.terminal_connect_hint))
            return@ScreenScaffold
        }
        Column(Modifier.fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.small),
                state = listState, contentPadding = PaddingValues(12.dp)) {
                items(vm.lines) { l ->
                    Text(l.text, style = ValueText.mono, color = when (l.kind) {
                        0 -> MaterialTheme.colorScheme.primary
                        1 -> MaterialTheme.colorScheme.onSurface
                        else -> AppTheme.status.warning
                    })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ATI", "ATRV", "ATDP", "0100", "010C", "0902", "03").forEach { q ->
                    AssistChip(onClick = { vm.send(q, blocked, invalid) }, label = { Text(q) })
                }
            }
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it.uppercase() }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text(stringResource(R.string.terminal_placeholder)) }, textStyle = ValueText.mono,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) { vm.send(input, blocked, invalid); input = "" } }))
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { if (input.isNotBlank()) { vm.send(input, blocked, invalid); input = "" } }, enabled = !vm.busy) {
                    Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.send))
                }
            }
        }
    }

    if (!accepted) {
        AlertDialog(
            onDismissRequest = { nav.popBackStack() },
            icon = { Icon(Icons.Outlined.WarningAmber, null, tint = AppTheme.status.warning) },
            title = { Text(stringResource(R.string.terminal_warning_title)) },
            text = { Text(stringResource(R.string.terminal_warning_text)) },
            confirmButton = { TextButton(onClick = { accepted = true }) { Text(stringResource(R.string.terminal_accept)) } },
            dismissButton = { TextButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

// ───────────────────────── Kommunikációs napló ─────────────────────────

@Composable
fun CommLogScreen(nav: NavController) {
    val c = hu.obddiag.app.ui.nav.appContainer()
    val entries by c.connection.commLog.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    var errorsOnly by rememberSaveable { mutableStateOf(false) }
    val shown = if (errorsOnly) entries.filter { it.direction == CommLog.Direction.ERROR || it.direction == CommLog.Direction.INFO } else entries

    ScreenScaffold(title = stringResource(R.string.commlog_title), onBack = { nav.popBackStack() }, actions = {
        IconButton(onClick = {
            scope.launch {
                val text = entries.joinToString("\n") { "${fmt.format(Date(it.timestampMs))} ${it.direction} ${it.text}" }
                CsvExporter.share(context, "kommunikacio_${CsvExporter.fileStamp()}.txt", text, "text/plain", context.getString(R.string.share))
            }
        }) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
        IconButton(onClick = { c.connection.commLog.clear() }) { Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.clear)) }
    }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.commlog_errors_only), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(errorsOnly, { errorsOnly = it })
            }
            if (shown.isEmpty()) {
                EmptyState(Icons.Outlined.Terminal, stringResource(R.string.commlog_empty_title), stringResource(R.string.commlog_empty_message))
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), reverseLayout = true) {
                    items(shown.asReversed(), key = { it.id }) { e ->
                        val color = when (e.direction) {
                            CommLog.Direction.TX -> MaterialTheme.colorScheme.primary
                            CommLog.Direction.RX -> MaterialTheme.colorScheme.onSurface
                            CommLog.Direction.ERROR -> AppTheme.status.danger
                            CommLog.Direction.INFO -> AppTheme.status.info
                        }
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(fmt.format(Date(e.timestampMs)), style = ValueText.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(when (e.direction) { CommLog.Direction.TX -> "→"; CommLog.Direction.RX -> "←"; CommLog.Direction.ERROR -> "!"; CommLog.Direction.INFO -> "i" },
                                style = ValueText.mono, color = color)
                            Spacer(Modifier.width(8.dp))
                            Text(e.text, style = ValueText.mono, color = color)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Beállítások ─────────────────────────

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    fun theme(t: ThemeMode) = viewModelScope.launch { c.settings.setTheme(t) }
    fun temp(t: TemperatureUnit) = viewModelScope.launch { c.settings.setUnits(settings.value.units.copy(temperature = t)) }
    fun speed(s: SpeedUnit) = viewModelScope.launch { c.settings.setUnits(settings.value.units.copy(speed = s)) }
    fun pressure(p: PressureUnit) = viewModelScope.launch { c.settings.setUnits(settings.value.units.copy(pressure = p)) }
    fun keepScreen(v: Boolean) = viewModelScope.launch { c.settings.setKeepScreenOn(v) }
    fun poll(ms: Long) = viewModelScope.launch { c.settings.setPollInterval(ms) }
    fun commLog(v: Boolean) = viewModelScope.launch { c.settings.setCommLogTraffic(v) }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, label) ->
                androidx.compose.material3.FilterChip(v == selected, { onSelect(v) }, { Text(label) })
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SettingsScreen(nav: NavController) {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    ScreenScaffold(title = stringResource(R.string.settings_title), onBack = { nav.popBackStack() }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionTitle(stringResource(R.string.settings_appearance)) }
            item {
                AppCard {
                    ChoiceRow(stringResource(R.string.settings_theme), listOf(
                        ThemeMode.DARK to stringResource(R.string.theme_dark), ThemeMode.LIGHT to stringResource(R.string.theme_light),
                        ThemeMode.SYSTEM to stringResource(R.string.theme_system)), s.theme) { vm.theme(it) }
                }
            }
            item { SectionTitle(stringResource(R.string.settings_units)) }
            item {
                AppCard {
                    ChoiceRow(stringResource(R.string.unit_temperature), listOf(TemperatureUnit.CELSIUS to "°C", TemperatureUnit.FAHRENHEIT to "°F"), s.units.temperature) { vm.temp(it) }
                    ChoiceRow(stringResource(R.string.unit_speed), listOf(SpeedUnit.KMH to "km/h", SpeedUnit.MPH to "mph"), s.units.speed) { vm.speed(it) }
                    ChoiceRow(stringResource(R.string.unit_pressure), listOf(PressureUnit.KPA to "kPa", PressureUnit.BAR to "bar", PressureUnit.PSI to "psi"), s.units.pressure) { vm.pressure(it) }
                    Text(stringResource(R.string.units_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionTitle(stringResource(R.string.settings_polling)) }
            item {
                AppCard {
                    ChoiceRow(stringResource(R.string.poll_interval), listOf(
                        0L to stringResource(R.string.poll_max), 250L to "250 ms", 500L to "500 ms", 1000L to "1 s", 2000L to "2 s"), s.pollIntervalMs) { vm.poll(it) }
                    Text(stringResource(R.string.poll_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SwitchRow(stringResource(R.string.keep_screen_on), stringResource(R.string.keep_screen_on_desc), s.keepScreenOn) { vm.keepScreen(it) }
                    SwitchRow(stringResource(R.string.commlog_traffic), stringResource(R.string.commlog_traffic_desc), s.commLogTraffic) { vm.commLog(it) }
                }
            }
        }
    }
}

// ───────────────────────── Névjegy / funkcióállapot ─────────────────────────

@Composable
fun AboutScreen(nav: NavController) {
    ScreenScaffold(title = stringResource(R.string.about_title), onBack = { nav.popBackStack() }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AppCard {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { SectionTitle(stringResource(R.string.features_working)) }
            item {
                AppCard {
                    listOf(R.string.f_conn, R.string.f_pids, R.string.f_mfr, R.string.f_dtc, R.string.f_freeze, R.string.f_live, R.string.f_chart, R.string.f_dash, R.string.f_log,
                        R.string.f_vehicles, R.string.f_readiness, R.string.f_mode06, R.string.f_mode09, R.string.f_terminal, R.string.f_sim).forEach {
                        FeatureLine(stringResource(it), AppTheme.status.ok)
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.features_limited)) }
            item {
                AppCard {
                    listOf(R.string.l_manufacturer, R.string.l_mfr_test, R.string.l_dtc_db, R.string.l_stn, R.string.l_mode06_legacy, R.string.l_background).forEach {
                        FeatureLine(stringResource(it), AppTheme.status.warning)
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.about_sources)) }
            item {
                AppCard {
                    Text(stringResource(R.string.about_psa_re), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { Text(stringResource(R.string.about_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun FeatureLine(text: String, color: Color) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        StatusPill("", color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
