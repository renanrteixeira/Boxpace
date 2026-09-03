package com.boxpace.presentation.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpace.domain.DeltaPendente
import com.boxpace.domain.Encomenda
import com.boxpace.domain.EncomendaRepository
import com.boxpace.domain.ErroDeRastreio
import com.boxpace.domain.Evento
import com.boxpace.domain.RastrearEncomendaUseCase
import com.boxpace.domain.RastreioResult
import com.boxpace.domain.Transportadora
import com.boxpace.domain.ValidadorDeCodigo
import com.boxpace.domain.ValidadorDeDocumento
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel do fluxo de adicionar encomenda (Story 1.3) + espelho local (1.6).
 *
 * Fluxo: validar no domínio (formato do código, etiqueta com trim, CPF/CNPJ de
 * J&T) → [RastrearEncomendaUseCase] → sucesso persiste no [EncomendaRepository]
 * (Room) de forma reativa e fecha o dialog; falha preserva os campos com
 * mensagem clara — nunca cria encomenda fantasma antes da busca.
 *
 * Estado vem reativamente do repositório ([encomendas]), com flows derivados
 * [encomendasAtivas]/[encomendasFechadas]/[detalhe]. Mutações escrevem no Room e
 * registram [DeltaPendente] (espelho para o Epic 5 / Drive).
 *
 * Regras de dado sensível (AD-DADO-SENSIVEL): CPF digitado mascarado na UI
 * (ver `AdicionarEncomendaDialog`), sanitizado de `-`/`.`/`/`, nunca logado.
 */
class AdicionarEncomendaViewModel(
    private val rastrear: RastrearEncomendaUseCase,
    private val repository: EncomendaRepository,
    private val agora: () -> String = { Instant.now().toString() },
) : ViewModel() {

    data class Form(
        val codigo: String = "",
        val transportadora: Transportadora = Transportadora.CORREIOS,
        val etiqueta: String = "",
        val cpf: String = "",
        val carregando: Boolean = false,
        /** Depois de ~10s de busca (cold start do scraper), o plano B é aguardar
         * até 30s: a UI troca a mensagem para "Aguardando o servidor acordar…". */
        val aguardandoServidor: Boolean = false,
        val erro: String? = null,
    )

    sealed interface UiEvent {
        data object Fechar : UiEvent
    }

    private val _form = MutableStateFlow(Form())
    val form: StateFlow<Form> = _form.asStateFlow()

    /** Todas as encomendas persistidas, observadas reativamente do Room. */
    val encomendas: StateFlow<List<Encomenda>> = repository.observar()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Filtro derivado: ativas (`fechadaEm == null`). */
    val encomendasAtivas: StateFlow<List<Encomenda>> = encomendas
        .map { lista -> lista.filter { it.fechadaEm == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Filtro derivado: fechadas (`fechadaEm != null`). */
    val encomendasFechadas: StateFlow<List<Encomenda>> = encomendas
        .map { lista -> lista.filter { it.fechadaEm != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Encomenda pelo id observada reativamente, ou `null` se não existir. */
    fun detalhe(id: String): Flow<Encomenda?> = encomendas.map { lista ->
        lista.firstOrNull { it.id == id }
    }

    private val _eventos = Channel<UiEvent>(Channel.BUFFERED)
    val eventos = _eventos.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.purgarFechadasAntigas(PURGA_DIAS)
        }
    }

    fun codigoMudou(valor: String) = _form.update { it.copy(codigo = valor, erro = null) }

    fun etiquetaMudou(valor: String) = _form.update { it.copy(etiqueta = valor, erro = null) }

    fun cpfMudou(valor: String) = _form.update { it.copy(cpf = valor, erro = null) }

    fun transportadoraMudou(valor: Transportadora) = _form.update {
        it.copy(transportadora = valor, cpf = if (valor == Transportadora.JT) it.cpf else "", erro = null)
    }

    /** Descarta o formulário (cancelou/reabriu o dialog) sem tocar a lista. */
    fun descartar() {
        _form.value = Form()
    }

    fun adicionar() {
        val f = _form.value
        if (f.carregando) return

        val codigo = f.codigo.trim()
        val etiqueta = f.etiqueta.trim()
        val cpf = f.cpf.filter(Char::isDigit).takeIf { it.isNotEmpty() }

        when {
            !ValidadorDeCodigo.validar(codigo, f.transportadora) ->
                _form.update { it.copy(erro = "Confere o código?") }

            etiqueta.isEmpty() ->
                _form.update { it.copy(erro = "Dá um nome pra essa encomenda") }

            f.transportadora == Transportadora.JT && !ValidadorDeDocumento.documentoFiscalValido(cpf) ->
                _form.update { it.copy(erro = "Confere o CPF do destinatário?") }

            else -> viewModelScope.launch {
                _form.update { it.copy(carregando = true, erro = null, aguardandoServidor = false) }
                // Plano B (AC 1.3): se a busca passar de ~10s (cold start do
                // scraper), a UI avisa que pode demorar até 30s na primeira vez.
                val alertaAcordar = viewModelScope.launch {
                    delay(TEMPO_ACORDAR_MS)
                    _form.update { it.copy(aguardandoServidor = true) }
                }
                try {
                    when (val resultado = rastrear.executar(codigo, f.transportadora, cpf)) {
                        is RastreioResult.Sucesso -> {
                            val encomenda = montarEncomenda(
                                codigo = codigo,
                                etiqueta = etiqueta,
                                transportadora = f.transportadora,
                                cpf = cpf,
                                eventos = resultado.eventos,
                            )
                            if (persistir(encomenda)) {
                                _form.value = Form()
                                _eventos.send(UiEvent.Fechar)
                            } else {
                                _form.update { it.copy(carregando = false, aguardandoServidor = false, erro = "Não deu pra salvar agora. Tente de novo.") }
                            }
                        }
                        is RastreioResult.NaoImplementado ->
                            _form.update { it.copy(carregando = false, aguardandoServidor = false, erro = "Provedor não disponível.") }
                    }
                } catch (e: ErroDeRastreio) {
                    _form.update { it.copy(carregando = false, aguardandoServidor = false, erro = e.mensagem) }
                } catch (e: Exception) {
                    _form.update { it.copy(carregando = false, aguardandoServidor = false, erro = "Não deu pra adicionar agora. Tente de novo.") }
                } finally {
                    alertaAcordar.cancel()
                }
            }
        }
    }

    private fun montarEncomenda(
        codigo: String,
        etiqueta: String,
        transportadora: Transportadora,
        cpf: String?,
        eventos: List<Evento>,
    ): Encomenda {
        val agoraIso = agora()
        return Encomenda(
            id = "${transportadora.scraperId}:${codigo}",
            codigo = codigo,
            transportadora = transportadora,
            etiqueta = sanitizarEtiqueta(etiqueta),
            ultimoStatus = eventos.lastOrNull()?.descricao,
            statusEntregue = eventos.any { it.descricao.contains("entregue", ignoreCase = true) },
            eventos = eventos,
            criadaEm = agoraIso,
            atualizadaEm = agoraIso,
            fechadaEm = null,
            cpfDestinatario = cpf,
            // A primeira busca conta como 1: se não houver eventos, já inicia o
            // caminho pro badge "Sem dados"; se houver, zera.
            buscasSemEventos = if (eventos.isEmpty()) 1 else 0,
        )
    }

    /** Persiste no repositório (Room) e registra o delta de Salvar (LWW). Retorna `false` se a escrita falhar. */
    private suspend fun persistir(encomenda: Encomenda): Boolean {
        return try {
            val agoraIso = agora()
            repository.salvar(encomenda)
            repository.registrarDeltaPendente(
                DeltaPendente.Salvar(
                    encomenda = encomenda,
                    alvoId = encomenda.id,
                    criadoEm = agoraIso,
                ),
            )
            true
        } catch (_: Exception) {
            // conservador: falha de escrita no Room não deve derrubar a coroutine
            false
        }
    }

    /** Etiqueta sanitizada para sobreviver a round-trip JSON (escape `"`/`\`). */
    private fun sanitizarEtiqueta(etiqueta: String): String =
        etiqueta.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Arquiva: seta `fechadaEm = agora` (sai de Ativos, entra em Fechados). */
    fun arquivar(id: String) {
        viewModelScope.launch {
            try {
                val atual = encomendas.value.firstOrNull { it.id == id } ?: return@launch
                if (atual.fechadaEm != null) return@launch
                persistir(atual.copy(fechadaEm = agora()))
            } catch (_: Exception) {
                // conservador: falha de escrita no Room não deve derrubar a coroutine
            }
        }
    }

    /** Reabre: `fechadaEm = null` e volta ao topo de Ativos com `atualizadaEm = agora`. */
    fun reabrir(id: String) {
        viewModelScope.launch {
            try {
                val atual = encomendas.value.firstOrNull { it.id == id } ?: return@launch
                if (atual.fechadaEm == null) return@launch
                persistir(atual.copy(fechadaEm = null, atualizadaEm = agora()))
            } catch (_: Exception) {
                // conservador: falha de escrita no Room não deve derrubar a coroutine
            }
        }
    }

    /** Exclui: remove da lista (ativa ou fechada) + registra delta de Excluir. */
    fun excluir(id: String) {
        viewModelScope.launch {
            try {
                repository.excluir(id)
                repository.registrarDeltaPendente(DeltaPendente.Excluir(alvoId = id, criadoEm = agora()))
            } catch (_: Exception) {
                // conservador: falha de escrita no Room não deve derrubar a coroutine
            }
        }
    }

    /**
     * Revalida em background: rastreia de novo via use case e substitui os
     * eventos/`ultimoStatus`/`statusEntregue`/`atualizadaEm` in-place, desde que
     * o id ainda exista (race guard). Preserva `fechadaEm` atual; id inexistente
     * (excluída) = no-op silencioso; erro mantém o cache.
     */
    fun revalidar(id: String) {
        val alvo = encomendas.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            try {
                when (val resultado = rastrear.executar(alvo.codigo, alvo.transportadora, alvo.cpfDestinatario)) {
                    is RastreioResult.Sucesso -> {
                        val atual = encomendas.value.firstOrNull { it.id == id } ?: return@launch
                        if (resultado.eventos.isEmpty() && atual.eventos.isNotEmpty()) return@launch
                        repository.salvar(
                            atual.copy(
                                ultimoStatus = resultado.eventos.lastOrNull()?.descricao,
                                statusEntregue = resultado.eventos.any {
                                    it.descricao.contains("entregue", ignoreCase = true)
                                },
                                eventos = resultado.eventos,
                                atualizadaEm = agora(),
                                // Sem eventos: conta mais uma busca pra chegar ao badge
                                // "Sem dados"; com eventos, volta a zero.
                                buscasSemEventos = if (resultado.eventos.isEmpty()) {
                                    atual.buscasSemEventos + 1
                                } else {
                                    0
                                },
                            ),
                        )
                        repository.purgarFechadasAntigas(PURGA_DIAS)
                    }
                    is RastreioResult.NaoImplementado -> Unit
                }
            } catch (_: Exception) {
                // conservador: mantém o cache atual
            }
        }
    }

    /** "Repetir" do badge "Sem dados": refaz uma busca (mesmo fluxo de [revalidar]). */
    fun repetirBusca(id: String) = revalidar(id)

    /** Badge "Sem dados" (AC 1.3): sem eventos há pelo menos [SEM_DADOS_BUSCAS] buscas. */
    fun semDados(encomenda: Encomenda): Boolean =
        encomenda.eventos.isEmpty() && encomenda.buscasSemEventos >= SEM_DADOS_BUSCAS

    companion object {
        /** Fechados com `fechadaEm` mais antigo que isso são purgados do Room. */
        const val PURGA_DIAS = 90

        /** Depois desse tempo de busca sem resposta, assume-se cold start do scraper. */
        const val TEMPO_ACORDAR_MS = 10_000L

        /** Buscas consecutivas sem eventos até aparecer o badge "Sem dados" (1 inicial + 2 refresh). */
        const val SEM_DADOS_BUSCAS = 3

        /**
         * Filtra a lista por [termo] usando substring case-insensitive em
         * etiqueta OU código. Lista original totalmente — sem dedup.
         */
        fun filtrarBusca(encomendas: List<Encomenda>, termo: String): List<Encomenda> {
            if (termo.isBlank()) return encomendas
            val lower = termo.trim().lowercase()
            return encomendas.filter {
                it.etiqueta.lowercase().contains(lower) || it.codigo.lowercase().contains(lower)
            }
        }
    }
}
