package com.boxpace.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boxpace.domain.Encomenda

/**
 * Tab bar simples Ativos/Fechados via estado local Compose (sem NavHost).
 * O FAB vive dentro de [AtivosScreen], logo só aparece na aba Ativos.
 */
@Composable
fun BoxpaceTabs(
    encomendasAtivas: List<Encomenda>,
    encomendasFechadas: List<Encomenda>,
    onAdicionar: () -> Unit,
    onAbrirDetalhes: (Encomenda) -> Unit,
    onArquivar: (Encomenda) -> Unit = {},
    onReabrir: (Encomenda) -> Unit = {},
    onRepetir: (Encomenda) -> Unit = {},
    onExcluir: (Encomenda) -> Unit = {},
    refrescando: Boolean = false,
    aoAtualizar: () -> Unit = {},
    onAbaMudou: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var aba by rememberSaveable { mutableStateOf(0) }
    val abas = listOf("Ativos", "Fechados")

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = aba) {
            abas.forEachIndexed { index, titulo ->
                Tab(
                    selected = aba == index,
                    onClick = { aba = index; onAbaMudou(index) },
                    text = { Text(titulo) },
                )
            }
        }

        when (aba) {
            0 -> AtivosScreen(
                encomendas = encomendasAtivas,
                onAdicionar = onAdicionar,
                onAbrirDetalhes = onAbrirDetalhes,
                onArquivar = onArquivar,
                onReabrir = onReabrir,
                onRepetir = onRepetir,
                onExcluir = onExcluir,
                refrescando = refrescando,
                aoAtualizar = aoAtualizar,
            )
            else -> FechadosScreen(
                encomendas = encomendasFechadas,
                onAbrirDetalhes = onAbrirDetalhes,
                onArquivar = onArquivar,
                onReabrir = onReabrir,
                onExcluir = onExcluir,
                refrescando = refrescando,
                aoAtualizar = aoAtualizar,
            )
        }
    }
}

/**
 * Aba Fechados (Story 1.5): lista real das encomendas fechadas, reutilizando a
 * row da lista. Estado vazio se não houver nada. Como toda linha fechada só
 * oferece "Reabrir" (via menu/swipe), o callback [onArquivar] é ignorado pelas
 * linhas fechadas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FechadosScreen(
    encomendas: List<Encomenda>,
    onAbrirDetalhes: (Encomenda) -> Unit,
    onArquivar: (Encomenda) -> Unit,
    onReabrir: (Encomenda) -> Unit,
    onExcluir: (Encomenda) -> Unit,
    refrescando: Boolean = false,
    aoAtualizar: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        PullToRefreshBox(
            isRefreshing = refrescando,
            onRefresh = aoAtualizar,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (encomendas.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "Nenhuma encomenda concluída ainda. Quando uma chegar, ela aparece aqui.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(encomendas, key = { it.id }) { encomenda ->
                        EncomendaRow(
                            encomenda = encomenda,
                            onClick = { onAbrirDetalhes(encomenda) },
                            onArquivar = onArquivar,
                            onReabrir = onReabrir,
                            onRepetir = {},
                            onExcluir = { onExcluir(encomenda) },
                        )
                    }
                }
            }
        }
    }
}

