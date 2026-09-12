package com.boxpace.presentation.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boxpace.domain.Encomenda
import com.boxpace.domain.Evento
import com.boxpace.presentation.ui.theme.coresBadgeSucesso
import java.time.format.DateTimeFormatter

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
    refrescando: Boolean = false,
    aoAtualizar: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var confirmandoExclusao by rememberSaveable { mutableStateOf(false) }

    BackHandler { onVoltar() }

    PullToRefreshBox(
        isRefreshing = refrescando,
        onRefresh = aoAtualizar,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedButton(onClick = onVoltar) {
                Text("← Voltar")
            }

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
                    FilledTonalButton(onClick = onArquivar) { Text("Arquivar") }
                } else {
                    FilledTonalButton(onClick = onReabrir) { Text("Reabrir") }
                }
                OutlinedButton(
                    onClick = { confirmandoExclusao = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Excluir")
                }
            }

            Spacer(Modifier.height(16.dp))

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
                Timeline(encomenda.eventos, Modifier.fillMaxWidth())
            }
        }
    }

    ConfirmarExclusaoDialog(
        mostrar = confirmandoExclusao,
        aoCancelar = { confirmandoExclusao = false },
        aoConfirmar = {
            confirmandoExclusao = false
            onExcluir()
        },
    )
}

@Composable
private fun Timeline(
    eventos: List<Evento>,
    modifier: Modifier = Modifier,
) {
    val ordenados = remember(eventos) { ordenarEventos(eventos) }
    Column(modifier = modifier.fillMaxWidth()) {
        ordenados.forEachIndexed { index, evento ->
            TimelineItem(
                evento = evento,
                ultimo = index == 0,
            )
        }
    }
}

/**
 * Ordena a timeline somente para exibição: mais recente → mais antigo, com
 * datas não-parseáveis caindo no fim (ordem depois dos parseáveis). O sort é
 * estável (`sortedWith` → TimSort), então empates de data preservam a sequência
 * de origem.
 *
 * Persistência/domínio continuam em ordem cronológica ascendente (contrato do
 * scraper) — esta ordenação nunca alimenta dedup/round-trip/`ultimoStatus`.
 */
internal fun ordenarEventos(eventos: List<Evento>): List<Evento> =
    eventos.sortedWith(compareByDescending { parseZonedDateTime(it.data) })

@Composable
private fun TimelineItem(
    evento: Evento,
    ultimo: Boolean,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Node(ultimo)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = formatarDataHora(evento.data),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = evento.descricao,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun Node(ultimo: Boolean) {
    val cor = if (ultimo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(12.dp)) {
        drawCircle(color = cor, radius = size.minDimension * 0.28f)
    }
}

private fun formatarDataHora(iso: String): String =
    parseZonedDateTime(iso)?.format(TipoDataHora) ?: iso

@Composable
private fun StatusBadge(encomenda: Encomenda) {
    val spec = quando(encomenda) ?: return
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = spec.corFundo,
        contentColor = spec.corTexto,
    ) {
        Row(
            Modifier
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = spec.glyph,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = spec.texto,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private data class StatusBadgeSpec(
    val texto: String,
    val glyph: String,
    val corFundo: Color,
    val corTexto: Color,
)

@Composable
private fun quando(encomenda: Encomenda): StatusBadgeSpec? {
    if (encomenda.eventos.isEmpty()) return null
    return if (encomenda.statusEntregue) {
        StatusBadgeSpec(
            texto = "Chegou!",
            glyph = "✓",
            corFundo = MaterialTheme.colorScheme.primaryContainer,
            corTexto = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        StatusBadgeSpec(
            texto = "Em trânsito",
            glyph = "↗",
            corFundo = coresBadgeSucesso(MaterialTheme.colorScheme).fundo,
            corTexto = coresBadgeSucesso(MaterialTheme.colorScheme).texto,
        )
    }
}
