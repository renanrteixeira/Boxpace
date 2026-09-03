package com.boxpace.presentation.ui

import com.boxpace.domain.Transportadora
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val HoraFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")

/**
 * Converte um ISO-8601 para [ZonedDateTime] tolerante à ausência de offset:
 * strings com fuso/`Z` (ex. `2026-09-01T12:00:00Z`) viram [Instant]; strings
 * naive (ex. `2026-09-01T10:00:00`, sem fuso — o contrato do scraper) são
 * interpretadas como hora local. Retorna `null` para entrada não-ISO.
 */
internal fun parseZonedDateTime(iso: String): ZonedDateTime? {
    return try {
        val instante = Instant.parse(iso).atZone(ZoneId.systemDefault())
        return instante
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(iso).atZone(ZoneId.systemDefault())
        } catch (_: Exception) {
            null
        }
    }
}

internal fun formatarHorario(iso: String): String {
    return parseZonedDateTime(iso)?.format(HoraFormatter) ?: ""
}

internal fun Transportadora.nomeExibicao(): String = when (this) {
    Transportadora.CORREIOS -> "Correios"
    Transportadora.JT -> "J&T"
}
