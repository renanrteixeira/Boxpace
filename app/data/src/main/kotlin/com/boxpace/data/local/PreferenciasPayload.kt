package com.boxpace.data.local

import com.boxpace.domain.Preferencias
import com.boxpace.domain.Tema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON da preferência para o payload de um [DeltaPendente.SalvarPreferencia] (Epic 5). */
@Serializable
data class PreferenciasPayloadDto(
    val tema: String,
    val updatedAt: String,
)

/** Conversão preferência de/para payload JSON (espelho de delta). */
internal object PreferenciasPayloadMapper {
    fun paraJson(preferencias: Preferencias, json: Json): String =
        json.encodeToString(
            PreferenciasPayloadDto.serializer(),
            PreferenciasPayloadDto(
                tema = preferencias.tema.id,
                updatedAt = preferencias.updatedAt,
            ),
        )

    fun doJson(payload: String, json: Json): Preferencias {
        val dto = json.decodeFromString(PreferenciasPayloadDto.serializer(), payload)
        return Preferencias(
            tema = Tema.fromId(dto.tema),
            updatedAt = dto.updatedAt,
        )
    }
}