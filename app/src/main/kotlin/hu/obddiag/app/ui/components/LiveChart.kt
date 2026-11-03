package hu.obddiag.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hu.obddiag.app.R
import hu.obddiag.app.ui.theme.ValueText
import androidx.compose.ui.res.stringResource
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Egy görbe adatai már megjelenítési egységben. */
class ChartSeries(
    val id: String,
    val name: String,
    val unit: String,
    val color: Color,
    val times: LongArray,
    val values: DoubleArray,
    val decimals: Int = 1
)

/** A grafikon nézetének állapota: időablak, visszagörgetés, megállítás, Y-skála. */
@Stable
class ChartState(initialWindowMs: Long = 30_000) {
    var windowMs by mutableLongStateOf(initialWindowMs)
    /** Mennyivel nézünk vissza a „most”-hoz (vagy a megállítás pillanatához) képest. */
    var offsetMs by mutableLongStateOf(0L)
    var paused by mutableStateOf(false)
        private set
    var pausedAt by mutableLongStateOf(0L)
        private set
    var autoY by mutableStateOf(true)
    var manualMin by mutableDoubleStateOf(0.0)
    var manualMax by mutableDoubleStateOf(100.0)

    fun pause(now: Long) { paused = true; pausedAt = now }
    fun resume() { paused = false; offsetMs = 0 }
    fun toggle(now: Long) = if (paused) resume() else pause(now)
    fun resetView() { offsetMs = 0; windowMs = 30_000 }

    fun endTime(now: Long): Long = (if (paused) pausedAt else now) - offsetMs

    companion object {
        const val MIN_WINDOW = 5_000L
        const val MAX_WINDOW = 10 * 60_000L
    }
}

private fun niceStep(range: Double, ticks: Int): Double {
    if (range <= 0) return 1.0
    val raw = range / ticks
    val mag = 10.0.pow(floor(log10(raw)))
    val n = raw / mag
    val nice = when { n < 1.5 -> 1.0; n < 3 -> 2.0; n < 7 -> 5.0; else -> 10.0 }
    return nice * mag
}

/**
 * Folyamatosan frissülő, több görbés grafikon.
 * Csippentés = időbeli nagyítás, húzás = visszanézés, dupla koppintás = vissza az élő nézetre.
 * Eltérő mértékegységű görbék esetén mindegyik saját skálán, normalizálva jelenik meg.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveChart(
    series: List<ChartSeries>,
    state: ChartState,
    nowMs: Long,
    modifier: Modifier = Modifier,
    height: Dp = 260.dp
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bg = MaterialTheme.colorScheme.surfaceContainerLow
    val end = state.endTime(nowMs)
    val start = end - state.windowMs
    val sharedUnit = series.map { it.unit }.distinct().size <= 1
    val nowLabel = stringResource(R.string.chart_now)
    val noDataLabel = stringResource(R.string.chart_no_data)

    // Látható tartomány értékei
    data class Visible(val s: ChartSeries, val from: Int, val to: Int, val min: Double, val max: Double)
    val visible = series.map { s ->
        var from = s.times.indexOfFirst { it >= start - 1000 }
        if (from < 0) from = s.times.size
        var to = s.times.indexOfLast { it <= end + 1000 }
        if (to < from) to = from - 1
        var mn = Double.POSITIVE_INFINITY; var mx = Double.NEGATIVE_INFINITY
        for (i in from..to) { val v = s.values[i]; if (v < mn) mn = v; if (v > mx) mx = v }
        Visible(s, from, to, mn, mx)
    }

    val (globalMin, globalMax) = run {
        if (!state.autoY && sharedUnit) state.manualMin to state.manualMax
        else {
            val mn = visible.filter { it.to >= it.from }.minOfOrNull { it.min } ?: 0.0
            val mx = visible.filter { it.to >= it.from }.maxOfOrNull { it.max } ?: 1.0
            val pad = if (mx - mn < 1e-9) maxOf(abs(mx) * 0.1, 1.0) else (mx - mn) * 0.08
            (mn - pad) to (mx + pad)
        }
    }

    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().height(height).clip(MaterialTheme.shapes.small).background(bg)
                .pointerInput(state) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            state.windowMs = (state.windowMs / zoom).toLong().coerceIn(ChartState.MIN_WINDOW, ChartState.MAX_WINDOW)
                        }
                        if (pan.x != 0f) {
                            val msPerPx = state.windowMs / size.width.toFloat()
                            val newOffset = (state.offsetMs + (pan.x * msPerPx)).toLong()
                            if (newOffset > 0 && !state.paused) state.pause(nowMs)
                            state.offsetMs = newOffset.coerceIn(0L, ChartState.MAX_WINDOW * 3)
                        }
                    }
                }
                .pointerInput(state) { detectTapGestures(onDoubleTap = { state.resume() }) }
        ) {
            Canvas(Modifier.fillMaxWidth().height(height).padding(start = 44.dp, end = 8.dp, top = 10.dp, bottom = 22.dp)) {
                val w = size.width; val h = size.height
                val paint = android.graphics.Paint().apply {
                    color = labelColor.toArgb(); textSize = 10.dp.toPx(); isAntiAlias = true
                }
                // Vízszintes rács + Y címkék
                if (sharedUnit) {
                    val step = niceStep(globalMax - globalMin, 4)
                    var v = ceil(globalMin / step) * step
                    while (v <= globalMax) {
                        val y = h - ((v - globalMin) / (globalMax - globalMin) * h).toFloat()
                        drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                        val label = if (step >= 1) String.format(Locale.getDefault(), "%.0f", v) else String.format(Locale.getDefault(), "%.2f", v)
                        paint.textAlign = android.graphics.Paint.Align.RIGHT
                        drawContext.canvas.nativeCanvas.drawText(label, -6.dp.toPx(), y + 4.dp.toPx(), paint)
                        v += step
                    }
                } else {
                    for (i in 0..4) {
                        val y = h * i / 4f
                        drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    }
                }
                // Időtengely
                val tStep = niceStep(state.windowMs / 1000.0, 4).coerceAtLeast(1.0)
                paint.textAlign = android.graphics.Paint.Align.CENTER
                var sec = 0.0
                while (sec <= state.windowMs / 1000.0) {
                    val x = w - (sec * 1000 / state.windowMs * w).toFloat()
                    drawLine(gridColor.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                    val txt = if (state.offsetMs == 0L && !state.paused && sec == 0.0) nowLabel else "-${(sec + state.offsetMs / 1000.0).toInt()} s"
                    drawContext.canvas.nativeCanvas.drawText(txt, x.coerceIn(14.dp.toPx(), w - 14.dp.toPx()), h + 16.dp.toPx(), paint)
                    sec += tStep
                }
                // Görbék
                for (vis in visible) {
                    if (vis.to <= vis.from) continue
                    val lo: Double; val hi: Double
                    if (sharedUnit) { lo = globalMin; hi = globalMax } else {
                        val pad = if (vis.max - vis.min < 1e-9) 1.0 else (vis.max - vis.min) * 0.1
                        lo = vis.min - pad; hi = vis.max + pad
                    }
                    val path = Path()
                    var first = true
                    // Ritkítás: legfeljebb kb. 2 pont / pixel
                    val stride = maxOf(1, (vis.to - vis.from) / (w.toInt() * 2).coerceAtLeast(1))
                    var i = vis.from
                    while (i <= vis.to) {
                        val x = ((vis.s.times[i] - start).toDouble() / state.windowMs * w).toFloat()
                        val y = (h - (vis.s.values[i] - lo) / (hi - lo) * h).toFloat().coerceIn(-2f, h + 2f)
                        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                        i += stride
                    }
                    drawPath(path, vis.s.color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            if (series.isEmpty() || visible.all { it.to < it.from }) {
                Text(noDataLabel, Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodySmall, color = labelColor)
            }
        }
        // Jelmagyarázat aktuális / minimum / maximum értékkel
        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            visible.forEach { v ->
                val cur = if (v.to >= v.from) v.s.values[v.to] else null
                fun f(x: Double?) = if (x == null || x.isInfinite() || x.isNaN()) "—" else String.format(Locale.getDefault(), "%.${v.s.decimals}f", x)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(v.s.color))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(v.s.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                        Text("${f(cur)} ${v.s.unit}   min ${f(v.min)}  max ${f(v.max)}", style = ValueText.mono, color = labelColor)
                    }
                }
            }
        }
    }
}
