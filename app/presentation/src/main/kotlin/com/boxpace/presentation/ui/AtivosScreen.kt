package com.boxpace.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.boxpace.domain.Encomenda
import com.boxpace.domain.Transportadora
import com.boxpace.presentation.vm.AdicionarEncomendaViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Acento = Color(0xFFEE6E34)
private val TintaSobreAcento = Color(0xFF2B1000)
private val VerdeSucesso = Color(0xFF1E7F4F)

/**
 * Aba **Ativos** (Story 1.4): lista em memória agregada pelo ViewModel, search
 * bar que filtra em tempo real por etiqueta OU código, rows em 2 níveis e FAB `+`.
 */
@Composable
fun AtivosScreen(
    encomendas: List<Encomenda>,
    onAdicionar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var termo by rememberSaveable { mutableStateOf("") }
    val filtradas = AdicionarEncomendaViewModel.filtrarBusca(encomendas, termo)

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

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = termo,
                onValueChange = { termo = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Buscar por etiqueta ou código") },
                leadingIcon = {
                    LupaIcon()
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )

            Spacer(Modifier.height(16.dp))

            when {
                encomendas.isEmpty() -> {
                    EmptyState("Nenhuma encomenda ativa — toque em + para começar.")
                }
                filtradas.isEmpty() -> {
                    EmptyState("Nenhuma encomenda com esse nome ou código.")
                }
                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filtradas, key = { it.id }) { encomenda ->
                            EncomendaRow(encomenda)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(texto: String) {
    Text(
        text = texto,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LupaIcon(modifier: Modifier = Modifier) {
    val cor = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier
            .size(24.dp)
            .semantics { contentDescription = "Buscar" },
    ) {
        val stroke = Stroke(width = size.minDimension * 0.12f)
        drawCircle(
            color = cor,
            radius = size.minDimension * 0.28f,
            style = stroke,
        )
        drawLine(
            color = cor,
            start = Offset(size.width * 0.60f, size.height * 0.60f),
            end = Offset(size.width * 0.85f, size.height * 0.85f),
            strokeWidth = size.minDimension * 0.12f,
        )
    }
}

@Composable
private fun EncomendaRow(
    encomenda: Encomenda,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val empilha = fontScale >= 2.0f

    Surface(
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
                Spacer(Modifier.height(8.dp))
                StatusBadge(encomenda)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatarHorario(encomenda.atualizadaEm),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    // etiqueta (título, sem truncar)
                    Text(
                        text = encomenda.etiqueta,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = Int.MAX_VALUE,
                    )
                    // meta: código + transportadora
                    Text(
                        text = "${encomenda.codigo} · ${encomenda.transportadora.nomeExibicao()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = Int.MAX_VALUE,
                    )
                }
                // à direita: badge + horário
                Column(horizontalAlignment = Alignment.End) {
                    StatusBadge(encomenda)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = formatarHorario(encomenda.atualizadaEm),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val HoraFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")

internal fun formatarHorario(iso: String): String {
    return try {
        Instant.parse(iso)
            .atZone(ZoneId.systemDefault())
            .format(HoraFormatter)
    } catch (_: Exception) {
        ""
    }
}

internal fun Transportadora.nomeExibicao(): String = when (this) {
    Transportadora.CORREIOS -> "Correios"
    Transportadora.JT -> "J&T"
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
