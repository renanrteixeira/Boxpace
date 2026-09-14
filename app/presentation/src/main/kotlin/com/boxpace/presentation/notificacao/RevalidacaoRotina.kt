package com.boxpace.presentation.notificacao

import android.content.Context
import com.boxpace.data.di.DataModule
import com.boxpace.domain.RastrearEncomendaUseCase
import com.boxpace.domain.RegrasDeRetencao
import com.boxpace.domain.RevalidarEncomendaUseCase

/**
 * Revalidação de **uma rodada** das encomendas ativas (AD-NOTIFY-REFRESH, NFR11).
 *
 * Lógica compartilhada entre o worker de background ([RevalidarWorker]) e o
 * foreground service ([RevalidacaoService]) — sem duplicação:
 * - Reutiliza [RevalidarEncomendaUseCase] (mesma regra do foreground/ViewModel).
 * - Respeita o mutex por `codigo` ([Gates.revalidacao]) compartilhado com o
 *   foreground: a segunda fonte serializa, nunca faz fetch duplicado.
 * - Notifica somente por transição ([RevalidarEncomendaUseCase.Resultado] com
 *   `transitou`); [NotificadorTransicao] opera em silêncio se `POST_NOTIFICATIONS`
 *   estiver negada (PERMISSAO_NEGADA).
 * - Falha por encomenda é isolada (FETCH_FALHOU) e não derruba as demais.
 * - Faz o purge best-effort de fechadas antigas após uma rodada com sucesso.
 *
 * Devolve quantas encomendas foram **persistidas** e quantas **falharam** em
 * uma rodada para o chamador decidir retry/backoff ([RevalidarWorker]) ou apenas
 * registrar ([RevalidacaoService]).
 */
object RevalidacaoRotina {

    data class Resultado(val persistidas: Int, val falhas: Int)

    /**
     * Roda uma rodada completa. Lança exceção apenas se o repositório falhar ao
     * **listar** as ativas (o chamador decide o que fazer: retry no worker,
     * silêncio no serviço).
     */
    suspend fun executar(context: Context): Resultado {
        val repository = DataModule.provideEncomendaRepository(context)
        val rastrear = RastrearEncomendaUseCase(DataModule.provideEncomendaRemoteDataSource())
        val useCase = RevalidarEncomendaUseCase(repository)
        val notificador = NotificadorTransicao(context)

        var persistidas = 0
        var falhas = 0
        val ativas = repository.listarAtivas()
        for (encomenda in ativas) {
            try {
                Gates.revalidacao.comLock(encomenda.id) {
                    val resultado = rastrear.executar(
                        encomenda.codigo,
                        encomenda.transportadora,
                        encomenda.cpfDestinatario,
                    )
                    when (val r = useCase.executar(encomenda, resultado)) {
                        is RevalidarEncomendaUseCase.Resultado.Sucesso -> {
                            persistidas++
                            if (r.transitou) notificador.notificarTransicao(r.encomenda)
                        }
                        is RevalidarEncomendaUseCase.Resultado.FalhaNaPersistencia -> falhas++
                        // NadaAFazer: no-op legítimo (status idêntico / sem mudança)
                        RevalidarEncomendaUseCase.Resultado.NadaAFazer -> Unit
                    }
                }
            } catch (_: Exception) {
                // FETCH_FALHOU: falha isolada; segue para as demais encomendas.
                falhas++
            }
        }

        if (persistidas > 0) {
            try {
                repository.purgarFechadasAntigas(RegrasDeRetencao.PURGA_DIAS)
            } catch (_: Exception) {
                // purge é best-effort; não derruba a rodada
            }
        }
        return Resultado(persistidas, falhas)
    }
}