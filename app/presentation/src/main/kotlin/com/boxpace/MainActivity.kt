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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boxpace.data.di.DataModule
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
    val viewModel: AdicionarEncomendaViewModel = viewModel {
        AdicionarEncomendaViewModel(
            rastrear = RastrearEncomendaUseCase(DataModule.provideEncomendaRemoteDataSource()),
        )
    }

    val encomendas by viewModel.encomendas.collectAsState()
    val form by viewModel.form.collectAsState()
    var dialogAberto by rememberSaveable { mutableStateOf(false) }
    var detalhesId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.eventos.collect { evento ->
            when (evento) {
                UiEvent.Fechar -> dialogAberto = false
            }
        }
    }

    // Revalida em background ao abrir a Detalhes.
    val encomendaDetalhe = detalhesId?.let { viewModel.buscarPorId(it) }

    // Se o id aponta para algo que não existe mais (ex.: excluída), volta à lista.
    LaunchedEffect(detalhesId, encomendaDetalhe) {
        if (detalhesId != null && encomendaDetalhe == null) {
            detalhesId = null
        }
    }
    LaunchedEffect(detalhesId) {
        detalhesId?.let { viewModel.revalidar(it) }
    }

    if (encomendaDetalhe != null) {
        DetalhesScreen(
            encomenda = encomendaDetalhe,
            onArquivar = { viewModel.arquivar(detalhesId!!) },
            onReabrir = { viewModel.reabrir(detalhesId!!) },
            onExcluir = {
                viewModel.excluir(detalhesId!!)
                detalhesId = null
            },
            onVoltar = { detalhesId = null },
        )
    } else {
        BoxpaceTabs(
            encomendasAtivas = viewModel.encomendasAtivas(),
            encomendasFechadas = viewModel.encomendasFechadas(),
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
