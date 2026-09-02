package com.boxpace.presentation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boxpace.domain.Encomenda
import com.boxpace.domain.Evento
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Acento = Color(0xFFEE6E34)
private val TintaSobreAcento = Color(0xFF2B1000)
private val VerdeSucesso = Color(0xFF1E7F4F)

private val TipoDataHora: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")

/**
 * Tela Detalhes (Story 1.5): etiqueta + transportadora no topo, badge do último
 * status destacado e timeline vertical cronológica com node laranja no passo
 * atual. Ações de ciclo de vida: arquivar/reabrir e excluir (com confirmação).
 */
@Composable
fun DetalhesScreen(
    encomenda: Encomenda,
    onArquivar: () -> Unit,
    onReabrir: () -> Unit,
    onExcluir: () -> Unit,
    onVoltar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmandoExclusao by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        TextButton(onClick = onVoltar) {
            Text("← Voltar")
        }

        // Topo: etiqueta + transportadora
        Text(
            text = encomenda.etiqueta,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${encomenda.codigo} · ${encomenda.transportadora.nomeExibicao()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        // Badge do último status destacado
        StatusBadge(encomenda)

        Spacer(Modifier.height(16.dp))

        // Ações de ciclo de vida
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (encomenda.fechadaEm == null) {
                TextButton(onClick = onArquivar) { Text("Arquivar") }
            } else {
                TextButton(onClick = onReabrir) { Text("Reabrir") }
            }
            TextButton(onClick = { confirmandoExclusao = true }) { Text("Excluir") }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Histórico",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(8.dp))

        if (encomenda.eventos.isEmpty()) {
            Text(
                text = "Sem eventos ainda.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Timeline(encomenda.eventos, Modifier.weight(1f))
        }
    }

    if (confirmandoExclusao) {
        AlertDialog(
            onDismissRequest = { confirmandoExclusao = false },
            title = { Text("Excluir definitivamente?") },
            text = {
                Text("Essa encomenda será removida da sua lista. Não dá pra desfazer.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmandoExclusao = false
                        onExcluir()
                    },
                ) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { confirmandoExclusao = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun Timeline(
    eventos: List<Evento>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        itemsIndexed(eventos) { index, evento ->
            TimelineItem(
                evento = evento,
                ultimo = index == eventos.lastIndex,
            )
        }
    }
}

@Composable
private fun TimelineItem(
    evento: Evento,
    ultimo: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // Coluna do node
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Node(ultimo)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = formatarDataHora(evento.data),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = evento.descricao,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            val cidadeUf = listOfNotNull(evento.cidade, evento.uf).joinToString("/").ifEmpty { null }
            val local = listOfNotNull(cidadeUf, evento.unidade).joinToString(" · ")
            if (local.isNotEmpty()) {
                Text(
                    text = local,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Node(ultimo: Boolean) {
    val cor = if (ultimo) Acento else MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(12.dp)) {
        drawCircle(color = cor, radius = size.minDimension * 0.5f)
    }
}

private fun formatarDataHora(iso: String): String {
    return try {
        Instant.parse(iso)
            .atZone(ZoneId.systemDefault())
            .format(TipoDataHora)
    } catch (_: Exception) {
        iso
    }
}

@Composable
private fun StatusBadge(
    encomenda: Encomenda,
    modifier: Modifier = Modifier,
) {
    val badge = when {
        encomenda.statusEntregue -> StatusBadgeSpec("Chegou!", "✓", VerdeSucesso, Color.White)
        encomenda.eventos.isNotEmpty() -> StatusBadgeSpec("Em trânsito", "↗", Acento, TintaSobreAcento)
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

private class StatusBadgeSpec(
    val texto: String,
    val glyph: String,
    val corFundo: Color,
    val corTexto: Color,
)
