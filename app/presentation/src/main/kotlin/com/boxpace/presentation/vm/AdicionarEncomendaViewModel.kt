package com.boxpace.presentation.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpace.domain.Encomenda
import com.boxpace.domain.ErroDeRastreio
import com.boxpace.domain.Evento
import com.boxpace.domain.RastrearEncomendaUseCase
import com.boxpace.domain.RastreioResult
import com.boxpace.domain.Transportadora
import com.boxpace.domain.ValidadorDeCodigo
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel do fluxo de adicionar encomenda (Story 1.3).
 *
 * Fluxo: validar no domínio (formato do código, etiqueta com trim, CPF/CNPJ de
 * J&T) → [RastrearEncomendaUseCase] → sucesso agrega em memória (topo, LWW por
 * `codigo + transportadora`) e fecha o dialog; falha preserva os campos com
 * mensagem clara — nunca cria encomenda fantasma antes da busca.
 *
 * Regras de dado sensível (AD-DADO-SENSIVEL): CPF digitado mascarado na UI
 * (ver `AdicionarEncomendaDialog`), sanitizado de `-`/`.`/`/`, nunca logado.
 */
class AdicionarEncomendaViewModel(
    private val rastrear: RastrearEncomendaUseCase,
    private val agora: () -> String = { Instant.now().toString() },
) : ViewModel() {

    data class Form(
        val codigo: String = "",
        val transportadora: Transportadora = Transportadora.CORREIOS,
        val etiqueta: String = "",
        val cpf: String = "",
        val carregando: Boolean = false,
        val erro: String? = null,
    )

    sealed interface UiEvent {
        data object Fechar : UiEvent
    }

    private val _form = MutableStateFlow(Form())
    val form: StateFlow<Form> = _form.asStateFlow()

    private val _encomendas = MutableStateFlow<List<Encomenda>>(emptyList())
    val encomendas: StateFlow<List<Encomenda>> = _encomendas.asStateFlow()

    private val _eventos = Channel<UiEvent>(Channel.BUFFERED)
    val eventos = _eventos.receiveAsFlow()

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

            f.transportadora == Transportadora.JT && !cpfValido(cpf) ->
                _form.update { it.copy(erro = "Confere o CPF do destinatário?") }

            else -> viewModelScope.launch {
                _form.update { it.copy(carregando = true, erro = null) }
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
                            agregar(encomenda)
                            _form.value = Form()
                            _eventos.send(UiEvent.Fechar)
                        }
                        is RastreioResult.NaoImplementado ->
                            _form.update { it.copy(carregando = false, erro = "Provedor não disponível.") }
                    }
                } catch (e: ErroDeRastreio) {
                    _form.update { it.copy(carregando = false, erro = e.mensagem) }
                } catch (e: Exception) {
                    _form.update { it.copy(carregando = false, erro = "Não deu pra adicionar agora. Tente de novo.") }
                }
            }
        }
    }

    private fun cpfValido(cpf: String?): Boolean = cpf?.length == 11 || cpf?.length == 14

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
        )
    }

    /** LWW em memória por identidade `codigo + transportadora`; nova no topo. */
    private fun agregar(encomenda: Encomenda) {
        _encomendas.update { lista ->
            val restante = lista.filterNot {
                it.codigo == encomenda.codigo && it.transportadora == encomenda.transportadora
            }
            listOf(encomenda) + restante
        }
    }

    /** Etiqueta sanitizada para sobreviver a round-trip JSON (escape `"`/`\`). */
    private fun sanitizarEtiqueta(etiqueta: String): String =
        etiqueta.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
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