package com.offgrid.bmsmonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette tuned for an always-on monitoring screen (dark by default).
val Bg = Color(0xFF12151A)
val Surface = Color(0xFF1B2029)
val SurfaceHi = Color(0xFF232A35)
val BorderCol = Color(0xFF2C3542)
val TextCol = Color(0xFFE6E9EE)
val Muted = Color(0xFF8B95A5)

val Accent = Color(0xFF4DA3FF)
val GreenCol = Color(0xFF3ECF8E)
val AmberCol = Color(0xFFF0A83C)
val RedCol = Color(0xFFE5544B)
val VioletCol = Color(0xFFA98BFF)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = VioletCol,
    background = Bg,
    onBackground = TextCol,
    surface = Surface,
    onSurface = TextCol,
    surfaceVariant = SurfaceHi,
    onSurfaceVariant = Muted,
    outline = BorderCol,
    error = RedCol,
)

fun socColor(soc: Float?): Color = when {
    soc == null -> Muted
    soc >= 60 -> GreenCol
    soc >= 30 -> AmberCol
    else -> RedCol
}

@Composable
fun BmsTheme(content: @Composable () -> Unit) {
    // Always dark; the app is designed around the dark palette above.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content
    )
}
