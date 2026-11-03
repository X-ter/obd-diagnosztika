package hu.obddiag.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import hu.obddiag.app.AppContainer
import hu.obddiag.app.R
import hu.obddiag.app.data.AppSettings
import hu.obddiag.app.ui.components.AppCard
import hu.obddiag.app.ui.components.EmptyState
import hu.obddiag.app.ui.components.ScreenScaffold
import hu.obddiag.app.ui.components.StatusPill
import hu.obddiag.app.ui.nav.appViewModel
import hu.obddiag.app.ui.theme.AppTheme
import hu.obddiag.core.connection.ConnectionState
import hu.obddiag.data.VehicleEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VehiclesViewModel(private val c: AppContainer) : ViewModel() {
    val vehicles = c.vehicles.vehicles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val connection = c.connection.state
    val vinMessage = MutableStateFlow<String?>(null)

    fun save(v: VehicleEntity) = viewModelScope.launch { val id = c.vehicles.save(v); if (settings.value.activeVehicleId == null) c.settings.setActiveVehicle(id) }
    fun delete(v: VehicleEntity) = viewModelScope.launch { c.vehicles.delete(v) }
    fun activate(id: Long) = viewModelScope.launch { c.settings.setActiveVehicle(id) }
    fun readVin(onVin: (String?) -> Unit) = viewModelScope.launch {
        val vin = runCatching { c.connection.client.value?.readVin() }.getOrNull()
        onVin(vin)
    }
}

@Composable
fun VehiclesScreen(nav: NavController) {
    val vm = appViewModel { VehiclesViewModel(it) }
    val vehicles by vm.vehicles.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<VehicleEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<VehicleEntity?>(null) }

    ScreenScaffold(
        title = stringResource(R.string.vehicles_title), onBack = { nav.popBackStack() },
        actions = { IconButton(onClick = { editing = VehicleEntity(make = "", model = "", year = null) }) { Icon(Icons.Outlined.Add, stringResource(R.string.vehicle_add)) } }
    ) {
        if (vehicles.isEmpty()) {
            EmptyState(Icons.Outlined.DirectionsCar, stringResource(R.string.vehicles_empty_title), stringResource(R.string.vehicles_empty_message)) {
                TextButton(onClick = { editing = VehicleEntity(make = "", model = "", year = null) }) { Text(stringResource(R.string.vehicle_add)) }
            }
            return@ScreenScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vehicles, key = { it.id }) { v ->
                val active = v.id == settings.activeVehicleId
                AppCard(onClick = { vm.activate(v.id) }, border = if (active) MaterialTheme.colorScheme.primary else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.DirectionsCar, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.displayName.ifBlank { stringResource(R.string.vehicle_unnamed) }, style = MaterialTheme.typography.titleMedium)
                            val details = listOf(v.engine, v.engineCode).filter { it.isNotBlank() }.joinToString(" · ")
                            if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            v.vin?.let { Text("VIN: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (v.notes.isNotBlank()) Text(v.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        if (active) StatusPill(stringResource(R.string.active), AppTheme.status.ok, icon = Icons.Outlined.CheckCircle)
                        IconButton(onClick = { editing = v }) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) }
                        IconButton(onClick = { confirmDelete = v }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }

    editing?.let { v ->
        var make by remember(v) { mutableStateOf(v.make) }
        var model by remember(v) { mutableStateOf(v.model) }
        var year by remember(v) { mutableStateOf(v.year?.toString() ?: "") }
        var engine by remember(v) { mutableStateOf(v.engine) }
        var engineCode by remember(v) { mutableStateOf(v.engineCode) }
        var vin by remember(v) { mutableStateOf(v.vin ?: "") }
        var notes by remember(v) { mutableStateOf(v.notes) }
        var vinNote by remember(v) { mutableStateOf<String?>(null) }
        val vinFail = stringResource(R.string.vin_read_failed)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (v.id == 0L) R.string.vehicle_add else R.string.vehicle_edit)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { OutlinedTextField(make, { make = it }, label = { Text(stringResource(R.string.vehicle_make)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.vehicle_model)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.vehicle_year)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(engine, { engine = it }, label = { Text(stringResource(R.string.vehicle_engine)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(engineCode, { engineCode = it }, label = { Text(stringResource(R.string.vehicle_engine_code)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item {
                        OutlinedTextField(vin, { vin = it.uppercase().take(17) }, label = { Text("VIN") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                    }
                    item {
                        OutlinedButton(enabled = conn is ConnectionState.Connected, onClick = {
                            vm.readVin { r -> if (r != null) { vin = r; vinNote = null } else vinNote = vinFail }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.vin_read))
                        }
                        vinNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppTheme.status.warning) }
                    }
                    item { OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.notes)) }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.save(v.copy(make = make.trim(), model = model.trim(), year = year.toIntOrNull(), engine = engine.trim(),
                        engineCode = engineCode.trim(), vin = vin.trim().ifBlank { null }, notes = notes.trim()))
                    editing = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    confirmDelete?.let { v ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.vehicle_delete_title)) },
            text = { Text(stringResource(R.string.vehicle_delete_text)) },
            confirmButton = { TextButton(onClick = { vm.delete(v); confirmDelete = null }) { Text(stringResource(R.string.delete), color = AppTheme.status.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}
