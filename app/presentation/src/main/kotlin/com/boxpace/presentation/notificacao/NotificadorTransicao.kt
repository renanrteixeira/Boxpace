package com.boxpace.presentation.notificacao

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.boxpace.MainActivity
import com.boxpace.domain.Encomenda

/**
 * Notificação **local** por transição de status (AD-NOTIFY-REFRESH, NFR11) —
 * sem backend de push.
 *
 * Regras (AD-DADO-SENSIVEL, LGPD):
 * - Título cita a **etiqueta** (e a transportadora, se útil); **CPF nunca**.
 * - Comporta-se silenciosamente quando `POST_NOTIFICATIONS` está negada no
 *   Android 13+ (o worker roda, mas nada é apresentado).
 * - Ao tocar, abre a Detalhes da encomenda via deep link por `id` (`codigo +
 *   transportadora`), sem CPF no extra.
 */
class NotificadorTransicao(
    private val context: Context,
) {

    fun notificarTransicao(encomenda: Encomenda) {
        if (!temPermissao()) return
        criarCanal()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACAO_ABRIR_DETALHE
            putExtra(EXTRA_ID, encomenda.id)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            encomenda.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notificacao = NotificationCompat.Builder(context, CANAL_TRANSICOES)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(titulo(encomenda))
            .setContentText(corpo(encomenda))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(encomenda.id.hashCode(), notificacao)
    }

    /** Título cita o status + etiqueta; jamais o CPF (AD-DADO-SENSIVEL). Padrão UX-DR8. */
    private fun titulo(encomenda: Encomenda): String {
        val status = encomenda.ultimoStatus?.lowercase() ?: "Atualização"
        return "$status — ${encomenda.etiqueta}"
    }

    /** Corpo cita o novo status (mais recente), sem dado pessoal. */
    private fun corpo(encomenda: Encomenda): String =
        encomenda.ultimoStatus ?: "Status atualizado"

    /** `true` se a permissão de notificação for concedida (13+ ou abaixo). */
    fun temPermissao(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    private fun criarCanal() {
        val gerente = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val canal = NotificationChannel(
            CANAL_TRANSICOES,
            "Transições de status",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Avisa quando uma encomenda mudar de status"
        }
        gerente.createNotificationChannel(canal)
    }

    companion object {
        const val ACAO_ABRIR_DETALHE = "com.boxpace.ABRIR_DETALHE"
        const val EXTRA_ID = "id"
        private const val CANAL_TRANSICOES = "transicoes_de_status"
    }
}
