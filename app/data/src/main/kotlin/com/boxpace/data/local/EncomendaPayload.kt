package com.boxpace.data.local

import com.boxpace.domain.Encomenda
import com.boxpace.domain.Evento
import com.boxpace.domain.Transportadora
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON da encomenda para o payload de um [DeltaPendente.Salvar] (Epic 5). */
@Serializable
data class EncomendaPayloadDto(
    val id: String,
    val codigo: String,
    val transportadora: String,
    val etiqueta: String,
    val ultimoStatus: String?,
    val statusEntregue: Boolean,
    val criadaEm: String,
    val atualizadaEm: String,
    val fechadaEm: String?,
    val cpfDestinatario: String?,
    val eventos: List<EventoPayloadDto>,
)

@Serializable
data class EventoPayloadDto(
    val data: String,
    val descricao: String,
    val cidade: String? = null,
    val uf: String? = null,
    val unidade: String? = null,
)

/** Conversão encomenda de/para payload JSON (espelho de delta). */
internal object EncomendaPayloadMapper {
    fun paraJson(encomenda: Encomenda, json: Json): String =
        json.encodeToString(
            EncomendaPayloadDto.serializer(),
            EncomendaPayloadDto(
                id = encomenda.id,
                codigo = encomenda.codigo,
                transportadora = encomenda.transportadora.scraperId,
                etiqueta = encomenda.etiqueta,
                ultimoStatus = encomenda.ultimoStatus,
                statusEntregue = encomenda.statusEntregue,
                criadaEm = encomenda.criadaEm,
                atualizadaEm = encomenda.atualizadaEm,
                fechadaEm = encomenda.fechadaEm,
                cpfDestinatario = encomenda.cpfDestinatario,
                eventos = encomenda.eventos.map {
                    EventoPayloadDto(
                        data = it.data,
                        descricao = it.descricao,
                        cidade = it.cidade,
                        uf = it.uf,
                        unidade = it.unidade,
                    )
                },
            ),
        )

    fun doJson(payload: String, json: Json): Encomenda {
        val dto = json.decodeFromString(EncomendaPayloadDto.serializer(), payload)
        return Encomenda(
            id = dto.id,
            codigo = dto.codigo,
            transportadora = Transportadora.fromScraperId(dto.transportadora) ?: Transportadora.CORREIOS,
            etiqueta = dto.etiqueta,
            ultimoStatus = dto.ultimoStatus,
            statusEntregue = dto.statusEntregue,
            eventos = dto.eventos.map {
                Evento(data = it.data, descricao = it.descricao, cidade = it.cidade, uf = it.uf, unidade = it.unidade)
            },
            criadaEm = dto.criadaEm,
            atualizadaEm = dto.atualizadaEm,
            fechadaEm = dto.fechadaEm,
            cpfDestinatario = dto.cpfDestinatario,
        )
    }
}
