package com.boxpace.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boxpace.domain.Encomenda

private val Acento = Color(0xFFEE6E34)
private val TintaSobreAcento = Color(0xFF2B1000)
private val VerdeSucesso = Color(0xFF1E7F4F)

/**
 * Aba **Ativos** (mínimo para adicionar, Story 1.3): lista em memória agregada
 * pelo ViewModel + FAB `+`. Nesta story ainda não há busca, detalhes nem
 * fechados (Stories 1.4/1.5).
 */
@Composable
fun AtivosScreen(
    encomendas: List<Encomenda>,
    onAdicionar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdicionar,
                containerColor = Acento,
                contentColor = TintaSobreAcento,
            ) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            Text(
                text = "Encomendas",
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(Modifier.height(16.dp))

            if (encomendas.isEmpty()) {
                Text(
                    text = "Nenhuma encomenda ativa — toque em + para começar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(encomendas, key = { it.id }) { encomenda ->
                        EncomendaRow(encomenda)
                    }
                }
            }
        }
    }
}

@Composable
private fun EncomendaRow(
    encomenda: Encomenda,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = encomenda.etiqueta,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = encomenda.codigo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(encomenda)
        }
    }
}

@Composable
private fun StatusBadge(
    encomenda: Encomenda,
    modifier: Modifier = Modifier,
) {
    val badge = when {
        encomenda.statusEntregue -> BadgeSpec("Chegou!", "✓", VerdeSucesso, Color.White)
        encomenda.eventos.isNotEmpty() -> BadgeSpec("Em trânsito", "↗", Acento, TintaSobreAcento)
        else -> null
    }

    if (badge != null) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(999.dp),
            color = badge.corFundo,
            contentColor = badge.corTexto,
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(badge.glyph, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = badge.texto,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private class BadgeSpec(
    val texto: String,
    val glyph: String,
    val corFundo: Color,
    val corTexto: Color,
)