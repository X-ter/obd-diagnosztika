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
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import hu.obddiag.app.data.MfrState
import hu.obddiag.app.ui.Labels
import hu.obddiag.app.ui.components.AppCard
import hu.obddiag.app.ui.components.EmptyState
import hu.obddiag.app.ui.components.ErrorBanner
import hu.obddiag.app.ui.components.ScreenScaffold
import hu.obddiag.app.ui.components.SectionTitle
import hu.obddiag.app.ui.components.StatusPill
import hu.obddiag.app.ui.nav.Routes
import hu.obddiag.app.ui.nav.appViewModel
import hu.obddiag.app.ui.nav.navigateTab
import hu.obddiag.app.ui.theme.AppTheme
import hu.obddiag.app.ui.theme.ValueText
import hu.obddiag.core.connection.ConnectionState
import hu.obddiag.core.mfr.MfrDecoder
import hu.obddiag.core.mfr.MfrDetection
import hu.obddiag.core.mfr.MfrReadResult
import hu.obddiag.core.mfr.MfrRegistry
import hu.obddiag.core.mfr.MfrSignals
import hu.obddiag.core.mfr.MfrValue
import kotlinx.coroutines.launch
import java.util.Locale

class MfrViewModel(val c: AppContainer) : ViewModel() {
    val state = c.mfr.state
    val connection = c.connection.state
    val registry = c.connection.mfr

    /** Egy vezérlő nem élő (azonosító, konfigurációs, számláló) adatainak egyszeri beolvasása. */
    val staticData = mutableStateMapOf<String, List<Pair<String, String>>>()
    var staticLoading by mutableStateOf<String?>(null)
        private set

    fun identify(group: hu.obddiag.core.ident.BrandGroup? = c.mfr.userGroup) = viewModelScope.launch { c.mfr.recognize(group) }
    val supportedGroups get() = c.mfr.supportedGroups
    var otherBrandChosen by mutableStateOf(false)

    /** Vezérlőnként a kiolvasott gyártói hibakódok (vagy hibaüzenet). */
    val dtcs = mutableStateMapOf<String, hu.obddiag.core.mfr.MfrDtcResult>()
    var dtcBusy by mutableStateOf<String?>(null)
        private set
    var dtcMessage = mutableStateMapOf<String, String>()

    fun readDtcs(list: List<MfrDetection.Identified>) {
        if (dtcBusy != null || list.isEmpty()) return
        viewModelScope.launch {
            for (d in list) {
                dtcBusy = d.ecu.key
                dtcMessage.remove(d.ecu.key)
                dtcs[d.ecu.key] = try { c.connection.readManufacturerDtcs(d.ecu) } catch (e: Exception) {
                    hu.obddiag.core.mfr.MfrDtcResult.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
            dtcBusy = null
        }
    }

    fun clearDtcs(d: MfrDetection.Identified, okText: String, failFmt: String) {
        if (dtcBusy != null) return
        dtcBusy = d.ecu.key
        viewModelScope.launch {
            val r = try { c.connection.clearManufacturerDtcs(d.ecu) } catch (e: Exception) { MfrReadResult.Failed(e.message ?: "hiba") }
            dtcMessage[d.ecu.key] = when (r) {
                is MfrReadResult.Ok -> okText
                is MfrReadResult.Negative -> failFmt.format("NRC 0x%02X".format(r.nrc))
                MfrReadResult.NoResponse -> failFmt.format("nincs válasz")
                is MfrReadResult.Failed -> failFmt.format(r.reason)
            }
            if (r is MfrReadResult.Ok) kotlinx.coroutines.delay(1_500)
            dtcs[d.ecu.key] = try { c.connection.readManufacturerDtcs(d.ecu) } catch (e: Exception) {
                hu.obddiag.core.mfr.MfrDtcResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            dtcBusy = null
        }
    }

    /** Megosztható azonosítási jelentés: címenként a nyers válaszok és a kiolvasott cikkszámok. */
    fun report(rec: hu.obddiag.core.ident.VehicleRecognition): String = buildString {
        val r = rec.manufacturer ?: hu.obddiag.core.mfr.MfrIdentification(emptyList(), 0, true)
        val conn = c.connection.state.value as? ConnectionState.Connected
        append("OBD Diagnosztika ").append(hu.obddiag.app.BuildConfig.VERSION_NAME).append(" – jármű- és ECU-felismerési jelentés\n")
        append("Idő: ").append(java.time.Instant.now()).append('\n')
        c.connection.mfr.value?.source?.let { append("Adatbázis: ").append(it.name).append(' ').append(it.commit.take(8)).append('\n') }
        conn?.let {
            append("Adapter: ").append(it.adapter.elmVersion).append(it.adapter.stnVersion?.let { s -> " / $s" } ?: "")
            append(" | Protokoll: ").append(it.vehicle.protocol.displayName).append(" | ECU-k: ").append(it.vehicle.ecus.joinToString()).append('\n')
            if (it.simulated) append("SZIMULÁLT KAPCSOLAT\n")
        }
        append('\n').append(rec.report().substringBefore("== Felismerési napló ==")).append('\n')
        append("Flow control: ").append(if (r.flowControlSupported) "OK" else "NEM támogatott").append('\n')
        r.error?.let { append("Hiba: ").append(it).append('\n') }
        append("\n== Gyártói profilok ==\n")
        for (d in r.detections) {
            append("%03X→%03X ".format(d.requestId, d.responseId))
            when (d) {
                is MfrDetection.Identified -> append("AZONOSÍTVA ${d.ecu.key} változat=${d.variant} cikkszám=${d.partNumber}")
                is MfrDetection.Ambiguous -> append("NEM EGYÉRTELMŰ cikkszám=${d.partNumber} jelöltek=${d.candidates.joinToString()}")
                is MfrDetection.Unknown -> append("ISMERETLEN cikkszám(ok)=${d.partNumbers.joinToString().ifEmpty { "–" }} (${d.nodes.joinToString()})")
            }
            append('\n')
        }
        if (dtcs.isNotEmpty()) {
            append("\n== Gyártói hibakódok ==\n")
            for ((k, v) in dtcs) {
                append(k).append(": ")
                when (v) {
                    is hu.obddiag.core.mfr.MfrDtcResult.Ok -> append(v.dtcs.joinToString { "${it.code}${it.failureType?.let { f -> "-%02X".format(f) } ?: ""} (0x%02X)".format(it.status) }.ifEmpty { "nincs" })
                    else -> append(v.toString())
                }
                append('\n')
            }
        }
        val log = c.connection.mfrReadLog
        if (log.isNotEmpty()) {
            append("\n== Gyártói olvasások (utolsó ").append(log.size).append(") ==\n")
            log.forEach { append(it).append('\n') }
        }
        append("\n== Felismerési napló ==\n")
        append(rec.report().substringAfter("== Felismerési napló ==\n"))
    }

    fun readStatic(det: MfrDetection.Identified, onUnsupported: String) {
        if (staticLoading != null) return
        staticLoading = det.ecu.key
        viewModelScope.launch {
            val items = det.ecu.items.filter { it.kind != "live" && it.paramsFor(det.variant) != null }
            val res = try { c.connection.readManufacturerOnce(det.ecu, items) } catch (e: Exception) { emptyMap() }
            val lang = MfrSignals.language
            val rows = mutableListOf<Pair<String, String>>()
            var unsupported = 0
            for (item in items) {
                val r = res[item.id]
                if (r !is MfrReadResult.Ok) { unsupported++; continue }
                for (p in item.paramsFor(det.variant).orEmpty()) {
                    val label = MfrRegistry.labelOf(item, p).get(lang)
                    val text = when (val v = MfrDecoder.decode(p, r.payload)) {
                        is MfrValue.Num -> String.format(Locale.getDefault(), "%.${MfrRegistry.decimalsFor(p.factor)}f", v.value) + (p.unit?.let { " $it" } ?: "")
                        is MfrValue.Label -> v.text.get(lang)
                        is MfrValue.Flag -> if (v.on) "1" else "0"
                        is MfrValue.Text -> v.text
                        MfrValue.Invalid -> continue
                    }
                    rows += label to text
                }
            }
            if (unsupported > 0) rows += "" to onUnsupported.format(unsupported)
            staticData[det.ecu.key] = rows
            staticLoading = null
        }
    }
}

@Composable
private fun nodeName(node: String): String = Labels.get(LocalContext.current, "node_" + node.lowercase(), node)

@Composable
fun MfrScreen(nav: NavController) {
    val vm = appViewModel { MfrViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val registry by vm.registry.collectAsStateWithLifecycle()
    val unsupportedFmt = stringResource(R.string.mfr_static_unsupported)

    ScreenScaffold(title = stringResource(R.string.mfr_title), subtitle = stringResource(R.string.mfr_subtitle), onBack = { nav.popBackStack() }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AppCard {
                    Text(stringResource(R.string.mfr_intro), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.mfr_source_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (conn !is ConnectionState.Connected) {
                item { EmptyState(Icons.Outlined.Memory, stringResource(R.string.not_connected), stringResource(R.string.mfr_connect_hint)) }
                return@LazyColumn
            }
            item {
                val running = state is MfrState.Running || state is MfrState.Loading
                Button(onClick = { vm.identify() }, enabled = !running, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (state is MfrState.Done) R.string.mfr_identify_again else R.string.mfr_identify))
                }
            }
            when (val st = state) {
                MfrState.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                is MfrState.Running -> item {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        val text = when {
                            st.stage == "protocol" -> stringResource(R.string.rec_stage_protocol)
                            st.stage == "vin" -> stringResource(R.string.rec_stage_vin)
                            st.stage == "ecu" -> stringResource(R.string.rec_stage_ecu)
                            st.stage == "brand" -> stringResource(R.string.rec_stage_brand)
                            else -> stringResource(R.string.rec_stage_source, st.stage.substringAfter(':'))
                        }
                        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                is MfrState.Failed -> item { ErrorBanner(stringResource(R.string.mfr_failed), detail = st.message) }
                is MfrState.Done -> {
                    val rec = st.recognition
                    val r = st.result
                    item { VehicleCard(rec) }
                    if (rec.support == hu.obddiag.core.ident.SupportLevel.NEEDS_INPUT) item { BrandInputCard(vm) }
                    item { StandardIdsCard(rec) }
                    item { SourcesCard(rec) }
                    r?.error?.let { e -> item { ErrorBanner(stringResource(R.string.mfr_failed), detail = e) } }
                    if (r != null && !r.flowControlSupported) item { ErrorBanner(stringResource(R.string.mfr_no_flow_control), detail = stringResource(R.string.mfr_no_flow_control_detail)) }
                    item {
                        AppCard {
                            if (r != null) Text(stringResource(R.string.mfr_summary, r.identified.size, r.detections.size, r.probedAddresses), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.rec_log_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val context = LocalContext.current
                            val scope = androidx.compose.runtime.rememberCoroutineScope()
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                scope.launch {
                                    hu.obddiag.app.data.CsvExporter.share(context, "jarmu_felismeres_${hu.obddiag.app.data.CsvExporter.fileStamp()}.txt",
                                        vm.report(rec), "text/plain", context.getString(R.string.share))
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.mfr_share_report))
                            }
                            if (r != null && r.identified.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { vm.readDtcs(r.identified) }, enabled = vm.dtcBusy == null, modifier = Modifier.fillMaxWidth()) {
                                    if (vm.dtcBusy != null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.ReportProblem, null)
                                    Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.mfr_dtc_read_all))
                                }
                            }
                            val live = registry?.signals?.count { it.isLive } ?: 0
                            if (live > 0) {
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.mfr_live_hint, live), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { nav.navigateTab(Routes.LIVE) }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Outlined.ShowChart, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.mfr_open_live))
                                }
                            }
                        }
                    }
                    if (r != null && r.detections.isEmpty() && rec.sources.any { it.ran }) item {
                        EmptyState(Icons.Outlined.HelpOutline, stringResource(R.string.mfr_none_title), stringResource(R.string.mfr_none_message))
                    }
                    if (r != null) items(r.detections, key = { "%03X".format(it.requestId) }) { d -> DetectionCard(d, registry, vm, unsupportedFmt) }
                }
                MfrState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun VehicleCard(rec: hu.obddiag.core.ident.VehicleRecognition) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rec_vehicle), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            val color = when (rec.support) {
                hu.obddiag.core.ident.SupportLevel.MANUFACTURER -> AppTheme.status.ok
                hu.obddiag.core.ident.SupportLevel.STANDARD_ONLY -> AppTheme.status.warning
                hu.obddiag.core.ident.SupportLevel.NEEDS_INPUT -> AppTheme.status.danger
            }
            StatusPill(when (rec.support) {
                hu.obddiag.core.ident.SupportLevel.MANUFACTURER -> stringResource(R.string.mfr_identified)
                hu.obddiag.core.ident.SupportLevel.STANDARD_ONLY -> "OBD-II"
                hu.obddiag.core.ident.SupportLevel.NEEDS_INPUT -> "?"
            }, color)
        }
        Spacer(Modifier.height(6.dp))
        InfoRow(stringResource(R.string.rec_protocol), rec.protocol ?: "–")
        InfoRow(stringResource(R.string.rec_ecus), rec.respondingEcus.joinToString().ifEmpty { "–" })
        InfoRow(stringResource(R.string.rec_vin), (rec.vin?.vin ?: rec.vinRaw ?: stringResource(R.string.rec_vin_none)) + (rec.vinSource?.let { " · $it" } ?: ""))
        rec.vin?.let { v ->
            InfoRow(stringResource(R.string.rec_manufacturer), "${v.wmi} → ${v.manufacturer ?: "?"}")
            if (v.modelYears.isNotEmpty()) InfoRow(stringResource(R.string.rec_model_year), v.modelYears.joinToString(" / "))
        }
        InfoRow(stringResource(R.string.rec_group), rec.group?.let { if (rec.groupFromUser) stringResource(R.string.rec_group_user, it.label) else it.label } ?: "–")
        InfoRow(stringResource(R.string.rec_support), rec.support.label)
    }
}

@Composable
private fun BrandInputCard(vm: MfrViewModel) {
    AppCard {
        Text(stringResource(R.string.rec_need_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.rec_need_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.supportedGroups.forEach { g ->
                OutlinedButton(onClick = { vm.otherBrandChosen = false; vm.identify(g) }) { Text(g.label) }
            }
            OutlinedButton(onClick = { vm.otherBrandChosen = true }) { Text(stringResource(R.string.rec_other_brand)) }
        }
        if (vm.otherBrandChosen) Text(stringResource(R.string.rec_other_brand_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun StandardIdsCard(rec: hu.obddiag.core.ident.VehicleRecognition) {
    AppCard {
        Text(stringResource(R.string.rec_std_ids), style = MaterialTheme.typography.titleSmall)
        if (rec.ecus.isEmpty()) Text("–", style = MaterialTheme.typography.bodySmall)
        rec.ecus.forEach { e ->
            Spacer(Modifier.height(6.dp))
            Text(e.address + (e.diagProtocol?.let { " · $it" } ?: ""), style = ValueText.mono, fontWeight = FontWeight.SemiBold)
            e.ecuName?.let { InfoRow(stringResource(R.string.rec_ident_name), it) }
            if (e.calibrationIds.isNotEmpty()) InfoRow("CALID", e.calibrationIds.joinToString(" / "))
            if (e.cvns.isNotEmpty()) InfoRow("CVN", e.cvns.joinToString(" / "))
            e.identifiers.forEach { (k, v) -> InfoRow(k, v) }
            if (e.ecuName == null && e.calibrationIds.isEmpty() && e.identifiers.isEmpty())
                Text(stringResource(R.string.rec_std_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SourcesCard(rec: hu.obddiag.core.ident.VehicleRecognition) {
    AppCard {
        Text(stringResource(R.string.rec_sources), style = MaterialTheme.typography.titleSmall)
        rec.sources.forEach { s ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.sourceName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                StatusPill(stringResource(if (s.ran) R.string.rec_source_ran else R.string.rec_source_skipped),
                    if (s.ran) AppTheme.status.ok else MaterialTheme.colorScheme.outline)
            }
            Text(s.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DtcSection(d: MfrDetection.Identified, vm: MfrViewModel) {
    var confirm by remember { mutableStateOf(false) }
    val busy = vm.dtcBusy == d.ecu.key
    val okText = stringResource(R.string.mfr_dtc_cleared)
    val failFmt = stringResource(R.string.mfr_dtc_clear_failed)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.readDtcs(listOf(d)) }, enabled = vm.dtcBusy == null, modifier = Modifier.weight(1f)) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.ReportProblem, null)
            Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.mfr_dtc_read))
        }
        OutlinedButton(onClick = { confirm = true }, enabled = vm.dtcBusy == null, modifier = Modifier.weight(1f),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.status.danger)) {
            Icon(Icons.Outlined.DeleteSweep, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.mfr_dtc_clear))
        }
    }
    vm.dtcMessage[d.ecu.key]?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
    when (val res = vm.dtcs[d.ecu.key]) {
        null -> Unit
        is hu.obddiag.core.mfr.MfrDtcResult.Ok -> Column(Modifier.padding(top = 6.dp)) {
            if (res.dtcs.isEmpty()) Text(stringResource(R.string.mfr_dtc_none), style = MaterialTheme.typography.bodySmall, color = AppTheme.status.ok)
            else Text(stringResource(R.string.mfr_dtc_count, res.dtcs.size), style = MaterialTheme.typography.labelLarge)
            val lang = MfrSignals.language
            res.dtcs.forEach { dtc ->
                val info = d.ecu.dtcInfo[dtc.raw]
                val status = when {
                    dtc.activeNow == true -> stringResource(R.string.mfr_dtc_status_active)
                    dtc.confirmed == true -> stringResource(R.string.mfr_dtc_status_stored)
                    else -> stringResource(R.string.mfr_dtc_status_raw, dtc.status)
                }
                Column(Modifier.padding(top = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(dtc.code + (dtc.failureType?.let { "-%02X".format(it) } ?: ""), style = ValueText.mono, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        StatusPill(status, if (dtc.activeNow == true) AppTheme.status.danger else AppTheme.status.warning)
                    }
                    Text(info?.text?.get(lang) ?: stringResource(R.string.mfr_dtc_unknown_text), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        is hu.obddiag.core.mfr.MfrDtcResult.Negative -> Text(stringResource(R.string.mfr_dtc_nrc, res.nrc), style = MaterialTheme.typography.bodySmall, color = AppTheme.status.warning)
        hu.obddiag.core.mfr.MfrDtcResult.NoResponse -> Text(stringResource(R.string.mfr_dtc_no_response), style = MaterialTheme.typography.bodySmall, color = AppTheme.status.warning)
        is hu.obddiag.core.mfr.MfrDtcResult.Failed -> Text(res.reason, style = MaterialTheme.typography.bodySmall, color = AppTheme.status.warning)
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Outlined.WarningAmber, null, tint = AppTheme.status.warning) },
            title = { Text(stringResource(R.string.mfr_dtc_clear_title, nodeName(d.ecu.node))) },
            text = { Text(stringResource(R.string.mfr_dtc_clear_text)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirm = false; vm.clearDtcs(d, okText, failFmt) }) {
                    Text(stringResource(R.string.mfr_dtc_clear), color = AppTheme.status.danger)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun DetectionCard(d: MfrDetection, registry: MfrRegistry?, vm: MfrViewModel, unsupportedFmt: String) {
    var expanded by remember { mutableStateOf(false) }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, color) = when (d) {
                is MfrDetection.Identified -> Icons.Outlined.CheckCircle to AppTheme.status.ok
                is MfrDetection.Ambiguous -> Icons.Outlined.WarningAmber to AppTheme.status.warning
                is MfrDetection.Unknown -> Icons.Outlined.HelpOutline to MaterialTheme.colorScheme.outline
            }
            Icon(icon, null, tint = color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val title = if (d is MfrDetection.Identified) nodeName(d.ecu.node) else d.nodes.map { nodeName(it) }.distinct().joinToString(" / ")
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text("%03X → %03X".format(d.requestId, d.responseId), style = ValueText.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(stringResource(when (d) {
                is MfrDetection.Identified -> R.string.mfr_identified
                is MfrDetection.Ambiguous -> R.string.mfr_ambiguous
                is MfrDetection.Unknown -> R.string.mfr_unknown
            }), color)
        }
        Spacer(Modifier.height(8.dp))
        when (d) {
            is MfrDetection.Identified -> {
                InfoRow(stringResource(R.string.mfr_model), d.ecu.model)
                InfoRow(stringResource(R.string.mfr_variant), d.variant)
                InfoRow(stringResource(R.string.mfr_part), d.partNumber)
                InfoRow(stringResource(R.string.mfr_protocol), if (d.ecu.readService == 0x22) "UDS (ISO 14229)" else "KWP2000 (PSA, CAN)")
                val sigs = registry?.signals?.filter { it.ecu === d.ecu }.orEmpty()
                InfoRow(stringResource(R.string.mfr_values), stringResource(R.string.mfr_values_count, sigs.count { it.isLive }, sigs.count { !it.isLive }))
                if (d.ecu.safetyCritical) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.mfr_safety_note), style = MaterialTheme.typography.bodySmall, color = AppTheme.status.warning)
                }
                Spacer(Modifier.height(8.dp))
                DtcSection(d, vm)
                Spacer(Modifier.height(8.dp))
                val loading = vm.staticLoading == d.ecu.key
                OutlinedButton(onClick = { expanded = true; vm.readStatic(d, unsupportedFmt) }, enabled = vm.staticLoading == null, modifier = Modifier.fillMaxWidth()) {
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Memory, null)
                    Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.mfr_read_static))
                }
                AnimatedVisibility(expanded && vm.staticData[d.ecu.key] != null) {
                    Column(Modifier.padding(top = 8.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        vm.staticData[d.ecu.key].orEmpty().forEach { (k, v) ->
                            if (k.isEmpty()) Text(v, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                            else InfoRow(k, v)
                        }
                    }
                }
            }
            is MfrDetection.Ambiguous -> {
                InfoRow(stringResource(R.string.mfr_part), d.partNumber)
                Text(stringResource(R.string.mfr_ambiguous_detail, d.candidates.joinToString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is MfrDetection.Unknown -> {
                if (d.partNumbers.isNotEmpty()) InfoRow(stringResource(R.string.mfr_part), d.partNumbers.joinToString())
                Text(stringResource(R.string.mfr_unknown_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

