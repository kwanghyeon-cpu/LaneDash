package com.khcompany.lanedash.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ArcadeAmber = Color(0xFFFFC93C)
private val ArcadeMagenta = Color(0xFFFF2E92)
private val ArcadeBackground = Color(0xFF120B22)
private val ArcadeSurface = Color(0xFF1D1233)

private val LaneDashColorScheme = darkColorScheme(
    primary = ArcadeAmber,
    secondary = ArcadeMagenta,
    background = ArcadeBackground,
    surface = ArcadeSurface,
    onPrimary = Color(0xFF1D1233),
    onBackground = Color(0xFFF4EFFF),
    onSurface = Color(0xFFF4EFFF),
)

@Composable
fun LaneDashTheme(content: @Composable () -> Unit) {
    // Always use the retro-arcade dark palette regardless of system theme.
    MaterialTheme(
        colorScheme = LaneDashColorScheme,
        typography = LaneDashTypography,
        content = content,
    )
}
