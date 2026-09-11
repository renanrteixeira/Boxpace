package com.boxpace.presentation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun BoxpaceTopBar(
    contaVinculada: Boolean,
    contaEmail: String?,
    onAbrirDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onAbrirDrawer) {
                HamburgerIcon()
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Boxpace",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.weight(1f))

            ContaAvatar(
                vinculada = contaVinculada,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .widthIn(max = 220.dp),
            ) {
                Text(
                    text = if (contaVinculada) "Conta vinculada" else "Sem vínculo",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (contaVinculada && contaEmail != null) {
                    Text(
                        text = contaEmail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContaAvatar(
    vinculada: Boolean,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (vinculada) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.outline
    }
    val glyph = if (vinculada) "\u2713" else "?"

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun HamburgerIcon(modifier: Modifier = Modifier) {
    val cor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier
            .size(24.dp)
            .semantics { contentDescription = "Menu" },
    ) {
        val strokeWidth = size.minDimension * 0.08f
        val lineY = listOf(size.height * 0.3f, size.height * 0.5f, size.height * 0.7f)
        val startX = size.width * 0.2f
        val endX = size.width * 0.8f

        lineY.forEach { y ->
            drawLine(
                color = cor,
                start = Offset(startX, y),
                end = Offset(endX, y),
                strokeWidth = strokeWidth,
            )
        }
    }
}
