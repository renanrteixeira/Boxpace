package com.boxpace.presentation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import com.boxpace.domain.Tema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contrato de cor do badge "Chegou!" (item 22 da retro do Epic 4): o foreground
 * dos badges de sucesso vem de `colorScheme.onSecondary`, que deve ser a tinta
 * escura `#2B1000` nos dois modos (`OnSuccess`/`OnSuccessDark`) — nunca `Color.White`
 * (branco sobre verde dark `#3DB87E` = 2.51, falha AA; `#2B1000` = 7.1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoxpaceThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ESCURO_onSecondary_e_a_tinta_OnSuccessDark_e_nao_branco() {
        var onSecondary: Color = Color.Unspecified
        composeRule.setContent {
            BoxpaceTheme(tema = Tema.ESCURO) {
                onSecondary = MaterialTheme.colorScheme.onSecondary
            }
        }
        composeRule.waitForIdle()
        assertEquals(OnSuccessDark, onSecondary)
        assertNotEquals(Color.White, onSecondary)
    }

    @Test
    fun CLARO_onSecondary_e_a_tinta_OnSuccess_e_nao_branco() {
        var onSecondary: Color = Color.Unspecified
        composeRule.setContent {
            BoxpaceTheme(tema = Tema.CLARO) {
                onSecondary = MaterialTheme.colorScheme.onSecondary
            }
        }
        composeRule.waitForIdle()
        assertEquals(OnSuccess, onSecondary)
        assertNotEquals(Color.White, onSecondary)
    }

    @Test
    fun ESCURO_badge_sucesso_usa_onSecondary_com_contraste_AA() {
        var cores: CoresBadgeSucesso = CoresBadgeSucesso(Color.Unspecified, Color.Unspecified)
        composeRule.setContent {
            BoxpaceTheme(tema = Tema.ESCURO) {
                cores = coresBadgeSucesso(MaterialTheme.colorScheme)
            }
        }
        composeRule.waitForIdle()
        assertEquals(OnSuccessDark, cores.texto)
        assertNotEquals(Color.White, cores.texto)
        assertTrue(contraste(cores.texto, cores.fundo) >= 4.5)
    }

    @Test
    fun CLARO_badge_sucesso_usa_onSecondary_e_nao_branco() {
        var cores: CoresBadgeSucesso = CoresBadgeSucesso(Color.Unspecified, Color.Unspecified)
        composeRule.setContent {
            BoxpaceTheme(tema = Tema.CLARO) {
                cores = coresBadgeSucesso(MaterialTheme.colorScheme)
            }
        }
        composeRule.waitForIdle()
        assertEquals(OnSuccess, cores.texto)
        assertNotEquals(Color.White, cores.texto)
    }

    /** Razão de contraste WCAG 2.x entre duas cores (mais clara / mais escura). */
    private fun contraste(a: Color, b: Color): Double {
        val clara = maxOf(a.luminance(), b.luminance()).toDouble()
        val escura = minOf(a.luminance(), b.luminance()).toDouble()
        return (clara + 0.05) / (escura + 0.05)
    }
}