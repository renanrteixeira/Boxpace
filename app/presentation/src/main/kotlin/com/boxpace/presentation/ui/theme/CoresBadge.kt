package com.boxpace.presentation.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Cores do badge de sucesso ("Chegou!") dirigidas pelo ColorScheme (item 22 retro Epic 4). */
internal data class CoresBadgeSucesso(val fundo: Color, val texto: Color)

internal fun coresBadgeSucesso(colorScheme: ColorScheme): CoresBadgeSucesso =
    CoresBadgeSucesso(fundo = colorScheme.secondary, texto = colorScheme.onSecondary)