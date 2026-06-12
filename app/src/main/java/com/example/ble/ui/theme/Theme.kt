package com.example.ble.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
    val target = if (darkTheme) DarkColorScheme else LightColorScheme
    val spec   = tween<Color>(durationMillis = 700)

    // Animate every color slot so the entire UI crossfades over 700 ms.
    val primary              by animateColorAsState(target.primary,              spec, label = "pri")
    val onPrimary            by animateColorAsState(target.onPrimary,            spec, label = "onPri")
    val primaryContainer     by animateColorAsState(target.primaryContainer,     spec, label = "pc")
    val onPrimaryContainer   by animateColorAsState(target.onPrimaryContainer,   spec, label = "opc")
    val inversePrimary       by animateColorAsState(target.inversePrimary,       spec, label = "invPri")
    val secondary            by animateColorAsState(target.secondary,            spec, label = "sec")
    val onSecondary          by animateColorAsState(target.onSecondary,          spec, label = "onSec")
    val secondaryContainer   by animateColorAsState(target.secondaryContainer,   spec, label = "sc")
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, spec, label = "osc")
    val tertiary             by animateColorAsState(target.tertiary,             spec, label = "ter")
    val onTertiary           by animateColorAsState(target.onTertiary,           spec, label = "onTer")
    val tertiaryContainer    by animateColorAsState(target.tertiaryContainer,    spec, label = "tc")
    val onTertiaryContainer  by animateColorAsState(target.onTertiaryContainer,  spec, label = "otc")
    val background           by animateColorAsState(target.background,           spec, label = "bg")
    val onBackground         by animateColorAsState(target.onBackground,         spec, label = "onBg")
    val surface              by animateColorAsState(target.surface,              spec, label = "surf")
    val onSurface            by animateColorAsState(target.onSurface,            spec, label = "onSurf")
    val surfaceVariant       by animateColorAsState(target.surfaceVariant,       spec, label = "sv")
    val onSurfaceVariant     by animateColorAsState(target.onSurfaceVariant,     spec, label = "osv")
    val surfaceTint          by animateColorAsState(target.surfaceTint,          spec, label = "st")
    val inverseSurface       by animateColorAsState(target.inverseSurface,       spec, label = "invS")
    val inverseOnSurface     by animateColorAsState(target.inverseOnSurface,     spec, label = "invOS")
    val error                by animateColorAsState(target.error,                spec, label = "err")
    val onError              by animateColorAsState(target.onError,              spec, label = "onErr")
    val errorContainer       by animateColorAsState(target.errorContainer,       spec, label = "ec")
    val onErrorContainer     by animateColorAsState(target.onErrorContainer,     spec, label = "oec")
    val outline              by animateColorAsState(target.outline,              spec, label = "ol")
    val outlineVariant       by animateColorAsState(target.outlineVariant,       spec, label = "olv")
    val scrim                by animateColorAsState(target.scrim,                spec, label = "scrim")

    val animatedScheme = target.copy(
        primary              = primary,
        onPrimary            = onPrimary,
        primaryContainer     = primaryContainer,
        onPrimaryContainer   = onPrimaryContainer,
        inversePrimary       = inversePrimary,
        secondary            = secondary,
        onSecondary          = onSecondary,
        secondaryContainer   = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary             = tertiary,
        onTertiary           = onTertiary,
        tertiaryContainer    = tertiaryContainer,
        onTertiaryContainer  = onTertiaryContainer,
        background           = background,
        onBackground         = onBackground,
        surface              = surface,
        onSurface            = onSurface,
        surfaceVariant       = surfaceVariant,
        onSurfaceVariant     = onSurfaceVariant,
        surfaceTint          = surfaceTint,
        inverseSurface       = inverseSurface,
        inverseOnSurface     = inverseOnSurface,
        error                = error,
        onError              = onError,
        errorContainer       = errorContainer,
        onErrorContainer     = onErrorContainer,
        outline              = outline,
        outlineVariant       = outlineVariant,
        scrim                = scrim,
    )

    MaterialTheme(
        colorScheme = animatedScheme,
        typography  = Typography,
        content     = content
    )
}
