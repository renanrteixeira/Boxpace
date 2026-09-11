package com.boxpace.presentation.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.boxpace.domain.Encomenda
import com.boxpace.domain.Transportadora
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetalhesScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun encomendaAtiva(
        etiqueta: String = "Caixa",
        codigo: String = "AA123456789BR",
        transportadora: Transportadora = Transportadora.CORREIOS,
    ) = Encomenda(
        id = "${transportadora.scraperId}:$codigo",
        codigo = codigo,
        transportadora = transportadora,
        etiqueta = etiqueta,
        criadaEm = "2026-09-01T12:00:00Z",
        atualizadaEm = "2026-09-01T12:00:00Z",
    )

    // --- DETALLES_GATE: Excluir → confirm invoca onExcluir ---

    @Test
    fun detalhes_excluir_confirm_invoca_callback() {
        var excluiu = false
        val encomenda = encomendaAtiva(etiqueta = "Notebook Dell")
        composeRule.setContent {
            DetalhesScreen(
                encomenda = encomenda,
                onArquivar = {},
                onReabrir = {},
                onExcluir = { excluiu = true },
                onVoltar = {},
            )
        }
        composeRule.onNodeWithText("Excluir").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Excluir definitivamente?").assertIsDisplayed()
        // Two nodes with "Excluir": action button + dialog confirm; take last (dialog)
        composeRule.onAllNodesWithText("Excluir").apply {
            get(1).assertIsDisplayed()
            get(1).performClick()
        }
        composeRule.waitForIdle()
        assert(excluiu) { "onExcluir deve ser invocado ao confirmar" }
    }

    // --- DETALLES_GATE: cancelar no exclui ---

    @Test
    fun detalhes_cancelar_no_exclui() {
        var excluiu = false
        val encomenda = encomendaAtiva()
        composeRule.setContent {
            DetalhesScreen(
                encomenda = encomenda,
                onArquivar = {},
                onReabrir = {},
                onExcluir = { excluiu = true },
                onVoltar = {},
            )
        }
        composeRule.onNodeWithText("Excluir").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancelar").performClick()
        composeRule.waitForIdle()
        assert(!excluiu) { "onExcluir não deve ser invocado ao cancelar" }
    }

    // --- DETALLES_GATE: Arquivar invoca callback ---

    @Test
    fun detalhes_arquivar_invoca_callback() {
        var arquivou = false
        val encomenda = encomendaAtiva()
        composeRule.setContent {
            DetalhesScreen(
                encomenda = encomenda,
                onArquivar = { arquivou = true },
                onReabrir = {},
                onExcluir = {},
                onVoltar = {},
            )
        }
        composeRule.onNodeWithText("Arquivar").performClick()
        composeRule.waitForIdle()
        assert(arquivou) { "onArquivar deve ser invocado" }
    }

    // --- BACK_HANDLER: voltar do sistema invoca onVoltar (BackHandler) ---

    @Test
    fun detalhes_voltar_botao_invoca_callback() {
        var voltou = false
        val encomenda = encomendaAtiva()
        composeRule.setContent {
            DetalhesScreen(
                encomenda = encomenda,
                onArquivar = {},
                onReabrir = {},
                onExcluir = {},
                onVoltar = { voltou = true },
            )
        }
        composeRule.onNodeWithText("← Voltar").performClick()
        composeRule.waitForIdle()
        assert(voltou) { "onVoltar deve ser invocado ao clicar ← Voltar" }
    }

    @Test
    fun detalhes_voltar_sistema_invoca_callback() {
        var voltou = false
        val encomenda = encomendaAtiva()
        composeRule.setContent {
            DetalhesScreen(
                encomenda = encomenda,
                onArquivar = {},
                onReabrir = {},
                onExcluir = {},
                onVoltar = { voltou = true },
            )
        }
        composeRule.waitForIdle()
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assert(voltou) { "onVoltar deve ser invocado pelo voltar do sistema" }
    }
}
