package com.boxpace.presentation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.boxpace.domain.Tema

private val BoxpaceLightColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = Accent,
    onPrimaryContainer = OnAccent,
    secondary = Success,
    onSecondary = InkPrimary,
    surface = SurfaceBase,
    onSurface = InkPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = InkSecondary,
    outline = InkDisabled,
    outlineVariant = BorderHairline,
    background = SurfaceBase,
    onBackground = InkPrimary,
    error = InkPrimary,
    onError = SurfaceBase,
)

private val BoxpaceDarkColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = OnAccentDark,
    primaryContainer = AccentDark,
    onPrimaryContainer = OnAccentDark,
    secondary = SuccessDark,
    onSecondary = InkPrimaryDark,
    surface = SurfaceBaseDark,
    onSurface = InkPrimaryDark,
    surfaceVariant = SurfaceRaisedDark,
    onSurfaceVariant = InkSecondaryDark,
    outline = InkDisabledDark,
    outlineVariant = BorderHairlineDark,
    background = SurfaceBaseDark,
    onBackground = InkPrimaryDark,
    error = InkPrimaryDark,
    onError = SurfaceBaseDark,
)

/**
 * Boxpace theme wrapper. When [tema] is [Tema.SISTEMA], follows the device
 * setting via [isSystemInDarkTheme]. [Tema.CLARO] forces light; [Tema.ESCURO]
 * forces dark. Theme switches are immediate (no activity restart).
 */
@Composable
fun BoxpaceTheme(
    tema: Tema,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (tema) {
        Tema.SISTEMA -> isSystemInDarkTheme()
        Tema.CLARO -> false
        Tema.ESCURO -> true
    }
    val colorScheme = if (darkTheme) BoxpaceDarkColorScheme else BoxpaceLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
