package com.boxpace.presentation.notificacao

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Liga/desliga o refresh de background (AD-NOTIFY-REFRESH, NFR11) em **duas
 * frentes complementares**, com estado persistido:
 *
 * 1. [RevalidarWorker] periódico (WorkManager) — cobertura best-effort do OS
 *    (reboot, processos derrubados, etc.).
 * 2. [RevalidacaoService] foreground — mantém o processo vivo e roda a cada
 *    15 min **mesmo com o app fechado**, com uma notificação permanente visível.
 *
 * Contratos não-negociáveis (Boundaries & Constraints):
 * - Intervalo mínimo ≥ 15 min ([INTERVALO_MINIMO_MINUTOS]), mínimo do WorkManager.
 * - Backoff **exponencial** em falha ([BackoffPolicy.EXPONENTIAL]).
 * - Network constraint: exige conectividade ([NetworkType.CONNECTED]).
 * - Trabalho único e identificável ([WORK_NOME]) para a Configurações ligar/desligar.
 * - Estado persistido localmente (SharedPreferences [ARQUIVO_PREFERENCIAS]) para
 *   o toggle refletir a intenção do usuário e o receiver de reboot ([ReiniciarRevalidacaoReceiver])
 *   restaurar o serviço + worker após o boot.
 */
object ProgramacaoDeRevalidacao {

    const val INTERVALO_MINIMO_MINUTOS = 15L
    const val WORK_NOME = "revalidacao_periodica"

    private const val ARQUIVO_PREFERENCIAS = "revalidacao_prefs"
    private const val CHAVE_ATIVO = "ativado"

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

    /**
     * Estado persistido da intenção (não do estado do work): `true` se o usuário
     * ligou o rastreio em background. Sobrevive a process death e reboot.
     */
    fun estaAtivo(context: Context): Boolean =
        context.getSharedPreferences(ARQUIVO_PREFERENCIAS, Context.MODE_PRIVATE)
            .getBoolean(CHAVE_ATIVO, false)

    /**
     * Liga: persiste a intenção, agenda o worker periódico (idempotente, KEEP) e
     * inicia o foreground service. Chamado pelo toggle em Configurações e pelo
     * receiver de reboot quando o usuário já havia ligado.
     */
    fun ativar(context: Context) {
        marcador(context, true)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NOME,
            ExistingPeriodicWorkPolicy.KEEP,
            criarRequest(),
        )
        iniciarServico(context)
    }

    /** Desliga: persiste a intenção, cancela o worker periódico e para o serviço. */
    fun desativar(context: Context) {
        marcador(context, false)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NOME)
        context.stopService(Intent(context, RevalidacaoService::class.java))
    }

    /** Inicia o foreground service (ação de usuário / broadcast de boot). */
    fun iniciarServico(context: Context) {
        val intent = Intent(context, RevalidacaoService::class.java).apply {
            action = RevalidacaoService.ACAO_INICIAR
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /** Para o foreground service (toggle desligado). */
    fun pararServico(context: Context) {
        context.stopService(Intent(context, RevalidacaoService::class.java))
    }

    private fun marcador(context: Context, ativo: Boolean) {
        context.getSharedPreferences(ARQUIVO_PREFERENCIAS, Context.MODE_PRIVATE).edit {
            putBoolean(CHAVE_ATIVO, ativo)
        }
    }
}