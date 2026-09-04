package com.boxpace.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.boxpace.presentation.vm.AdicionarEncomendaViewModel

private val Acento = Color(0xFFEE6E34)
private val TintaSobreAcento = Color(0xFF2B1000)
private val VerdeSucesso = Color(0xFF1E7F4F)
private val CinzaSemDados = Color(0xFF616161)

/**
 * Aba **Ativos** (Story 1.4): lista em memória agregada pelo ViewModel, search
 * bar que filtra em tempo real por etiqueta OU código, rows em 2 níveis e FAB `+`.
 */
@Composable
fun AtivosScreen(
    encomendas: List<Encomenda>,
    onAdicionar: () -> Unit,
    onAbrirDetalhes: (Encomenda) -> Unit,
    onArquivar: (Encomenda) -> Unit = {},
    onReabrir: (Encomenda) -> Unit = {},
    onRepetir: (Encomenda) -> Unit = {},
    onExcluir: (Encomenda) -> Unit = {},
    onAbrirConfiguracoes: () -> Unit = {},
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
            Row(
                Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Encomendas",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAbrirConfiguracoes) { Text("Configurações") }
            }

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
                            EncomendaRow(
                                encomenda = encomenda,
                                onClick = { onAbrirDetalhes(encomenda) },
                                onArquivar = { onArquivar(encomenda) },
                                onReabrir = { onReabrir(encomenda) },
                                onRepetir = { onRepetir(encomenda) },
                                onExcluir = { onExcluir(encomenda) },
                            )
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
internal fun EncomendaRow(
    encomenda: Encomenda,
    onClick: () -> Unit,
    onArquivar: (Encomenda) -> Unit,
    onReabrir: (Encomenda) -> Unit,
    onRepetir: (Encomenda) -> Unit,
    onExcluir: (Encomenda) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val empilha = fontScale >= 2.0f
    val semDados = encomenda.eventos.isEmpty() &&
        encomenda.buscasSemEventos >= AdicionarEncomendaViewModel.SEM_DADOS_BUSCAS

    // Confirmação leve (UX-DR5, FR6) antes de excluir — vale para os dois
    // gatilhos da row: o item "Excluir" do menu `⋮` e o botão "Excluir" do
    // badge "Sem dados".
    var confirmandoExclusao by rememberSaveable { mutableStateOf(false) }

    // Swipe é atalho redundante (UX-DR7): dispara a mesma ação do menu ⋮.
    // Arquiva quando ativa, reabre quando fechada. `onDismiss` dispara a ação
    // após o gesto; a row sai da aba atual de forma reativa (o `onArquivar`/
    // `onReabrir` move a encomenda para a outra aba).
    val dismissState = rememberSwipeToDismissBoxState()

    SwipeToDismissBox(
        state = dismissState,
        onDismiss = {
            if (encomenda.fechadaEm == null) onArquivar(encomenda) else onReabrir(encomenda)
        },
        backgroundContent = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = if (encomenda.fechadaEm == null) "Arquivar" else "Reabrir",
                        style = MaterialTheme.typography.labelLarge,
                        color = Acento,
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Surface(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                if (empilha) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = formatarHorario(encomenda.atualizadaEm),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            RowMenu(
                                encomenda = encomenda,
                                onArquivar = onArquivar,
                                onReabrir = onReabrir,
                                onExcluir = { confirmandoExclusao = true },
                            )
                        }
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
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
                        Spacer(Modifier.width(4.dp))
                        RowMenu(
                            encomenda = encomenda,
                            onArquivar = onArquivar,
                            onReabrir = onReabrir,
                            onExcluir = { confirmandoExclusao = true },
                        )
                    }
                }
            }

            if (semDados && encomenda.fechadaEm == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onRepetir(encomenda) }) { Text("Repetir") }
                    TextButton(onClick = { confirmandoExclusao = true }) { Text("Excluir") }
                }
            }
        }
    }

    ConfirmarExclusaoDialog(
        mostrar = confirmandoExclusao,
        aoCancelar = { confirmandoExclusao = false },
        aoConfirmar = {
            confirmandoExclusao = false
            onExcluir(encomenda)
        },
    )
}

/**
 * Menu `⋮` de ciclo de vida da linha (FR5): "Arquivar"/"Reabrir" conforme
 * [Encomenda.estaFechada] + "Excluir". As ações tocáveis sempre estão presentes
 * (UX-DR7, NFR12) — mesmo com o swipe disponível como atalho.
 */
@Composable
private fun RowMenu(
    encomenda: Encomenda,
    onArquivar: (Encomenda) -> Unit,
    onReabrir: (Encomenda) -> Unit,
    onExcluir: (Encomenda) -> Unit,
) {
    var aberto by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { aberto = true }) {
            MoreVertIcon()
        }
        DropdownMenu(
            expanded = aberto,
            onDismissRequest = { aberto = false },
        ) {
            if (encomenda.fechadaEm == null) {
                DropdownMenuItem(
                    text = { Text("Arquivar") },
                    onClick = {
                        aberto = false
                        onArquivar(encomenda)
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Reabrir") },
                    onClick = {
                        aberto = false
                        onReabrir(encomenda)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Excluir") },
                onClick = {
                    aberto = false
                    onExcluir(encomenda)
                },
            )
        }
    }
}

/** Ícone `⋮` (mais opções) desenhado à mão, seguindo o padrão de [LupaIcon]. */
@Composable
private fun MoreVertIcon(modifier: Modifier = Modifier) {
    val cor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier
            .size(24.dp)
            .semantics { contentDescription = "Mais ações" },
    ) {
        val raio = size.minDimension * 0.10f
        val cx = size.width / 2f
        drawCircle(color = cor, radius = raio, center = Offset(cx, size.height * 0.25f))
        drawCircle(color = cor, radius = raio, center = Offset(cx, size.height * 0.5f))
        drawCircle(color = cor, radius = raio, center = Offset(cx, size.height * 0.75f))
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
        encomenda.buscasSemEventos >= AdicionarEncomendaViewModel.SEM_DADOS_BUSCAS ->
            BadgeSpec("Sem dados", "!", CinzaSemDados, Color.White)
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
