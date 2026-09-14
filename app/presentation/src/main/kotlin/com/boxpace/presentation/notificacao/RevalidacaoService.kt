package com.boxpace.presentation.notificacao

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service que mantém a revalidação de encomendas **com o app fechado**
 * (AD-NOTIFY-REFRESH, NFR11): roda uma rodada imediatamente ao iniciar e depois
 * a cada [INTERVALO_MS] (15 min), delegando a [RevalidacaoRotina] — mesma rotina
 * do [RevalidarWorker].
 *
 * - Tipo `specialUse` (Android 14+): uso sustentado iniciado pelo usuário
 *   (toggle em Configurações), com notificação permanente visível enquanto roda
 *   (FXS: usuário sempre vê que o rastreio está ativo). Diferente de `dataSync`,
 *   não está sujeito ao limite de tempo do Android 15+.
 * - `START_STICKY`: se o sistema derrubar o serviço, ele é recriado e o loop
 *   recomeça (null intent cai no fluxo "iniciar").
 * - A notificação não é a notificação de transição (essa segue pelo
 *   [NotificadorTransicao]) — é o indicador persistente do serviço em execução.
 *
 * Controle: iniciado via [ProgramacaoDeRevalidacao.iniciarServico] (toggle ligado,
 * ação de usuário) e parado via [ACAO_PARAR] (toggle desligado).
 */
class RevalidacaoService : Service() {

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACAO_PARAR) {
            encerrar()
            return START_NOT_STICKY
        }
        iniciarForeground()
        iniciarLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        escopo.cancel()
        super.onDestroy()
    }

    private fun iniciarForeground() {
        criarCanal()
        val notificacao = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Rastreando encomendas")
            .setContentText("Verificando novas atualizações a cada ${ProgramacaoDeRevalidacao.INTERVALO_MINIMO_MINUTOS} min")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startForeground(NOTIFICACAO_ID, notificacao, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICACAO_ID, notificacao)
        }
    }

    private fun iniciarLoop() {
        if (loop?.isActive == true) return
        loop = escopo.launch {
            while (isActive) {
                try {
                    RevalidacaoRotina.executar(applicationContext)
                } catch (_: Exception) {
                    // Falha ao listar repositório: tentará de novo na próxima rodada.
                }
                delay(INTERVALO_MS)
            }
        }
    }

    private fun encerrar() {
        loop?.cancel()
        escopo.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun criarCanal() {
        val gerente = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val canal = NotificationChannel(
            CANAL,
            "Rastreio em segundo plano",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Notificação permanente de que encomendas são rastreadas a cada 15 min"
        }
        gerente.createNotificationChannel(canal)
    }

    companion object {
        const val ACAO_INICIAR = "com.boxpace.REVALIDACAO_INICIAR"
        const val ACAO_PARAR = "com.boxpace.REVALIDACAO_PARAR"

        /** Milissegundos entre rodadas — mesmo intervalo mínimo do WorkManager. */
        val INTERVALO_MS: Long = ProgramacaoDeRevalidacao.INTERVALO_MINIMO_MINUTOS * 60_000L

        private const val CANAL = "revalidacao_background"
        private const val NOTIFICACAO_ID = 1_000
    }
}