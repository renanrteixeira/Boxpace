package com.boxpace.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Espelho persistido (Room) da [Encomenda] do domínio. O domínio não conhece
 * esta entidade — a conversão fica em `EncomendaLocalRepository` (mappers).
 */
@Entity(
    tableName = "encomendas",
    indices = [Index(value = ["codigo", "transportadora"], unique = true)],
)
data class EncomendaEntity(
    @PrimaryKey val id: String,
    val codigo: String,
    val transportadora: String,
    val etiqueta: String,
    val ultimoStatus: String?,
    val statusEntregue: Boolean,
    val criadaEm: String,
    val atualizadaEm: String,
    val fechadaEm: String?,
    val cpfDestinatario: String?,
    val buscasSemEventos: Int = 0,
)

/**
 * Evento de timeline — PK composta por `(idEncomenda, data, descricao, unidade)`
 * para append idempotente (AD-IDENTIDADE): re-sync não duplica eventos.
 */
@Entity(
    tableName = "eventos",
    primaryKeys = ["idEncomenda", "data", "descricao", "unidade"],
    indices = [Index(value = ["idEncomenda"])],
)
data class EventoEntity(
    val idEncomenda: String,
    val data: String,
    val descricao: String,
    val cidade: String?,
    val uf: String?,
    val unidade: String,
)

/** Junção 1-N restaurando a encomenda com seus eventos (timeline). */
data class EncomendaComEventos(
    @Embedded val encomenda: EncomendaEntity,
    @Relation(parentColumn = "id", entityColumn = "idEncomenda")
    val eventos: List<EventoEntity>,
)
