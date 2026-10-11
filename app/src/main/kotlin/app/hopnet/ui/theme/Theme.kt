package app.hopnet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCF2EC),
    onPrimaryContainer = Color(0xFF04332F),
    secondary = Color(0xFF4F46E5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E0FF),
    onSecondaryContainer = Color(0xFF1B1660),
    tertiary = Color(0xFFB45309),
    tertiaryContainer = Color(0xFFFDEBC8),
    onTertiaryContainer = Color(0xFF4A2300),
    background = Color(0xFFF6F7F4),
    surface = Color(0xFFF6F7F4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEFF1EC),
    surfaceContainerHigh = Color(0xFFE8EBE5),
    surfaceVariant = Color(0xFFE3E7E1),
    onSurfaceVariant = Color(0xFF4A524D),
    outlineVariant = Color(0xFFD3D9D2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF00201D),
    primaryContainer = Color(0xFF0B3B37),
    onPrimaryContainer = Color(0xFFB4F5EA),
    secondary = Color(0xFFA5A1FF),
    secondaryContainer = Color(0xFF2B2777),
    onSecondaryContainer = Color(0xFFE0E0FF),
    tertiary = Color(0xFFFBBF24),
    tertiaryContainer = Color(0xFF4A3005),
    onTertiaryContainer = Color(0xFFFDEBC8),
    background = Color(0xFF0F1720),
    surface = Color(0xFF0F1720),
    surfaceContainerLowest = Color(0xFF0B1118),
    surfaceContainerLow = Color(0xFF141E28),
    surfaceContainer = Color(0xFF17212C),
    surfaceContainerHigh = Color(0xFF1D2935),
    surfaceVariant = Color(0xFF22303D),
    onSurfaceVariant = Color(0xFFAAB6C2),
    outlineVariant = Color(0xFF2C3B49),
)

/** Colours for mesh states, shared by the status card, chips and chat ticks. */
@Immutable
data class MeshColors(val online: Color, val mesh: Color, val waiting: Color, val off: Color)

val LocalMeshColors = staticCompositionLocalOf { MeshColors(Color.Gray, Color.Gray, Color.Gray, Color.Gray) }

@Composable
fun HopnetTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) Dark else Light
    val meshColors = MeshColors(online = scheme.primary, mesh = scheme.secondary, waiting = scheme.tertiary, off = scheme.onSurfaceVariant)
    CompositionLocalProvider(LocalMeshColors provides meshColors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
