package hu.obddiag.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Troubleshoot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import hu.obddiag.app.R
import hu.obddiag.app.ui.components.AppCard
import hu.obddiag.app.ui.components.ScreenScaffold
import hu.obddiag.app.ui.components.SectionTitle
import hu.obddiag.app.ui.components.StatusPill
import hu.obddiag.app.ui.nav.Routes
import hu.obddiag.app.ui.nav.appContainer
import hu.obddiag.app.ui.theme.AppTheme

@Composable
fun MoreScreen(nav: NavController) {
    val recording by appContainer().logging.recording.collectAsStateWithLifecycle()
    ScreenScaffold(title = stringResource(R.string.tab_more)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionTitle(stringResource(R.string.more_diagnostics)) }
            item {
                MoreItem(Icons.Outlined.FiberManualRecord, stringResource(R.string.logging_title), stringResource(R.string.logging_desc)) { nav.navigate(Routes.LOGGING) }
            }
            if (recording != null) item { StatusPill(stringResource(R.string.logging_active), AppTheme.status.danger) }
            item { MoreItem(Icons.Outlined.Memory, stringResource(R.string.mfr_title), stringResource(R.string.mfr_desc)) { nav.navigate(Routes.MFR) } }
            item { MoreItem(Icons.Outlined.Troubleshoot, stringResource(R.string.advanced_title), stringResource(R.string.advanced_desc)) { nav.navigate(Routes.ADVANCED) } }
            item { MoreItem(Icons.Outlined.Terminal, stringResource(R.string.terminal_title), stringResource(R.string.terminal_desc)) { nav.navigate(Routes.TERMINAL) } }
            item { SectionTitle(stringResource(R.string.more_setup)) }
            item { MoreItem(Icons.Outlined.DirectionsCar, stringResource(R.string.vehicles_title), stringResource(R.string.vehicles_desc)) { nav.navigate(Routes.VEHICLES) } }
            item { MoreItem(Icons.Outlined.Bluetooth, stringResource(R.string.adapter_title), stringResource(R.string.adapter_desc)) { nav.navigate(Routes.ADAPTER) } }
            item { MoreItem(Icons.Outlined.ReceiptLong, stringResource(R.string.commlog_title), stringResource(R.string.commlog_desc)) { nav.navigate(Routes.COMMLOG) } }
            item { MoreItem(Icons.Outlined.Settings, stringResource(R.string.settings_title), stringResource(R.string.settings_desc)) { nav.navigate(Routes.SETTINGS) } }
            item { MoreItem(Icons.Outlined.Info, stringResource(R.string.about_title), stringResource(R.string.about_desc)) { nav.navigate(Routes.ABOUT) } }
        }
    }
}

@Composable
fun MoreItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    AppCard(onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
