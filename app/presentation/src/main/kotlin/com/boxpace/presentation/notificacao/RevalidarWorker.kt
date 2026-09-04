package com.boxpace.presentation.notificacao

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.boxpace.data.di.DataModule
import com.boxpace.domain.RastrearEncomendaUseCase
import com.boxpace.domain.RegrasDeRetencao
import com.boxpace.domain.RevalidarEncomendaUseCase

/**
 * Worker de background que revalida as encomendas **ativas** (AD-NOTIFY-REFRESH,
 * NFR11), disparado periodicamente (≥ 30 min, ver [ProgramacaoDeRevalidacao]).
 *
 * - Reutiliza a mesma regra de domínio de `revalidar` via
 *   [RevalidarEncomendaUseCase] — sem duplicação (persist `salvar` +
 *   `DeltaPendente`, migração automática para Fechados, append idempotente).
 * - Respeita o **mutex por `codigo`** compartilhado ([Gates.revalidacao]):
 *   o segundo atualizador (foreground/worker) pula enquanto há um fetch em voo —
 *   sem fetch/notificação duplicada (CONCORRENCIA).
 * - Notifica **somente por transição** ([RevalidarEncomendaUseCase.Resultado]
 *   com `transitou`), e o [NotificadorTransicao] opera em silêncio se a
 *   permissão `POST_NOTIFICATIONS` estiver negada (PERMISSAO_NEGADA).
 * - Falha de rede por encomenda é capturada e não derruba as demais
 *   (FETCH_FALHOU); se **nenhuma** encomenda foi persistida em uma rodada com ao
 *   menos uma falha, devolve [Result.retry] para o [ProgramacaoDeRevalidacao]
 *   aplicar o backoff exponencial. Com sucesso parcial, devolve [Result.success].
 *
 * Construtor `(Context, WorkerParameters)` para a fábrica padrão do WorkManager.
 */
class RevalidarWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = DataModule.provideEncomendaRepository(applicationContext)
        val rastrear = RastrearEncomendaUseCase(DataModule.provideEncomendaRemoteDataSource())
        val useCase = RevalidarEncomendaUseCase(repository)
        val notificador = NotificadorTransicao(applicationContext)

        var persistidas = 0
        var falhas = 0
        try {
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
        } catch (_: Exception) {
            // Falha ao listar/carregar o repositório: sinaliza retry (backoff).
            return Result.retry()
        }

        // Se havia encomendas ativas mas nenhuma persistiu e houve ao menos uma
        // falha (ex.: todo fetch falhou), o backoff exponencial deve disparar.
        if (persistidas == 0 && falhas > 0) {
            return Result.retry()
        }

        if (persistidas > 0) {
            try {
                repository.purgarFechadasAntigas(RegrasDeRetencao.PURGA_DIAS)
            } catch (_: Exception) {
                // purge é best-effort; não derruba o worker
            }
        }
        return Result.success()
    }
}
