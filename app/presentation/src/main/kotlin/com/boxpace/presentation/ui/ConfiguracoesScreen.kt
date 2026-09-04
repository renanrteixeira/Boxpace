package com.boxpace.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.boxpace.presentation.notificacao.ProgramacaoDeRevalidacao

/**
 * Tela **Configurações** — seções **Tema** (Epic 4), **Sincronização com Drive**
 * (Epic 5) e **Notificações** (Story 3.1).
 *
 * A seção de notificações acende/desliga o refresh em background reutilizando o
 * [ProgramacaoDeRevalidacao] (WorkManager, `PeriodicWorkRequest` ≥ 30 min). No
 * Android 13+, o toggle pede `POST_NOTIFICATIONS`; se negada, o worker roda
 * silencioso e aparece o aviso `Notificações desativadas`, sem repetir o prompt.
 *
 * Tema/Drive permanecem como skeleton (estado local efêmero) conforme o story.
 */
@Composable
fun ConfiguracoesScreen(
    onVoltar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var temaEscuro by rememberSaveable { mutableStateOf(false) }
    var driveVinculado by rememberSaveable { mutableStateOf(false) }
    var notificarTransicoes by rememberSaveable { mutableStateOf(false) }

    // Ao ativar, verifica a permissão (Android 13+) e liga/desliga o worker.
    val launcherPermissao = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedida ->
        if (concedida) {
            notificarTransicoes = true
            ProgramacaoDeRevalidacao.ativar(context)
        }
        // se negada: notificarTransicoes permanece false → toggle volta desligado
    }

    fun aoAlternarNotificacoes(ativar: Boolean) {
        if (!ativar) {
            notificarTransicoes = false
            ProgramacaoDeRevalidacao.desativar(context)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Prioridade ao dado do sistema: só liga o toggle se concedida.
            launcherPermissao.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificarTransicoes = true
            ProgramacaoDeRevalidacao.ativar(context)
        }
    }

    val permissaoNegada = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onVoltar) { Text("Voltar") }
            Spacer(Modifier.weight(1f))
        }

        Text(
            text = "Configurações",
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Notificações",
            style = MaterialTheme.typography.titleMedium,
        )
        ConfigRow(
            titulo = "Avisar quando uma encomenda mudar de status",
            descricao = "Revalida em segundo plano e notifica apenas quando houver novidade. Mínimo de 30 min entre buscas.",
            checked = notificarTransicoes,
            onCheckedChange = { aoAlternarNotificacoes(it) },
        )
        if (permissaoNegada) {
            Text(
                text = "Notificações desativadas. Pra avisar quando sua encomenda chegar, preciso de permissão de notificação.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text(
            text = "Tema",
            style = MaterialTheme.typography.titleMedium,
        )
        ConfigRow(
            titulo = "Tema escuro",
            descricao = "Aplicar tema escuro. (Epic 4)",
            checked = temaEscuro,
            onCheckedChange = { temaEscuro = it },
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text(
            text = "Sincronização com Drive",
            style = MaterialTheme.typography.titleMedium,
        )
        ConfigRow(
            titulo = "Vincular conta Google",
            descricao = "Sincronizar dados no Google Drive. (Epic 5)",
            checked = driveVinculado,
            onCheckedChange = { driveVinculado = it },
        )
    }
}

@Composable
private fun ConfigRow(
    titulo: String,
    descricao: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = titulo,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = descricao,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
