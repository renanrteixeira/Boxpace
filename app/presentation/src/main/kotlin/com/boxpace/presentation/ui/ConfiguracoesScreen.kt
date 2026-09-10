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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.boxpace.data.cloud.TokenOAuthProvider
import com.boxpace.domain.SyncState
import com.boxpace.domain.Tema
import com.boxpace.presentation.notificacao.ProgramacaoDeRevalidacao
import com.boxpace.presentation.vm.ConfiguracoesViewModel

/**
 * Tela **Configurações** — seções **Tema** (Epic 4), **Sincronização com Drive**
 * (Epic 5) e **Notificações** (Story 3.1).
 *
 * A seção de notificações acende/desliga o refresh em background reutilizando o
 * [ProgramacaoDeRevalidacao] (WorkManager, `PeriodicWorkRequest` ≥ 30 min). No
 * Android 13+, o toggle pede `POST_NOTIFICATIONS`; se negada, o worker roda
 * silencioso e aparece o aviso `Notificações desativadas`, sem repetir o prompt.
 *
 * O estado do toggle é **derivado do WorkManager** (persistido pelo OS) para
 * sobreviver a process death e restart, e sincronizado com a permissão do
 * sistema para evitar toggle verde + aviso vermelho simultâneos.
 *
 * Tema: segmented de 3 opções (Sistema / Claro / Escuro) conectado ao
 * [ConfiguracoesViewModel] — preferência persistida via DataStore.
 */
@Composable
fun ConfiguracoesScreen(
    onVoltar: () -> Unit,
    viewModel: ConfiguracoesViewModel,
    modifier: Modifier = Modifier,
    solicitarVincular: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val tema by viewModel.tema.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val avisoRestaurar by viewModel.avisoRestaurar.collectAsState()
    var notificarTransicoes by rememberSaveable { mutableStateOf(false) }

    // Inicializa o toggle a partir do estado real do WorkManager (persistido pelo OS).
    // Sobrevive a process death — o toggle reflete se o worker realmente está agendado.
    LaunchedEffect(Unit) {
        notificarTransicoes = ProgramacaoDeRevalidacao.estaAtivo(context)
    }

    // Sincroniza com a permissão do sistema: se o usuário revogou POST_NOTIFICATIONS
    // em Configurações do sistema, desliga o toggle para evitar estado contraditório.
    val permissaoNegada = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED

    LaunchedEffect(permissaoNegada) {
        if (permissaoNegada && notificarTransicoes) {
            notificarTransicoes = false
        }
    }

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

    val opcoesTema = listOf(Tema.SISTEMA to "Sistema", Tema.CLARO to "Claro", Tema.ESCURO to "Escuro")

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
        Text(
            text = "Escolha como o app deve se aparar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            opcoesTema.forEachIndexed { index, (t, label) ->
                SegmentedButton(
                    selected = tema == t,
                    onClick = { viewModel.alternarTema(t) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = opcoesTema.size),
                ) {
                    Text(label)
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text(
            text = "Sincronização com Drive",
            style = MaterialTheme.typography.titleMedium,
        )

        // Launcher do picker OAuth; entrega o token (memória) ao coordenador. Em teste,
        // [solicitarVincular] injeta o disparo (contorno determinístico do OAuth).
        val tokenProvider = remember(context) { com.boxpace.data.di.DataModule.provideTokenOAuthProvider() }
        val launcherPadrao = rememberVincularDriveLauncher(
            tokenOAuthProvider = tokenProvider,
            onVinculado = { viewModel.vincular() },
            onFalha = { /* OAuth negado: permanece "Encomendas só neste aparelho" (vazio) */ },
        )
        val solicitarVincularEfetivo = solicitarVincular ?: launcherPadrao

        when (val estado = syncState) {
            SyncState.Desvinculado -> ConfigRow(
                titulo = "Encomendas só neste aparelho",
                descricao = "Vincule sua conta Google para sincronizar seus dados na nuvem.",
                checked = false,
                onCheckedChange = { if (it) solicitarVincularEfetivo() },
                acaoRotulo = "Vincular Google Drive",
                aviso = avisoRestaurar,
            )
            SyncState.Restaurando -> ConfigRow(
                titulo = "Restaurando…",
                descricao = "Restaurando suas encomendas…",
                checked = true,
                onCheckedChange = null,
                acaoRotulo = null,
            )
            SyncState.Sincronizando -> ConfigRow(
                titulo = "Sincronizando…",
                descricao = "Enviando seus dados com segurança.",
                checked = true,
                onCheckedChange = null,
                acaoRotulo = null,
            )
            SyncState.Vinculado -> ConfigRow(
                titulo = "Vinculado",
                descricao = "Seus dados estão sincronizados no Google Drive.",
                checked = true,
                onCheckedChange = null,
                acaoRotulo = "Desvincular Drive",
                onAcao = { viewModel.desvincular() },
                acaoSecundariaRotulo = "Restaurar do Drive",
                onAcaoSecundaria = { viewModel.restaurar() },
                aviso = avisoRestaurar,
            )
            is SyncState.SincronizacaoEmPausa -> ConfigRow(
                titulo = "Vinculado",
                descricao = estado.motivo,
                checked = true,
                onCheckedChange = null,
                acaoRotulo = "Desvincular Drive",
                onAcao = { viewModel.desvincular() },
                acaoSecundariaRotulo = "Restaurar do Drive",
                onAcaoSecundaria = { viewModel.restaurar() },
                aviso = avisoRestaurar,
            )
            SyncState.SincronizacaoPerdida -> {
                ConfigRow(
                    titulo = "Sincronização perdida",
                    descricao = "Toque para reconectar. Seus dados estão seguros neste aparelho.",
                    checked = true,
                    onCheckedChange = null,
                    acaoRotulo = "Reconectar",
                    // o picker reautoriza (token novo); o vínculo seguinte relê o canônico
                    // e retoma no merge LWW — pull-only, nunca sobrescreve nada
                    onAcao = { solicitarVincularEfetivo() },
                    aviso = avisoRestaurar,
                )
            }
        }
    }
}

/**
 * Linha de configuração com texto de apoio e, opcionalmente, um switch ou um
 * botão de ação (vincular/desvincular/reconectar/restaurar). [aviso] é um
 * alerta discreto exibido abaixo da ação quando não-nulo.
 */
@Composable
private fun ConfigRow(
    titulo: String,
    descricao: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    acaoRotulo: String? = null,
    onAcao: (() -> Unit)? = null,
    acaoSecundariaRotulo: String? = null,
    onAcaoSecundaria: (() -> Unit)? = null,
    aviso: String? = null,
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
        if (onCheckedChange != null) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
        if (acaoRotulo != null && onAcao != null) {
            TextButton(onClick = onAcao) { Text(acaoRotulo) }
        }
        if (acaoSecundariaRotulo != null && onAcaoSecundaria != null) {
            TextButton(onClick = onAcaoSecundaria) { Text(acaoSecundariaRotulo) }
        }
        if (aviso != null) {
            Text(
                text = aviso,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
