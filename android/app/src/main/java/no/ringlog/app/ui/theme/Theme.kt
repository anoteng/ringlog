package no.ringlog.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green      = Color(0xFF5B7A3A)
private val GreenDark  = Color(0xFF4A6630)
private val Background = Color(0xFFF5F0EB)
private val Surface    = Color(0xFFFFFFFF)

private val ColorScheme = lightColorScheme(
    primary          = Green,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFD4EDDA),
    secondary        = GreenDark,
    background       = Background,
    surface          = Surface,
    error            = Color(0xFFC0392B),
)

@Composable
fun RingLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, content = content)
}
