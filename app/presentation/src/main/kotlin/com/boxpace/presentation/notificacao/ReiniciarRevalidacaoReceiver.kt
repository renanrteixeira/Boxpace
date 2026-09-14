package com.boxpace.presentation.notificacao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restaura o rastreio em background após o reboot (AD-NOTIFY-REFRESH, NFR11).
 *
 * O WorkManager é repersistido pelo próprio OS após o boot, mas o foreground
 * service ([RevalidacaoService]) **não** — então, se o usuário já havia ligado o
 * rastreio em background ([ProgramacaoDeRevalidacao.estaAtivo]), este receiver
 * reativa a programação completa (worker + serviço) de forma idempotente.
 *
 * `BOOT_COMPLETED` é uma das exceções que permitem iniciar foreground service a
 * partir de background no Android 12+.
 */
class ReiniciarRevalidacaoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (ProgramacaoDeRevalidacao.estaAtivo(context)) {
                ProgramacaoDeRevalidacao.ativar(context)
            }
        }
    }
}