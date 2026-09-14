package com.boxpace.presentation.notificacao

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Worker de background que revalida as encomendas **ativas** (AD-NOTIFY-REFRESH,
 * NFR11), disparado periodicamente (≥ 15 min, ver [ProgramacaoDeRevalidacao]).
 *
 * - Delega a rodada de revalidação a [RevalidacaoRotina] — a mesma lógica do
 *   foreground service ([RevalidacaoService]) e do ViewModel, sem duplicação
 *   (usa o [RevalidarEncomendaUseCase] compartilhado e o mutex [Gates.revalidacao]).
 * - Falha de rede por encomenda é capturada e não derruba as demais
 *   (FETCH_FALHOU); se **nenhuma** encomenda foi persistida em uma rodada com ao
 *   menos uma falha, devolve [Result.retry] para o backoff exponencial. Com
 *   sucesso parcial, devolve [Result.success].
 * - Falha ao listar o repositório: [Result.retry] (backoff).
 *
 * Construtor `(Context, WorkerParameters)` para a fábrica padrão do WorkManager.
 */
class RevalidarWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val rodada = RevalidacaoRotina.executar(applicationContext)
            // Se havia encomendas ativas mas nenhuma persistiu e houve ao menos
            // uma falha (ex.: todo fetch falhou), o backoff exponencial dispara.
            if (rodada.persistidas == 0 && rodada.falhas > 0) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (_: Exception) {
            // Falha ao listar/carregar o repositório: sinaliza retry (backoff).
            Result.retry()
        }
    }
}