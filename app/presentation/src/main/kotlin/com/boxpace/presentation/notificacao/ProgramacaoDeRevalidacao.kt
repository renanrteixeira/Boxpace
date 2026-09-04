package com.boxpace.presentation.notificacao

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Agenda/cancela o refresh de background limitado (AD-NOTIFY-REFRESH, NFR11).
 *
 * Contratos não-negociáveis (Boundaries & Constraints):
 * - Intervalo mínimo ≥ 30 min ([INTERVALO_MINIMO_MINUTOS]).
 * - Backoff **exponencial** em falha ([BackoffPolicy.EXPONENTIAL]).
 * - Network constraint: exige conectividade ([NetworkType.CONNECTED]).
 * - Trabalho único e identificável ([WORK_NOME]) para a Configurações ligar/desligar.
 */
object ProgramacaoDeRevalidacao {

    const val INTERVALO_MINIMO_MINUTOS = 30L
    const val WORK_NOME = "revalidacao_periodica"

    /**
     * Constrói o [PeriodicWorkRequest] em forma pura (testável): intervalo fixo,
     * backoff exponencial e network constraint. Delegar a busca ao
     * [RevalidarWorker] via worker class.
     */
    fun criarRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<RevalidarWorker>(
            INTERVALO_MINIMO_MINUTOS,
            TimeUnit.MINUTES,
        )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

    /** Liga o worker periódico (idempotente: não cria duplicado). */
    fun ativar(context: android.content.Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NOME,
            ExistingPeriodicWorkPolicy.KEEP,
            criarRequest(),
        )
    }

    /** Desliga o worker periódico. */
    fun desativar(context: android.content.Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NOME)
    }
}
