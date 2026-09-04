package com.boxpace.domain

import java.time.Instant

/**
 * Use case: revalidar uma [Encomenda] a partir de um novo resultado de rastreio,
 * **sem duplicar** a regra de domínio que antes vivia no ViewModel (Story 3.1).
 *
 * Extrai/reúne a lógica canônica de "revalidar" — comparar `ultimoStatus`,
 * migração automática para Fechados ([Encomenda.estaEntregue]/`estaFechada`) e
 * persistência (`salvar` + `DeltaPendente.Salvar`, append idempotente) — para
 * ser compartilhada entre o foreground (`revalidar` do ViewModel) e o worker de
 * background (`RevalidarWorker`), sem duplicar a regra.
 *
 * O use case **aceita a encomenda atual + resultado do fetch** e devolve se
 * houve transição de status. Não executa a busca remota (isso fica a cargo do
 * chamador); a busca mora no [ChamadorDeBusca] da fronteira.
 *
 * [agora] é injetável para determinismo em teste (o ViewModel injeta o seu).
 */
class RevalidarEncomendaUseCase(
    private val repository: EncomendaRepository,
    private val agora: () -> String = { Instant.now().toString() },
) {
    /**
     * Revalida [atual] com base em [resultado]. Persiste quando há trabalho a
     * fazer e indica transição de `ultimoStatus`.
     *
     * Casos (I/O & Edge-Case Matrix):
     * - [RastreioResult.Sucesso] com eventos vazios e encomenda já com eventos:
     *   no-op silencioso (mantém o cache) — [Resultado.NadaAFazer].
     * - [RastreioResult.NaoImplementado]: no-op — [Resultado.NadaAFazer].
     * - Persistência sem transição: dados salvos (append idempotente), sem
     *   notificação — [Resultado.Sucesso] com `transitou = false`.
     * - Persistência com transição: dados salvos + `transitou = true`.
     * - Falha de escrita no Room: [Resultado.FalhaNaPersistencia] (sem transição).
     */
    suspend fun executar(atual: Encomenda, resultado: RastreioResult): Resultado {
        return when (resultado) {
            is RastreioResult.NaoImplementado -> Resultado.NadaAFazer
            is RastreioResult.Sucesso -> {
                if (resultado.eventos.isEmpty() && atual.eventos.isNotEmpty()) {
                    Resultado.NadaAFazer
                } else {
                    val novoUltimoStatus = resultado.eventos.lastOrNull()?.descricao
                    val transitou = atual.ultimoStatus != novoUltimoStatus
                    val snapshot = atual.copy(
                        ultimoStatus = novoUltimoStatus,
                        eventos = resultado.eventos,
                        atualizadaEm = agora(),
                        // Sem eventos: conta mais uma busca pra chegar ao badge
                        // "Sem dados"; com eventos, volta a zero.
                        buscasSemEventos = if (resultado.eventos.isEmpty()) {
                            atual.buscasSemEventos + 1
                        } else {
                            0
                        },
                    )
                    val entregue = snapshot.estaEntregue()
                    val aPersistir = snapshot.copy(
                        statusEntregue = entregue,
                        // Migração automática (AD-6, AD-FECHADO): só quando a
                        // encomenda não foi arquivada manualmente (`fechadaEm`
                        // null) e o rastreio indica entrega. Não sobrescreve um
                        // fechado manual.
                        fechadaEm = if (!snapshot.estaFechada() && entregue) {
                            agora()
                        } else {
                            snapshot.fechadaEm
                        },
                    )
                    if (persistir(aPersistir)) {
                        Resultado.Sucesso(aPersistir, transitou)
                    } else {
                        Resultado.FalhaNaPersistencia
                    }
                }
            }
        }
    }

    /** Persiste via repositório e registra o delta de Salvar (LWW). `false` se falhar. */
    private suspend fun persistir(encomenda: Encomenda): Boolean {
        return try {
            repository.salvar(encomenda)
            repository.registrarDeltaPendente(
                DeltaPendente.Salvar(
                    encomenda = encomenda,
                    alvoId = encomenda.id,
                    criadoEm = agora(),
                ),
            )
            true
        } catch (_: Exception) {
            // conservador: falha de escrita no Room não deve derrubar a coroutine
            false
        }
    }

    sealed interface Resultado {
        /** Houve transição de `ultimoStatus` (e a persistência funcionou)? */
        val transitou: Boolean

        /** Salvou com sucesso; [encomenda] é o estado persistido. */
        data class Sucesso(
            val encomenda: Encomenda,
            override val transitou: Boolean,
        ) : Resultado

        /** No-op: fetch vazio com eventos prévios, ou provedor não implementado. */
        data object NadaAFazer : Resultado {
            override val transitou: Boolean get() = false
        }

        /** A escrita no Room falhou; nada foi persistido nem deve notificar. */
        data object FalhaNaPersistencia : Resultado {
            override val transitou: Boolean get() = false
        }
    }
}
