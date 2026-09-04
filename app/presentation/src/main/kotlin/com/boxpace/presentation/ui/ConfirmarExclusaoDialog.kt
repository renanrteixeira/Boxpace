package com.boxpace.presentation.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun ConfirmarExclusaoDialog(
    mostrar: Boolean,
    aoCancelar: () -> Unit,
    aoConfirmar: () -> Unit,
) {
    if (mostrar) {
        AlertDialog(
            onDismissRequest = aoCancelar,
            title = { Text("Excluir definitivamente?") },
            text = {
                Text("Essa encomenda será removida da sua lista. Não dá pra desfazer.")
            },
            confirmButton = {
                TextButton(onClick = aoConfirmar) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = aoCancelar) { Text("Cancelar") }
            },
        )
    }
}
