package hu.obddiag.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** Az állapotjelző színek, amelyek a Material sémában nem szerepelnek. */
@Immutable
data class StatusColors(
    val ok: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val simulated: Color,
    val chartPalette: List<Color>,
    val gaugeTrack: Color
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF35D0C0),
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0E3B37),
    onPrimaryContainer = Color(0xFFA6F2E8),
    secondary = Color(0xFF5AA9FF),
    onSecondary = Color(0xFF001D36),
    secondaryContainer = Color(0xFF12304D),
    onSecondaryContainer = Color(0xFFCDE5FF),
    tertiary = Color(0xFFB7A6FF),
    background = Color(0xFF0B0E12),
    onBackground = Color(0xFFE3E7EC),
    surface = Color(0xFF0B0E12),
    onSurface = Color(0xFFE3E7EC),
    surfaceVariant = Color(0xFF1A2027),
    onSurfaceVariant = Color(0xFF9AA5B1),
    surfaceContainerLowest = Color(0xFF080A0D),
    surfaceContainerLow = Color(0xFF11151A),
    surfaceContainer = Color(0xFF151A20),
    surfaceContainerHigh = Color(0xFF1B2128),
    surfaceContainerHighest = Color(0xFF222A32),
    outline = Color(0xFF3A444F),
    outlineVariant = Color(0xFF262E37),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0000),
    errorContainer = Color(0xFF4A1416),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00897B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6F1EC),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF1E6FD9),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E6FF),
    onSecondaryContainer = Color(0xFF001B3D),
    background = Color(0xFFF5F7F9),
    onBackground = Color(0xFF14181D),
    surface = Color(0xFFF5F7F9),
    onSurface = Color(0xFF14181D),
    surfaceVariant = Color(0xFFE4E9EE),
    onSurfaceVariant = Color(0xFF55606B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFCFD),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEFF2F5),
    surfaceContainerHighest = Color(0xFFE6EAEE),
    outline = Color(0xFFB8C1CA),
    outlineVariant = Color(0xFFDCE2E8),
    error = Color(0xFFD32F2F),
)

private val DarkStatus = StatusColors(
    ok = Color(0xFF3DDC84), warning = Color(0xFFFFB547), danger = Color(0xFFFF5A5F), info = Color(0xFF5AA9FF),
    simulated = Color(0xFFB7A6FF),
    chartPalette = listOf(Color(0xFF35D0C0), Color(0xFF5AA9FF), Color(0xFFFFB547), Color(0xFFFF6FAE), Color(0xFFB7A6FF), Color(0xFF9BE15D)),
    gaugeTrack = Color(0xFF222A32)
)

private val LightStatus = StatusColors(
    ok = Color(0xFF1E9E57), warning = Color(0xFFC77700), danger = Color(0xFFD32F2F), info = Color(0xFF1E6FD9),
    simulated = Color(0xFF6A4FD6),
    chartPalette = listOf(Color(0xFF00897B), Color(0xFF1E6FD9), Color(0xFFC77700), Color(0xFFC2185B), Color(0xFF6A4FD6), Color(0xFF558B2F)),
    gaugeTrack = Color(0xFFE1E6EB)
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val Base = Typography()
private val AppTypography = Base.copy(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
)

/** Táblázatos (azonos szélességű) számjegyek a műszerértékekhez: nem „ugrál” a szám frissítéskor. */
object ValueText {
    val huge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp)
    val large = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val medium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val small = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    val mono = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
}

@Composable
fun ObdTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val scheme: ColorScheme = if (dark) DarkScheme else LightScheme
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object AppTheme {
    val status: StatusColors @Composable get() = LocalStatusColors.current
}
