package com.boxpace.presentation.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.boxpace.domain.DeltaPendente
import com.boxpace.domain.Encomenda
import com.boxpace.domain.EncomendaRepository
import com.boxpace.domain.Preferencias
import com.boxpace.domain.PreferenciasRepository
import com.boxpace.domain.SincronizacaoRepository
import com.boxpace.domain.SyncState
import com.boxpace.domain.Tema
import com.boxpace.domain.Transportadora
import com.boxpace.presentation.vm.ConfiguracoesViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI do toggle de tema (Epic 4) renderizada via Robolectric + compose-test.
 * WorkManager real é trocado pelo [WorkManagerTestInitHelper] — o fluxo de
 * notificações não deve ser testado aqui (boundary da spec).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfiguracoesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    class PreferenciasRepoFake(
        var preferencias: Preferencias = Preferencias(),
    ) : PreferenciasRepository {
        override suspend fun carregar(): Preferencias = preferencias

        override suspend fun salvar(preferencias: Preferencias) {
            this.preferencias = preferencias
        }
    }

    class EncomendaRepoFake : EncomendaRepository {
        override fun observar(): Flow<List<Encomenda>> = MutableStateFlow(emptyList())

        override suspend fun salvar(encomenda: Encomenda) {}

        override suspend fun salvarComDelta(encomenda: Encomenda): Boolean = true

        override suspend fun buscarPorId(id: String): Encomenda? = null

        override suspend fun buscarPorCodigo(codigo: String, transportadora: Transportadora): Encomenda? = null

        override suspend fun listar(): List<Encomenda> = emptyList()

        override suspend fun listarAtivas(): List<Encomenda> = emptyList()

        override suspend fun listarFechadas(): List<Encomenda> = emptyList()

        override suspend fun excluir(id: String, criadoEm: String) {}

        override suspend fun registrarDeltaPendente(delta: DeltaPendente) {}

        override suspend fun listarDeltasPendentes(): List<DeltaPendente> = emptyList()

        override suspend fun limparDeltasPendentes() {}

        override suspend fun purgarFechadasAntigas(dias: Int) {}
    }

    class SincronizacaoRepoFake : SincronizacaoRepository {
        override val syncState: StateFlow<SyncState> = MutableStateFlow(SyncState.Desvinculado)

        override suspend fun vincular(): Boolean = true

        override suspend fun desvincular(): Boolean = true

        override suspend fun reconectar(): Boolean = true
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun UI_TOGGLE_opcoes_visiveis_SISTEMA_selecionado_e_click_em_ESCURO_atualiza_viewModel() {
        val preferenciasRepo = PreferenciasRepoFake()
        val encomendaRepo = EncomendaRepoFake()
        val viewModel = ConfiguracoesViewModel(
            preferenciasRepository = preferenciasRepo,
            encomendaRepository = encomendaRepo,
            sincronizacaoRepository = SincronizacaoRepoFake(),
            agora = { "2026-09-01T12:00:00Z" },
        )

        composeRule.setContent {
            ConfiguracoesScreen(onVoltar = {}, viewModel = viewModel)
        }

        // Sem preferência → o VM inicia em SISTEMA e o segmented reflete isso.
        assertEquals(Tema.SISTEMA, viewModel.tema.value)

        composeRule.onNodeWithText("Sistema").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Claro").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Escuro").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Escuro").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(Tema.ESCURO, viewModel.tema.value)
        assertEquals(Tema.ESCURO, preferenciasRepo.preferencias.tema)
    }

    @Test
    fun UI_INICIA_COM_ESCURO_persistido() {
        val preferenciasRepo = PreferenciasRepoFake(
            preferencias = Preferencias(tema = Tema.ESCURO, updatedAt = "2026-09-01T11:00:00Z"),
        )
        val viewModel = ConfiguracoesViewModel(
            preferenciasRepository = preferenciasRepo,
            encomendaRepository = EncomendaRepoFake(),
            sincronizacaoRepository = SincronizacaoRepoFake(),
            agora = { "2026-09-01T12:00:00Z" },
        )

        composeRule.setContent {
            ConfiguracoesScreen(onVoltar = {}, viewModel = viewModel)
        }

        composeRule.waitForIdle()
        assertEquals(Tema.ESCURO, viewModel.tema.value)
    }
}