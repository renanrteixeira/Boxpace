package com.boxpace.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
    onRepetir: (Encomenda) -> Unit = {},
    onExcluir: (Encomenda) -> Unit = {},
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
            0 -> AtivosScreen(
                encomendas = encomendasAtivas,
                onAdicionar = onAdicionar,
                onAbrirDetalhes = onAbrirDetalhes,
                onRepetir = onRepetir,
                onExcluir = onExcluir,
            )
            else -> FechadosScreen(encomendas = encomendasFechadas, onAbrirDetalhes = onAbrirDetalhes)
        }
    }
}

/**
 * Aba Fechados (Story 1.5): lista real das encomendas fechadas, reutilizando a
 * row da lista. Estado vazio se não houver nada.
 */
@Composable
private fun FechadosScreen(
    encomendas: List<Encomenda>,
    onAbrirDetalhes: (Encomenda) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        if (encomendas.isEmpty()) {
            Text(
                text = "Nenhuma encomenda concluída ainda. Quando uma chegar, ela aparece aqui.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(encomendas, key = { it.id }) { encomenda ->
                    FechadaRow(encomenda, onClick = { onAbrirDetalhes(encomenda) })
                }
            }
        }
    }
}

@Composable
private fun FechadaRow(
    encomenda: Encomenda,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val empilha = fontScale >= 2.0f

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (empilha) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = encomenda.etiqueta,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = Int.MAX_VALUE,
                )
                Text(
                    text = "${encomenda.codigo} · ${encomenda.transportadora.nomeExibicao()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = Int.MAX_VALUE,
                )
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = encomenda.etiqueta,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = Int.MAX_VALUE,
                    )
                    Text(
                        text = "${encomenda.codigo} · ${encomenda.transportadora.nomeExibicao()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = Int.MAX_VALUE,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Fechada",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
