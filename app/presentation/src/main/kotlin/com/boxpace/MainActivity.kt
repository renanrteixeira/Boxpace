package com.boxpace

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boxpace.data.cloud.ConnectivityObserver
import com.boxpace.data.di.DataModule
import com.boxpace.domain.Encomenda
import com.boxpace.domain.RastrearEncomendaUseCase
import com.boxpace.domain.SyncState
import com.boxpace.presentation.notificacao.NotificadorTransicao
import com.boxpace.presentation.ui.AdicionarEncomendaDialog
import com.boxpace.presentation.ui.BoxpaceTabs
import com.boxpace.presentation.ui.BoxpaceTopBar
import com.boxpace.presentation.ui.ConfiguracoesScreen
import com.boxpace.presentation.ui.DetalhesScreen
import com.boxpace.presentation.ui.theme.BoxpaceTheme
import com.boxpace.presentation.vm.AdicionarEncomendaViewModel
import com.boxpace.presentation.vm.AdicionarEncomendaViewModel.UiEvent
import com.boxpace.presentation.vm.ConfiguracoesViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Pedido de abertura vindo de deep link (notificação). O `selo` garante que
    // toques repetidos na mesma encomenda recomponham (mesmo se o id não mudar).
    private val comandoAbrir = mutableStateOf<ComandoAbrir?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge (targetSdk 37 força em API 35+): barras transparentes e
        // conteúdo estendido; os insets ficam com o app, não com o sistema.
        enableEdgeToEdge()
        comandoAbrir.value = comandoDe(intent)
        setContent {
            val configuracoesVm: ConfiguracoesViewModel = viewModel {
                ConfiguracoesViewModel(
                    preferenciasRepository = DataModule.providePreferenciasRepository(applicationContext),
                    encomendaRepository = DataModule.provideEncomendaRepository(applicationContext),
                    sincronizacaoRepository = DataModule.provideSincronizacaoRepository(applicationContext),
                )
            }
            val tema by configuracoesVm.tema.collectAsState()
            BoxpaceTheme(
                tema = tema,
                onDarkThemeChanged = { dark ->
                    // ícones das system bars: escuros no tema claro, claros no escuro
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                },
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // conteúdo nunca fica sob a status/nav bar (edge-to-edge)
                    Box(modifier = Modifier.safeDrawingPadding().fillMaxSize()) {
                        BoxpaceApp(
                            comandoAbrir = comandoAbrir.value,
                            configuracoesVm = configuracoesVm,
                        )
                    }
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
    val notificador = remember(context) { NotificadorTransicao(context) }
    val viewModel: AdicionarEncomendaViewModel = viewModel {
        AdicionarEncomendaViewModel(
            rastrear = RastrearEncomendaUseCase(DataModule.provideEncomendaRemoteDataSource()),
            repository = DataModule.provideEncomendaRepository(context),
            notificarTransicao = notificador::notificarTransicao,
        )
    }

    val coordenador = remember(context) { DataModule.provideCoordenadorDeSync(context) }
    LaunchedEffect(Unit) {
        coordenador.dispararSync()
        val observer = ConnectivityObserver(context)
        observer.reconexao.collect { conectado ->
            if (conectado) {
                coordenador.dispararSync()
            }
        }
    }

    val form by viewModel.form.collectAsState()
    val encomendasAtivas by viewModel.encomendasAtivas.collectAsState()
    val encomendasFechadas by viewModel.encomendasFechadas.collectAsState()
    var dialogAberto by rememberSaveable { mutableStateOf(false) }
    var detalhesId by rememberSaveable { mutableStateOf<String?>(null) }
    var refrescando by remember { mutableStateOf(false) }
    var abaAtiva by rememberSaveable { mutableStateOf(0) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    val syncState by configuracoesVm.syncState.collectAsState()
    val contaVinculada = syncState is SyncState.Vinculado || syncState is SyncState.SincronizacaoEmPausa

    val contaEmail = if (contaVinculada) {
        DataModule.provideTokenOAuthProvider().email()
    } else {
        null
    }

    LaunchedEffect(viewModel) {
        viewModel.eventos.collect { evento ->
            when (evento) {
                UiEvent.Fechar -> dialogAberto = false
            }
        }
    }

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

    LaunchedEffect(comandoAbrir?.id, comandoAbrir?.selo) {
        comandoAbrir?.let { comando ->
            detalhesId = comando.id
            viewModel.revalidar(comando.id)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ConfiguracoesScreen(
                    onVoltar = { coroutineScope.launch { drawerState.close() } },
                    viewModel = configuracoesVm,
                    mostrarVoltar = false,
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                BoxpaceTopBar(
                    contaVinculada = contaVinculada,
                    contaEmail = contaEmail,
                    onAbrirDrawer = { coroutineScope.launch { drawerState.open() } },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            ) {
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
                            refrescando = refrescando,
                            aoAtualizar = {
                                if (!refrescando) {
                                    refrescando = true
                                    val lista = if (abaAtiva == 0) encomendasAtivas else encomendasFechadas
                                    viewModel.revalidarLote(lista) { refrescando = false }
                                }
                            },
                            onAbaMudou = { abaAtiva = it },
                        )
                    }
                }
            }
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
