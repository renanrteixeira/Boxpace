package com.boxpace.presentation.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmarExclusaoDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mostrar_false_nao_renderiza_titulo() {
        composeRule.setContent {
            ConfirmarExclusaoDialog(
                mostrar = false,
                aoCancelar = {},
                aoConfirmar = {},
            )
        }
        composeRule.onNodeWithText("Excluir definitivamente?").assertDoesNotExist()
    }

    @Test
    fun dialog_cancelar_nao_exclui_e_fecha() {
        var excluiu = false
        composeRule.setContent {
            ConfirmarExclusaoDialog(
                mostrar = true,
                aoCancelar = {},
                aoConfirmar = { excluiu = true },
            )
        }
        composeRule.onNodeWithText("Excluir definitivamente?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancelar").performClick()
        composeRule.waitForIdle()
        assert(!excluiu) { "onExcluir não deve ser invocado ao cancelar" }
    }

    @Test
    fun dialog_confirmar_invoca_on_excluir() {
        var excluiu = false
        composeRule.setContent {
            ConfirmarExclusaoDialog(
                mostrar = true,
                aoCancelar = {},
                aoConfirmar = { excluiu = true },
            )
        }
        composeRule.onNodeWithText("Excluir").performClick()
        composeRule.waitForIdle()
        assert(excluiu) { "onExcluir deve ser invocado ao confirmar" }
    }
}
