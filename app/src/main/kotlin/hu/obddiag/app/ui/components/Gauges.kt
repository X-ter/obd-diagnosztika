package hu.obddiag.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.obddiag.app.ui.theme.AppTheme
import hu.obddiag.app.ui.theme.ValueText
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Egy műszer megjelenítéséhez szükséges, már megjelenítési egységre váltott adatok. */
data class GaugeModel(
    val title: String,
    val valueText: String,
    val unit: String,
    /** Megjelenítési egységben; NaN, ha nincs érték. */
    val value: Double,
    val min: Double,
    val max: Double,
    val warnLow: Double? = null,
    val warnHigh: Double? = null,
    val stale: Boolean = false
) {
    val fraction: Float get() = if (value.isNaN() || max <= min) 0f else ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    val alarm: Boolean get() = !value.isNaN() && ((warnHigh != null && value >= warnHigh) || (warnLow != null && value <= warnLow))
    fun fractionOf(v: Double): Float = if (max <= min) 0f else ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun gaugeAccent(m: GaugeModel): Color {
    val s = AppTheme.status
    return when {
        m.value.isNaN() -> MaterialTheme.colorScheme.outline
        m.alarm -> s.danger
        else -> MaterialTheme.colorScheme.primary
    }
}

@Composable
private fun animatedFraction(target: Float): Float {
    val f by animateFloatAsState(target, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow), label = "gauge")
    return f
}

private fun DrawScope.arc(color: Color, start: Float, sweep: Float, stroke: Float, inset: Float) {
    drawArc(color, start, sweep, false, Offset(inset, inset), Size(size.minDimension - inset * 2, size.minDimension - inset * 2), style = Stroke(stroke, cap = StrokeCap.Round))
}

/** Analóg műszer mutatóval és skálaosztással (pl. fordulatszám). */
@Composable
fun AnalogGauge(m: GaugeModel, modifier: Modifier = Modifier) {
    val accent = gaugeAccent(m)
    val track = AppTheme.status.gaugeTrack
    val danger = AppTheme.status.danger
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val f = animatedFraction(m.fraction)
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1.25f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val d = min(size.width, size.height * 1.2f)
            val stroke = d * 0.06f
            val start = 150f; val sweep = 240f
            val left = (size.width - d) / 2
            val box = Size(d - stroke, d - stroke)
            val tl = Offset(left + stroke / 2, stroke / 2)
            drawArc(track, start, sweep, false, tl, box, style = Stroke(stroke, cap = StrokeCap.Round))
            m.warnHigh?.let { wh ->
                val wf = m.fractionOf(wh)
                drawArc(danger.copy(alpha = 0.35f), start + sweep * wf, sweep * (1 - wf), false, tl, box, style = Stroke(stroke, cap = StrokeCap.Butt))
            }
            drawArc(Brush.sweepGradient(listOf(accent.copy(alpha = 0.5f), accent), center = Offset(left + d / 2, d / 2)),
                start, sweep * f, false, tl, box, style = Stroke(stroke, cap = StrokeCap.Round))
            val c = Offset(left + d / 2, d / 2)
            val rOuter = d / 2 - stroke * 1.6f
            for (i in 0..10) {
                val a = Math.toRadians((start + sweep * i / 10f).toDouble())
                val len = if (i % 2 == 0) stroke * 0.9f else stroke * 0.5f
                drawLine(tick.copy(alpha = 0.7f),
                    Offset(c.x + cos(a).toFloat() * rOuter, c.y + sin(a).toFloat() * rOuter),
                    Offset(c.x + cos(a).toFloat() * (rOuter - len), c.y + sin(a).toFloat() * (rOuter - len)),
                    strokeWidth = stroke * 0.18f, cap = StrokeCap.Round)
            }
            if (!m.value.isNaN()) {
                val a = Math.toRadians((start + sweep * f).toDouble())
                val needle = rOuter - stroke * 0.8f
                drawLine(accent, c, Offset(c.x + cos(a).toFloat() * needle, c.y + sin(a).toFloat() * needle), strokeWidth = stroke * 0.32f, cap = StrokeCap.Round)
            }
            drawCircle(accent, radius = stroke * 0.45f, center = c)
        }
        val wide = maxWidth > 220.dp
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(m.valueText, style = if (wide) ValueText.large else ValueText.medium, color = MaterialTheme.colorScheme.onSurface)
            Text("${m.unit}  ·  ${m.title}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Kör alakú (gyűrűs) műszer középre írt értékkel; hőmérséklethez és nyomáshoz is ezt használjuk más színnel. */
@Composable
fun RingGauge(m: GaugeModel, modifier: Modifier = Modifier, gradient: List<Color>? = null) {
    val accent = gaugeAccent(m)
    val track = AppTheme.status.gaugeTrack
    val f = animatedFraction(m.fraction)
    Box(modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val stroke = size.minDimension * 0.085f
            arc(track, 135f, 270f, stroke, stroke / 2)
            val brush = if (gradient != null && !m.alarm && !m.value.isNaN())
                Brush.sweepGradient(gradient + gradient.first(), center = center) else Brush.linearGradient(listOf(accent, accent))
            drawArc(brush, 135f, 270f * f, false, Offset(stroke / 2, stroke / 2),
                Size(size.minDimension - stroke, size.minDimension - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(m.valueText, style = ValueText.medium, color = if (m.alarm) AppTheme.status.danger else MaterialTheme.colorScheme.onSurface)
            Text(m.unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(m.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp, start = 8.dp, end = 8.dp))
    }
}

/** Nagy digitális kijelző (pl. sebesség) finom skálasávval. */
@Composable
fun DigitalGauge(m: GaugeModel, modifier: Modifier = Modifier) {
    val accent = gaugeAccent(m)
    val f = animatedFraction(m.fraction)
    Column(modifier.fillMaxWidth().padding(4.dp)) {
        Text(m.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(m.valueText, style = ValueText.huge.copy(fontSize = 56.sp), color = if (m.alarm) AppTheme.status.danger else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.padding(4.dp))
            Text(m.unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        }
        SegmentBar(f, accent, segments = 24)
    }
}

@Composable
private fun SegmentBar(fraction: Float, color: Color, segments: Int) {
    val track = AppTheme.status.gaugeTrack
    Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until segments) {
            val on = (i + 0.5f) / segments <= fraction
            Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(2.dp)).let {
                it.then(Modifier.padding(0.dp))
            }) {
                Canvas(Modifier.fillMaxSize()) { drawRect(if (on) color.copy(alpha = 0.4f + 0.6f * i / segments) else track) }
            }
        }
    }
}

/** Lineáris mérősáv. */
@Composable
fun BarGauge(m: GaugeModel, modifier: Modifier = Modifier) {
    val accent = gaugeAccent(m)
    val track = AppTheme.status.gaugeTrack
    val danger = AppTheme.status.danger
    val f = animatedFraction(m.fraction)
    Column(modifier.fillMaxWidth().padding(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(m.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.valueText, style = ValueText.medium, color = if (m.alarm) danger else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.padding(2.dp))
            Text(m.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            if (f > 0f) drawRoundRect(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.55f), accent)), size = Size(max(size.height, size.width * f), size.height), cornerRadius = r)
            m.warnHigh?.let { drawLine(danger, Offset(size.width * m.fractionOf(it), -4f), Offset(size.width * m.fractionOf(it), size.height + 4f), strokeWidth = 3f) }
            m.warnLow?.let { drawLine(danger, Offset(size.width * m.fractionOf(it), -4f), Offset(size.width * m.fractionOf(it), size.height + 4f), strokeWidth = 3f) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(trimNum(m.min), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(trimNum(m.max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Egyszerű numerikus érték. */
@Composable
fun NumberTile(m: GaugeModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(4.dp)) {
        Text(m.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(m.valueText, style = ValueText.large, color = if (m.alarm) AppTheme.status.danger else MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.padding(2.dp))
            Text(m.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

/** Kisméretű élő grafikon az utolsó értékekből. */
@Composable
fun SparklineTile(m: GaugeModel, points: DoubleArray, modifier: Modifier = Modifier) {
    val accent = gaugeAccent(m)
    Column(modifier.fillMaxWidth().padding(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(m.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.valueText, style = ValueText.medium)
            Spacer(Modifier.padding(2.dp))
            Text(m.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            if (points.size < 2) return@Canvas
            val lo = points.min(); val hi = points.max()
            val span = if (hi - lo < 1e-9) 1.0 else hi - lo
            val path = Path()
            points.forEachIndexed { i, v ->
                val x = size.width * i / (points.size - 1)
                val y = size.height - ((v - lo) / span * size.height * 0.9 + size.height * 0.05).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val fill = Path().apply { addPath(path); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent)))
            drawPath(path, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

fun trimNum(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e7) v.toLong().toString() else String.format(java.util.Locale.getDefault(), "%.1f", v)
