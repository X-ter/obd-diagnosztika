package hu.obddiag.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hu.obddiag.app.ui.theme.AppTheme
import hu.obddiag.core.connection.ConnectionState

enum class IndicatorPhase { IDLE, WORKING, PARTIAL, CONNECTED, ERROR }

fun ConnectionState.phase(): IndicatorPhase = when (this) {
    ConnectionState.Disconnected -> IndicatorPhase.IDLE
    is ConnectionState.ConnectingAdapter, is ConnectionState.InitializingAdapter,
    is ConnectionState.ConnectingVehicle, is ConnectionState.Reconnecting -> IndicatorPhase.WORKING
    is ConnectionState.AdapterOnly -> IndicatorPhase.PARTIAL
    is ConnectionState.Connected -> IndicatorPhase.CONNECTED
    is ConnectionState.Failed -> IndicatorPhase.ERROR
}

/**
 * Animált kapcsolatjelző: kapcsolódás közben forgó ív, kapcsolódva lassan „lélegző” hullámok,
 * hiba esetén statikus piros gyűrű. Az animációk visszafogottak.
 */
@Composable
fun ConnectionIndicator(state: ConnectionState, modifier: Modifier = Modifier, size: Dp = 112.dp) {
    val phase = state.phase()
    val s = AppTheme.status
    val target = when (phase) {
        IndicatorPhase.IDLE -> Color(0xFF6B7682)
        IndicatorPhase.WORKING -> s.info
        IndicatorPhase.PARTIAL -> s.warning
        IndicatorPhase.CONNECTED -> s.ok
        IndicatorPhase.ERROR -> s.danger
    }
    val color by animateColorAsState(target, tween(500), label = "color")
    val transition = rememberInfiniteTransition(label = "indicator")
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "rot")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "pulse")
    val track = s.gaugeTrack

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.045f
            val r = this.size.minDimension / 2 * 0.70f
            if (phase == IndicatorPhase.CONNECTED || phase == IndicatorPhase.PARTIAL) {
                for (k in 0..1) {
                    val p = (pulse + k * 0.5f) % 1f
                    drawCircle(color.copy(alpha = (1f - p) * 0.35f), radius = r + (this.size.minDimension / 2 - r) * p, style = Stroke(stroke * 0.6f))
                }
            }
            drawCircle(track, radius = r, style = Stroke(stroke))
            if (phase == IndicatorPhase.WORKING) {
                drawArc(color, startAngle = rotation, sweepAngle = 100f, useCenter = false,
                    topLeft = center.copy(x = center.x - r, y = center.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
            } else {
                drawCircle(color, radius = r, style = Stroke(stroke))
            }
            drawCircle(color.copy(alpha = 0.10f), radius = r - stroke)
        }
        val icon = when (phase) {
            IndicatorPhase.IDLE -> Icons.Outlined.LinkOff
            IndicatorPhase.WORKING -> Icons.Outlined.Sensors
            IndicatorPhase.PARTIAL -> Icons.Outlined.Sensors
            IndicatorPhase.CONNECTED -> Icons.Outlined.DirectionsCar
            IndicatorPhase.ERROR -> Icons.Outlined.SyncProblem
        }
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.30f))
    }
}
