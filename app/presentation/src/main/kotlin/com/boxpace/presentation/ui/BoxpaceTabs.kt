package com.boxpace.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
    encomendas: List<Encomenda>,
    onAdicionar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var aba by rememberSaveable { mutableStateOf(0) }
    val abas = listOf("Ativos", "Fechados")

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = aba) {
            abas.forEachIndexed { index, titulo ->
                Tab(
                    selected = aba == index,
                    onClick = { aba = index },
                    text = { Text(titulo) },
                )
            }
        }

        when (aba) {
            0 -> AtivosScreen(encomendas = encomendas, onAdicionar = onAdicionar)
            else -> FechadosScreen()
        }
    }
}

/**
 * Aba Fechados (Story 1.4): apenas o estado vazio. A lista real de fechados é
 * Story 1.5/1.6.
 */
@Composable
private fun FechadosScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Nenhuma encomenda concluída ainda. Quando uma chegar, ela aparece aqui.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
