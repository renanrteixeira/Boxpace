package com.boxpace.presentation.ui

import com.boxpace.domain.Transportadora
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
