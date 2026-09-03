package com.boxpace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
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
import com.boxpace.presentation.ui.AdicionarEncomendaDialog
import com.boxpace.presentation.ui.BoxpaceTabs
import com.boxpace.presentation.ui.DetalhesScreen
import com.boxpace.presentation.vm.AdicionarEncomendaViewModel
import com.boxpace.presentation.vm.AdicionarEncomendaViewModel.UiEvent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    BoxpaceApp()
                }
            }
        }
    }
}

@Composable
fun BoxpaceApp() {
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

    if (encomendaDetalhe != null) {
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
    } else {
        BoxpaceTabs(
            encomendasAtivas = encomendasAtivas,
            encomendasFechadas = encomendasFechadas,
            onAdicionar = { dialogAberto = true },
            onAbrirDetalhes = { detalhesId = it.id },
        )
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
