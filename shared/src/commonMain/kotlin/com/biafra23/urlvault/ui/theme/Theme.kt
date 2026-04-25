package com.biafra23.urlvault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AnchorBlue = Color(0xFF1565C0)
private val AnchorBlueDark = Color(0xFF82B1FF)
private val AnchorTeal = Color(0xFF00897B)

private val LightColorScheme = lightColorScheme(
    primary = AnchorBlue,
    secondary = AnchorTeal,
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F)
)

private val DarkColorScheme = darkColorScheme(
    primary = AnchorBlueDark,
    secondary = AnchorTeal,
    background = Color(0xFF1C1B1F),
    surface = Color(0xFF2D2C31),
    onPrimary = Color(0xFF003A88),
    onSecondary = Color.White,
    onBackground = Color(0xFFE6E1E5),
    onSurface = Color(0xFFE6E1E5)
)

@Composable
fun URLVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
