package com.boxpace.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.boxpace.domain.Transportadora
import com.boxpace.presentation.vm.AdicionarEncomendaViewModel.Form
import com.boxpace.presentation.vm.AdicionarEncomendaViewModel

private val Acento = Color(0xFFEE6E34)
private val TintaSobreAcento = Color(0xFF2B1000)

/**
 * Dialog de adição da aba Ativos (Story 1.3): código + transportadora + etiqueta
 * (obrigatória) e CPF/CNPJ apenas para J&T (teclado seguro + mascarado).
 *
 * Estados: padrão (2 campos), carregando (`Ainda procurando sua entrega…` com
 * spinner), erro (mensagem clara, campos preservados, dialog permanece aberto).
 */
@Composable
fun AdicionarEncomendaDialog(
    form: Form,
    onCodigoMudou: (String) -> Unit,
    onTransportadoraMudou: (Transportadora) -> Unit,
    onEtiquetaMudou: (String) -> Unit,
    onCpfMudou: (String) -> Unit,
    onConfirmar: () -> Unit,
    onFechar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val podeConfirmar =
        !form.carregando &&
            form.etiqueta.trim().isNotEmpty() &&
            form.codigo.trim().isNotEmpty()

    AlertDialog(
        modifier = modifier,
        onDismissRequest = { if (!form.carregando) onFechar() },
        title = { Text("Adicionar encomenda") },
        text = {
            Column {
                Text("Transportadora", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow {
                    Transportadora.entries.forEachIndexed { index, transportadora ->
                        SegmentedButton(
                            selected = form.transportadora == transportadora,
                            onClick = { onTransportadoraMudou(transportadora) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = Transportadora.entries.size),
                            enabled = !form.carregando,
                        ) {
                            Text(
                                text = if (transportadora == Transportadora.CORREIOS) "Correios" else "J&T",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = form.codigo,
                    onValueChange = onCodigoMudou,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Código de rastreio") },
                    placeholder = { Text("BR123456789BR") },
                    singleLine = true,
                    enabled = !form.carregando,
                )

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = form.etiqueta,
                    onValueChange = onEtiquetaMudou,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nome do produto (etiqueta)") },
                    placeholder = { Text("Fone de ouvido") },
                    singleLine = true,
                    enabled = !form.carregando,
                )

                if (form.transportadora == Transportadora.JT) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = form.cpf,
                        onValueChange = onCpfMudou,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("CPF/CNPJ do destinatário") },
                        placeholder = { Text("123.456.789-09") },
                        singleLine = true,
                        enabled = !form.carregando,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }

                if (form.carregando) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (form.aguardandoServidor) {
                                "Aguardando o servidor acordar… isso leva até 30 s na primeira vez"
                            } else {
                                "Ainda procurando sua entrega…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                form.erro?.let { erro ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = erro,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmar,
                enabled = podeConfirmar,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Acento,
                    contentColor = TintaSobreAcento,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text("Adicionar e rastrear")
            }
        },
        dismissButton = {
            TextButton(onClick = onFechar, enabled = !form.carregando) {
                Text("Cancelar")
            }
        },
    )
}