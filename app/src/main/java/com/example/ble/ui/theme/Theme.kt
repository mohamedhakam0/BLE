package com.example.ble.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

var isDarkTheme by mutableStateOf(true)

fun toggleTheme() { isDarkTheme = !isDarkTheme }

private val DarkColorScheme = darkColorScheme(
    background = Bg0,
    surface = Bg1,
    surfaceVariant = Bg2,
    onBackground = Text1,
    onSurface = Text1,
    onSurfaceVariant = Text2,
    primary = Accent,
    onPrimary = Color.Black,
    outline = BorderWeak
)

private val LightColorScheme = lightColorScheme(
    background = Bg0Light,
    surface = Bg1Light,
    surfaceVariant = Bg2Light,
    onBackground = Text1Light,
    onSurface = Text1Light,
    onSurfaceVariant = Text2Light,
    primary = AccentLight,
    onPrimary = Color.White,
    outline = BorderWeakLight
)

@Composable
fun BLETheme(
    darkTheme: Boolean = isDarkTheme,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
