package com.boxpace.data.remote

import com.boxpace.domain.Evento
import kotlinx.serialization.Serializable

/**
 * DTO do contrato fixo `POST /rastrear` (AD-SCRAPER-CONTRACT).
 *
 * `data/remote` é o único tradutor da fronteira HTTP: serializa/desserializa
 * este shape (request `{ transportadora, codigo, cpf? }`, response
 * `{ codigo, eventos[] }`) e o converte para o [Evento] de domínio via
 * [RastreioMapper].
 */
@Serializable
data class ContratoRastrearRequest(
    val transportadora: String,
    val codigo: String,
    val cpf: String? = null,
)

@Serializable
data class ContratoEventoDto(
    val data: String,
    val descricao: String,
    val cidade: String? = null,
    val uf: String? = null,
    val unidade: String? = null,
)

@Serializable
data class ContratoRastrearResponse(
    val codigo: String,
    val eventos: List<ContratoEventoDto> = emptyList(),
)

/**
 * Mapper único do contrato do scraper para o domínio (AD-SCRAPER-CONTRACT).
 */
object RastreioMapper {
    fun paraEvento(dto: ContratoEventoDto): Evento = Evento(
        data = dto.data,
        descricao = dto.descricao,
        cidade = dto.cidade,
        uf = dto.uf,
        unidade = dto.unidade,
    )
}