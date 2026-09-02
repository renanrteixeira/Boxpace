package com.boxpace.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Tela **Configurações** — superfície de UI apenas (skeleton).
 *
 * Seções: **Tema** (claro/escuro) e **Sincronização com Drive**.
 * Conforme este story, não há lógica de negócio nem persistência — o estado
 * exibido é local/efêmero e a integração com tema e Drive fica para Epic 4/5.
 */
@Composable
fun ConfiguracoesScreen(modifier: Modifier = Modifier) {
    // Estado puramente de superfície para este skeleton; em Epic 4/5 vira
    // ViewModel com persistência real.
    var temaEscuro by rememberSaveable { mutableStateOf(false) }
    var driveVinculado by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "Configurações",
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Tema",
            style = MaterialTheme.typography.titleMedium,
        )
        ConfigRow(
            titulo = "Tema escuro",
            descricao = "Aplicar tema escuro. (Epic 4)",
            checked = temaEscuro,
            onCheckedChange = { temaEscuro = it },
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text(
            text = "Sincronização com Drive",
            style = MaterialTheme.typography.titleMedium,
        )
        ConfigRow(
            titulo = "Vincular conta Google",
            descricao = "Sincronizar dados no Google Drive. (Epic 5)",
            checked = driveVinculado,
            onCheckedChange = { driveVinculado = it },
        )
    }
}

@Composable
private fun ConfigRow(
    titulo: String,
    descricao: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = titulo,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = descricao,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConfiguracoesScreenPreview() {
    MaterialTheme {
        ConfiguracoesScreen()
    }
}
