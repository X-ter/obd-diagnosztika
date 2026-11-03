@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package hu.obddiag.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import hu.obddiag.app.AppContainer
import hu.obddiag.app.R
import hu.obddiag.app.data.AppSettings
import hu.obddiag.app.transport.AdapterTarget
import hu.obddiag.app.transport.BleTransport
import hu.obddiag.app.ui.components.AppCard
import hu.obddiag.app.ui.components.ConnectionIndicator
import hu.obddiag.app.ui.components.ErrorBanner
import hu.obddiag.app.ui.components.ScreenScaffold
import hu.obddiag.app.ui.components.SectionTitle
import hu.obddiag.app.ui.components.StatusPill
import hu.obddiag.app.ui.nav.appViewModel
import hu.obddiag.app.ui.theme.AppTheme
import hu.obddiag.core.connection.ConnectionState
import hu.obddiag.core.elm.ObdProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AdapterViewModel(private val c: AppContainer) : ViewModel() {
    val state = c.connection.state
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    fun connect(t: AdapterTarget) = c.connect(t)
    fun disconnect() = c.connection.disconnect()
    fun setProtocol(p: ObdProtocol) = viewModelScope.launch { c.settings.setProtocol(p) }
    fun setAutoReconnect(v: Boolean) = viewModelScope.launch { c.settings.setAutoReconnect(v) }
    fun setAutoConnect(v: Boolean) = viewModelScope.launch { c.settings.setAutoConnect(v) }
}

data class FoundDevice(val address: String, val name: String?, val bonded: Boolean, val likelyObd: Boolean, val rssi: Int? = null)

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasPermissions(ctx: Context) = requiredPermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

private fun looksLikeObd(name: String?): Boolean {
    val n = name?.uppercase() ?: return false
    return listOf("OBD", "ELM", "OBDII", "OBDLINK", "VLINK", "V-LINK", "VGATE", "ICAR", "KONNWEI", "VEEPEAK", "CARISTA", "STN", "BAFX", "LELINK", "KIWI").any { n.contains(it) }
}

@SuppressLint("MissingPermission")
private fun bondedDevices(ctx: Context): List<FoundDevice> = try {
    ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
        .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
        .map { FoundDevice(it.address, it.name, true, looksLikeObd(it.name)) }
        .sortedByDescending { it.likelyObd }
} catch (e: SecurityException) { emptyList() }

@Composable
fun AdapterScreen(nav: NavController) {
    val vm = appViewModel { AdapterViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var permitted by remember { mutableStateOf(hasPermissions(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permitted = hasPermissions(context) }
    val btEnabled = remember(permitted) {
        try { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (_: Exception) { false }
    }

    ScreenScaffold(title = stringResource(R.string.adapter_title), onBack = { nav.popBackStack() }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConnectionIndicator(state, size = 72.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(connectionTitle(state), style = MaterialTheme.typography.titleMedium)
                            settings.lastAdapter?.let { Text(stringResource(R.string.adapter_last, it.label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (state !is ConnectionState.Disconnected && state !is ConnectionState.Failed) {
                            OutlinedButton(onClick = { vm.disconnect() }) { Text(stringResource(R.string.disconnect)) }
                        }
                    }
                    (state as? ConnectionState.Failed)?.let { f ->
                        Spacer(Modifier.height(10.dp))
                        ErrorBanner(f.reason, detail = f.detail)
                    }
                    (state as? ConnectionState.AdapterOnly)?.let { a ->
                        Spacer(Modifier.height(10.dp))
                        ErrorBanner(stringResource(R.string.conn_adapter_only), detail = a.reason)
                    }
                    (state as? ConnectionState.Connected)?.let { c ->
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusPill(c.adapter.elmVersion, AppTheme.status.ok)
                            c.adapter.stnVersion?.let { StatusPill(it, AppTheme.status.info) }
                            StatusPill(c.vehicle.protocol.displayName, MaterialTheme.colorScheme.secondary)
                            StatusPill("ECU: " + c.vehicle.ecus.joinToString(), MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            item {
                PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Bluetooth") }, icon = { Icon(Icons.Outlined.Bluetooth, null) })
                    Tab(tab == 1, { tab = 1 }, text = { Text("BLE") }, icon = { Icon(Icons.Outlined.BluetoothSearching, null) })
                    Tab(tab == 2, { tab = 2 }, text = { Text("Wi-Fi") }, icon = { Icon(Icons.Outlined.Wifi, null) })
                    Tab(tab == 3, { tab = 3 }, text = { Text(stringResource(R.string.simulation)) }, icon = { Icon(Icons.Outlined.Science, null) })
                }
            }
            if ((tab == 0 || tab == 1) && !permitted) item {
                AppCard {
                    Text(stringResource(R.string.bt_permission_text), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { launcher.launch(requiredPermissions()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.bt_permission_grant)) }
                }
            } else if ((tab == 0 || tab == 1) && !btEnabled) item {
                ErrorBanner(stringResource(R.string.bt_disabled), detail = stringResource(R.string.bt_disabled_hint))
            } else when (tab) {
                0 -> item { ClassicPanel { d -> vm.connect(AdapterTarget.Classic(d.address, d.name)) } }
                1 -> item { BlePanel { d -> vm.connect(AdapterTarget.Ble(d.address, d.name)) } }
                2 -> item { WifiPanel(settings) { h, p -> vm.connect(AdapterTarget.Wifi(h, p)) } }
                else -> item {
                    AppCard {
                        Text(stringResource(R.string.simulation_text), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { vm.connect(AdapterTarget.Simulation) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.simulation_start))
                        }
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.adapter_options)) }
            item {
                AppCard {
                    ProtocolPicker(settings.protocol) { vm.setProtocol(it) }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.auto_reconnect), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.auto_reconnect_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(settings.autoReconnect, { vm.setAutoReconnect(it) })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.auto_connect), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.auto_connect_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(settings.autoConnectOnStart, { vm.setAutoConnect(it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtocolPicker(current: ObdProtocol, onSelect: (ObdProtocol) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(
            value = current.displayName, onValueChange = {}, readOnly = true,
            label = { Text(stringResource(R.string.protocol)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ObdProtocol.entries.forEach { p ->
                DropdownMenuItem(text = { Text(p.displayName) }, onClick = { onSelect(p); expanded = false })
            }
        }
    }
}

@Composable
private fun DeviceRow(d: FoundDevice, onClick: () -> Unit) {
    AppCard(onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Bluetooth, null, tint = if (d.likelyObd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name ?: stringResource(R.string.unknown_device), style = MaterialTheme.typography.bodyLarge)
                Text(d.address + (d.rssi?.let { "  ·  $it dBm" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (d.likelyObd) StatusPill("OBD", AppTheme.status.ok)
            else if (d.bonded) StatusPill(stringResource(R.string.paired), MaterialTheme.colorScheme.secondary)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ClassicPanel(onPick: (FoundDevice) -> Unit) {
    val context = LocalContext.current
    val bonded = remember { bondedDevices(context) }
    val found = remember { mutableStateListOf<FoundDevice>() }
    var scanning by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (dev != null && found.none { it.address == dev.address } && bonded.none { it.address == dev.address }) {
                            val name = try { dev.name } catch (_: SecurityException) { null }
                            found += FoundDevice(dev.address, name, false, looksLikeObd(name))
                        }
                    }
                    android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
            try { context.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery() } catch (_: Exception) { }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bt_paired_devices), style = MaterialTheme.typography.titleSmall)
        if (bonded.isEmpty()) Text(stringResource(R.string.bt_no_paired), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        bonded.forEach { d -> DeviceRow(d) { onPick(d) } }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bt_nearby), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            OutlinedButton(onClick = {
                found.clear()
                scanning = try { context.getSystemService(BluetoothManager::class.java)?.adapter?.startDiscovery() == true } catch (_: SecurityException) { false }
            }, enabled = !scanning) { Text(stringResource(R.string.scan)) }
        }
        found.forEach { d -> DeviceRow(d) { onPick(d) } }
        Text(stringResource(R.string.bt_pair_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BlePanel(onPick: (FoundDevice) -> Unit) {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<FoundDevice>() }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scanner = remember { try { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner } catch (_: Exception) { null } }
    val callback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val name = try { dev.name ?: result.scanRecord?.deviceName } catch (_: SecurityException) { null }
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
                val likely = looksLikeObd(name) || uuids.any { it in BleTransport.LIKELY_SERVICE_UUIDS }
                val idx = found.indexOfFirst { it.address == dev.address }
                val item = FoundDevice(dev.address, name, false, likely, result.rssi)
                if (idx >= 0) found[idx] = item else found += item
            }
            override fun onScanFailed(errorCode: Int) { scanning = false }
        }
    }
    DisposableEffect(Unit) { onDispose { try { scanner?.stopScan(callback) } catch (_: Exception) { } } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ble_devices), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            OutlinedButton(enabled = !scanning && scanner != null, onClick = {
                found.clear()
                try {
                    scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                    scanning = true
                    scope.launch { delay(12_000); try { scanner?.stopScan(callback) } catch (_: Exception) { }; scanning = false }
                } catch (_: SecurityException) { scanning = false }
            }) { Text(stringResource(R.string.scan)) }
        }
        found.sortedWith(compareByDescending<FoundDevice> { it.likelyObd }.thenByDescending { it.rssi ?: -200 }).forEach { d -> DeviceRow(d) { onPick(d) } }
        if (found.isEmpty()) Text(stringResource(R.string.ble_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WifiPanel(settings: AppSettings, onConnect: (String, Int) -> Unit) {
    var host by remember(settings.wifiHost) { mutableStateOf(settings.wifiHost) }
    var port by remember(settings.wifiPort) { mutableStateOf(settings.wifiPort.toString()) }
    val valid = host.isNotBlank() && port.toIntOrNull()?.let { it in 1..65535 } == true
    AppCard {
        Text(stringResource(R.string.wifi_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(host, { host = it.trim() }, label = { Text(stringResource(R.string.wifi_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.wifi_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Spacer(Modifier.height(10.dp))
        Button(onClick = { onConnect(host, port.toInt()) }, enabled = valid, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.connect)) }
    }
}
