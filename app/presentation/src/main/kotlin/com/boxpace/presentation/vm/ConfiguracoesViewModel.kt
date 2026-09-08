package com.boxpace.presentation.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpace.domain.DeltaPendente
import com.boxpace.domain.EncomendaRepository
import com.boxpace.domain.Preferencias
import com.boxpace.domain.PreferenciasRepository
import com.boxpace.domain.SincronizacaoRepository
import com.boxpace.domain.SyncState
import com.boxpace.domain.Tema
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * ViewModel para a tela Configurações — gerencia a preferência de tema (Epic 4)
 * e o estado de sincronização com o Google Drive (Epic 5).
 *
 * [syncState] vem reativamente da porta [SincronizacaoRepository]. As ações
 * [vincular]/[desvincular]/[reconectar] delegam ao coordenador em `data/cloud`.
 */
class ConfiguracoesViewModel(
    private val preferenciasRepository: PreferenciasRepository,
    private val encomendaRepository: EncomendaRepository,
    private val sincronizacaoRepository: SincronizacaoRepository,
    private val agora: () -> String = { Instant.now().toString() },
) : ViewModel() {

    private val _tema = MutableStateFlow(Tema.SISTEMA)
    val tema: StateFlow<Tema> = _tema.asStateFlow()

    val syncState: StateFlow<SyncState> = sincronizacaoRepository.syncState

    init {
        viewModelScope.launch {
            val preferencias = preferenciasRepository.carregar()
            _tema.update { preferencias.tema }
        }
    }

    /** Autoriza o vínculo no picker (token entregue pelo launcher) e sincroniza. */
    fun vincular() {
        viewModelScope.launch { sincronizacaoRepository.vincular() }
    }

    fun desvincular() {
        viewModelScope.launch { sincronizacaoRepository.desvincular() }
    }

    fun reconectar() {
        viewModelScope.launch { sincronizacaoRepository.reconectar() }
    }

    fun alternarTema(novoTema: Tema) {
        viewModelScope.launch {
            _tema.update { novoTema }
            val preferencias = Preferencias(tema = novoTema, updatedAt = agora())
            preferenciasRepository.salvar(preferencias)
            registrarDeltaPreferencia(preferencias)
        }
    }

    /**
     * Registra delta pendente de preferência para sync futuro (Epic 5 / Drive).
     * Alvo fixo `"preferencias:tema"`; [DeltaPendente.SalvarPreferencia.criadoEm]
     * == [Preferencias.updatedAt] (LWW por registro).
     */
    private suspend fun registrarDeltaPreferencia(preferencias: Preferencias) {
        try {
            encomendaRepository.registrarDeltaPendente(
                DeltaPendente.SalvarPreferencia(
                    preferencias = preferencias,
                    alvoId = "preferencias:tema",
                    criadoEm = preferencias.updatedAt,
                ),
            )
        } catch (_: Exception) {
            // conservador: falha no registro de delta não deve derrubar a preferência
        }
    }
}
