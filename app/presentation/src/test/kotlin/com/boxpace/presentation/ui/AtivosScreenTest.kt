package com.boxpace.presentation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.boxpace.domain.Encomenda
import com.boxpace.domain.Evento
import com.boxpace.domain.Transportadora
import com.boxpace.presentation.vm.AdicionarEncomendaViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AtivosScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun encomendaAtiva(
        etiqueta: String = "Caixa",
        codigo: String = "AA123456789BR",
        transportadora: Transportadora = Transportadora.CORREIOS,
        eventos: List<Evento> = emptyList(),
        buscasSemEventos: Int = 0,
    ) = Encomenda(
        id = "${transportadora.scraperId}:$codigo",
        codigo = codigo,
        transportadora = transportadora,
        etiqueta = etiqueta,
        criadaEm = "2026-09-01T12:00:00Z",
        atualizadaEm = "2026-09-01T12:00:00Z",
        eventos = eventos,
        buscasSemEventos = buscasSemEventos,
    )

    // --- MENU_ROW_ABRE_DIALOG ---

    @Test
    fun menu_row_abre_dialog() {
        val encomenda = encomendaAtiva(etiqueta = "Notebook")
        composeRule.setContent {
            AtivosScreen(
                encomendas = listOf(encomenda),
                onAdicionar = {},
                onAbrirDetalhes = {},
                onExcluir = {},
            )
        }
        composeRule.onNodeWithContentDescription("Mais ações").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Excluir").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Excluir definitivamente?").assertIsDisplayed()
    }

    // --- BADGE_ABRE_DIALOG ---

    @Test
    fun badge_abre_dialog() {
        val encomenda = encomendaAtiva(
            etiqueta = "Sem dados encomenda",
            eventos = emptyList(),
            buscasSemEventos = AdicionarEncomendaViewModel.SEM_DADOS_BUSCAS,
        )
        composeRule.setContent {
            AtivosScreen(
                encomendas = listOf(encomenda),
                onAdicionar = {},
                onAbrirDetalhes = {},
                onExcluir = {},
            )
        }
        composeRule.onNodeWithText("Sem dados").assertIsDisplayed()
        composeRule.onNodeWithText("Excluir").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Excluir definitivamente?").assertIsDisplayed()
    }

    // --- DIALOG_CANCELAR_NO_EXCLUYE ---

    @Test
    fun dialog_cancelar_no_exclui() {
        var excluiu = false
        val encomenda = encomendaAtiva()
        composeRule.setContent {
            AtivosScreen(
                encomendas = listOf(encomenda),
                onAdicionar = {},
                onAbrirDetalhes = {},
                onExcluir = { excluiu = true },
            )
        }
        composeRule.onNodeWithContentDescription("Mais ações").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Excluir").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancelar").performClick()
        composeRule.waitForIdle()
        assert(!excluiu) { "onExcluir não deve ser invocado ao cancelar" }
    }

    // --- DIALOG_CONFIRM_EXCLUYE ---

    @Test
    fun dialog_confirm_exclui() {
        var excluiuEncomenda: Encomenda? = null
        val encomenda = encomendaAtiva(etiqueta = "Notebook Dell")
        composeRule.setContent {
            AtivosScreen(
                encomendas = listOf(encomenda),
                onAdicionar = {},
                onAbrirDetalhes = {},
                onExcluir = { excluiuEncomenda = it },
            )
        }
        composeRule.onNodeWithContentDescription("Mais ações").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Excluir").performClick()
        composeRule.waitForIdle()
        // Two nodes with "Excluir": menu item (gone) + dialog button
        composeRule.onAllNodesWithText("Excluir").apply {
            get(0).assertIsDisplayed()
            get(0).performClick()
        }
        composeRule.waitForIdle()
        assert(excluiuEncomenda == encomenda) {
            "onExcluir deve ser invocado com a encomenda correta"
        }
    }

    // --- MENU_VS_SWIPE_PARIDAD ---

    @Test
    fun menu_arquivar_invoca_callback() {
        var arquivada: Encomenda? = null
        val encomenda = encomendaAtiva(etiqueta = "Para arquivar")
        composeRule.setContent {
            AtivosScreen(
                encomendas = listOf(encomenda),
                onAdicionar = {},
                onAbrirDetalhes = {},
                onArquivar = { arquivada = it },
            )
        }
        composeRule.onNodeWithContentDescription("Mais ações").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Arquivar")
            .filterToOne(hasClickAction())
            .performClick()
        composeRule.waitForIdle()
        assert(arquivada == encomenda) {
            "onArquivar deve ser invocado via menu"
        }
    }

    // --- ROTACION_PRESERVA_DIALOG ---

    @Test
    fun rotaciona_preserva_dialog() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            var mostrar by rememberSaveable { mutableStateOf(true) }
            ConfirmarExclusaoDialog(
                mostrar = mostrar,
                aoCancelar = { mostrar = false },
                aoConfirmar = { mostrar = false },
            )
        }
        composeRule.onNodeWithText("Excluir definitivamente?").assertIsDisplayed()
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Excluir definitivamente?").assertIsDisplayed()
    }
}
