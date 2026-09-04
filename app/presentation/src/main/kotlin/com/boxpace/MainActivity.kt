package com.boxpace

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boxpace.data.di.DataModule
import com.boxpace.domain.Encomenda
import com.boxpace.domain.RastrearEncomendaUseCase
import com.boxpace.presentation.notificacao.NotificadorTransicao
import com.boxpace.presentation.ui.AdicionarEncomendaDialog
import com.boxpace.presentation.ui.BoxpaceTabs
import com.boxpace.presentation.ui.ConfiguracoesScreen
import com.boxpace.presentation.ui.DetalhesScreen
import com.boxpace.presentation.ui.theme.BoxpaceTheme
import com.boxpace.presentation.vm.AdicionarEncomendaViewModel
import com.boxpace.presentation.vm.AdicionarEncomendaViewModel.UiEvent
import com.boxpace.presentation.vm.ConfiguracoesViewModel

class MainActivity : ComponentActivity() {
    // Pedido de abertura vindo de deep link (notificação). O `selo` garante que
    // toques repetidos na mesma encomenda recomponham (mesmo se o id não mudar).
    private val comandoAbrir = mutableStateOf<ComandoAbrir?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        comandoAbrir.value = comandoDe(intent)
        setContent {
            val configuracoesVm: ConfiguracoesViewModel = viewModel {
                ConfiguracoesViewModel(
                    preferenciasRepository = DataModule.providePreferenciasRepository(),
                    encomendaRepository = DataModule.provideEncomendaRepository(applicationContext),
                )
            }
            val tema by configuracoesVm.tema.collectAsState()
            BoxpaceTheme(tema = tema) {
                Surface {
                    BoxpaceApp(
                        comandoAbrir = comandoAbrir.value,
                        configuracoesVm = configuracoesVm,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        comandoDe(intent)?.let { comandoAbrir.value = it }
    }

    private fun comandoDe(intent: Intent?): ComandoAbrir? =
        extrairIdDeAbertura(intent)?.let { ComandoAbrir(it, SystemClock.elapsedRealtime()) }
}

/**
 * Decodifica o deep link de abertura vindo da notificação (TOCAR_NOTIFICACAO):
 * só produz um `id` quando a action é a de abrir detalhe e há `EXTRA_ID`.
 * Função pura (testável) — sem CPF no extra (AD-DADO-SENSIVEL).
 */
internal fun extrairIdDeAbertura(intent: Intent?): String? =
    intent
        ?.takeIf { it.action == NotificadorTransicao.ACAO_ABRIR_DETALHE }
        ?.getStringExtra(NotificadorTransicao.EXTRA_ID)

internal data class ComandoAbrir(val id: String, val selo: Long)

@Composable
internal fun BoxpaceApp(
    comandoAbrir: ComandoAbrir? = null,
    configuracoesVm: ConfiguracoesViewModel,
) {
    val context = LocalContext.current
    val viewModel: AdicionarEncomendaViewModel = viewModel {
        AdicionarEncomendaViewModel(
            rastrear = RastrearEncomendaUseCase(DataModule.provideEncomendaRemoteDataSource()),
            repository = DataModule.provideEncomendaRepository(context),
        )
    }

    val form by viewModel.form.collectAsState()
    val encomendasAtivas by viewModel.encomendasAtivas.collectAsState()
    val encomendasFechadas by viewModel.encomendasFechadas.collectAsState()
    var dialogAberto by rememberSaveable { mutableStateOf(false) }
    var detalhesId by rememberSaveable { mutableStateOf<String?>(null) }
    var telaConfiguracoes by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.eventos.collect { evento ->
            when (evento) {
                UiEvent.Fechar -> dialogAberto = false
            }
        }
    }

    // Encomenda em detalhe observada reativamente; volta à lista se somir.
    var encomendaDetalhe by remember { mutableStateOf<Encomenda?>(null) }
    LaunchedEffect(detalhesId) {
        val id = detalhesId
        if (id == null) {
            encomendaDetalhe = null
        } else {
            viewModel.detalhe(id).collect { encomendaDetalhe = it }
        }
    }
    LaunchedEffect(detalhesId) {
        detalhesId?.let { viewModel.revalidar(it) }
    }

    // Deep link da notificação: abre a Detalhes (mesmo se já aberta) e revalida.
    LaunchedEffect(comandoAbrir?.id, comandoAbrir?.selo) {
        comandoAbrir?.let { comando ->
            telaConfiguracoes = false
            detalhesId = comando.id
            viewModel.revalidar(comando.id)
        }
    }

    when {
        encomendaDetalhe != null -> {
            DetalhesScreen(
                encomenda = encomendaDetalhe!!,
                onArquivar = { detalhesId?.let { viewModel.arquivar(it) } },
                onReabrir = { detalhesId?.let { viewModel.reabrir(it) } },
                onExcluir = {
                    detalhesId?.let { viewModel.excluir(it) }
                    detalhesId = null
                },
                onVoltar = { detalhesId = null },
            )
        }
        telaConfiguracoes -> {
            ConfiguracoesScreen(
                onVoltar = { telaConfiguracoes = false },
                viewModel = configuracoesVm,
            )
        }
        else -> {
            BoxpaceTabs(
                encomendasAtivas = encomendasAtivas,
                encomendasFechadas = encomendasFechadas,
                onAdicionar = { dialogAberto = true },
                onAbrirDetalhes = { detalhesId = it.id },
                onArquivar = { viewModel.arquivar(it.id) },
                onReabrir = { viewModel.reabrir(it.id) },
                onRepetir = { viewModel.repetirBusca(it.id) },
                onExcluir = { viewModel.excluir(it.id) },
                onAbrirConfiguracoes = { telaConfiguracoes = true },
            )
        }
    }

    if (dialogAberto) {
        AdicionarEncomendaDialog(
            form = form,
            onCodigoMudou = viewModel::codigoMudou,
            onTransportadoraMudou = viewModel::transportadoraMudou,
            onEtiquetaMudou = viewModel::etiquetaMudou,
            onCpfMudou = viewModel::cpfMudou,
            onConfirmar = viewModel::adicionar,
            onFechar = {
                viewModel.descartar()
                dialogAberto = false
            },
        )
    }
}
