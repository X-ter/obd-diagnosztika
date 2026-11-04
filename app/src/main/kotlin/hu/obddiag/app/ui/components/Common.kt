package hu.obddiag.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hu.obddiag.app.R
import hu.obddiag.app.ui.theme.AppTheme
import hu.obddiag.core.pid.ObdUnit
import hu.obddiag.core.pid.StandardPids
import hu.obddiag.core.util.UnitConverter
import hu.obddiag.core.util.UnitPreferences
import java.util.Locale

/** Lekerekített, finoman kiemelt kártya. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    border: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = MaterialTheme.shapes.medium
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val m = modifier.then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
    if (onClick != null) {
        Card(onClick = onClick, modifier = m, shape = shape, colors = colors) { Column(Modifier.padding(padding), content = content) }
    } else {
        Card(modifier = m, shape = shape, colors = colors) { Column(Modifier.padding(padding), content = content) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Kis, színes állapotcímke. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        } else {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** A szimulált adatok jól látható jelölése. */
@Composable
fun SimulatedBadge(modifier: Modifier = Modifier) {
    StatusPill(stringResource(R.string.simulated_badge), AppTheme.status.simulated, modifier, Icons.Outlined.Science)
}

@Composable
fun SimulatedBanner(modifier: Modifier = Modifier) {
    val c = AppTheme.status.simulated
    Row(
        modifier.fillMaxWidth().background(c.copy(alpha = 0.16f)).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Outlined.Science, null, tint = c, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.simulated_banner), style = MaterialTheme.typography.labelMedium, color = c, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier, detail: String? = null, onClick: (() -> Unit)? = null) {
    val c = AppTheme.status.danger
    Surface(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = c.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = c)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Csillogó vázelem a betöltés idejére. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, height: Dp = 16.dp, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "x")
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier.height(height).clip(shape).background(
            Brush.linearGradient(
                listOf(base, hi, base),
                start = androidx.compose.ui.geometry.Offset(x * 1200f - 400f, 0f),
                end = androidx.compose.ui.geometry.Offset(x * 1200f, 0f)
            )
        )
    )
}

@Composable
fun SkeletonList(rows: Int = 5, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(rows) { Skeleton(Modifier.fillMaxWidth(), height = 64.dp, shape = MaterialTheme.shapes.medium) }
    }
}

/** Érték formázása a felhasználó mértékegység-beállításai szerint. */
data class FormattedValue(val text: String, val unit: String, val value: Double)

fun formatSignal(signalId: String, value: Double?, units: UnitPreferences): FormattedValue {
    val def = StandardPids.signal(signalId)
    val unit = def?.unit ?: ObdUnit.NONE
    if (value == null) return FormattedValue("—", if (unit == ObdUnit.CUSTOM) def?.unitText.orEmpty() else UnitConverter.convert(0.0, unit, units).symbol, Double.NaN)
    val c = UnitConverter.convert(value, unit, units)
    val dec = UnitConverter.decimals(unit, def?.decimals ?: 1, units)
    val symbol = if (unit == ObdUnit.CUSTOM) def?.unitText.orEmpty() else c.symbol
    return FormattedValue(String.format(Locale.getDefault(), "%.${dec}f", c.value), symbol, c.value)
}

/** A jel skálája megjelenítési egységben. */
fun displayRange(signalId: String, units: UnitPreferences, minOverride: Double? = null, maxOverride: Double? = null): Pair<Double, Double> {
    val def = StandardPids.signal(signalId) ?: return 0.0 to 100.0
    val lo = UnitConverter.convert(minOverride ?: def.min, def.unit, units).value
    val hi = UnitConverter.convert(maxOverride ?: def.max, def.unit, units).value
    return if (hi > lo) lo to hi else 0.0 to 1.0
}

fun convertForDisplay(signalId: String, value: Double, units: UnitPreferences): Double {
    val def = StandardPids.signal(signalId) ?: return value
    return UnitConverter.convert(value, def.unit, units).value
}
