package com.boxpace.presentation.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpace.domain.DeltaPendente
import com.boxpace.domain.EncomendaRepository
import com.boxpace.domain.Preferencias
import com.boxpace.domain.PreferenciasRepository
import com.boxpace.domain.Tema
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * ViewModel para a tela Configurações — gerencia a preferência de tema.
 *
 * Carrega via [PreferenciasRepository] e expõe [tema] como StateFlow reativo.
 * [alternarTema] persiste imediatamente e registra delta pendente para Epic 5.
 */
class ConfiguracoesViewModel(
    private val preferenciasRepository: PreferenciasRepository,
    private val encomendaRepository: EncomendaRepository,
    private val agora: () -> String = { Instant.now().toString() },
) : ViewModel() {

    private val _tema = MutableStateFlow(Tema.SISTEMA)
    val tema: StateFlow<Tema> = _tema.asStateFlow()

    init {
        viewModelScope.launch {
            val preferencias = preferenciasRepository.carregar()
            _tema.update { preferencias.tema }
        }
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
