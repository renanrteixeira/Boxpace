package com.boxpace.data.cloud

import com.boxpace.domain.Encomenda
import com.boxpace.domain.Evento
import com.boxpace.domain.Transportadora
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serialização única do arquivo canônico `boxpace.json` no App Data Folder
 * (Drive) — a forma descrita em `drive-file-model.md`. Nada além desta classe
 * serializa o canônico (AD-SCHEMA-DRIVE); o round-trip é o contrato de CAP-3.
 *
 * Um único caminho de serialização da entidade de domínio aqui em `data/cloud`
 * (nunca replay do DTO do scraper).
 */
object SchemaBoxpace {

    /** Versão de schema emitida por este app. Arquivo > [SCHEMA_VERSION] ⇒ recusa sobrescrever. */
    const val SCHEMA_VERSION = 1

    /** Nome fixo do arquivo canônico na raiz do App Data Folder (AD-SYNC-4). */
    const val NOME_ARQUIVO = "boxpace.json"

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun decodificar(conteudo: String): BoxpaceArquivo =
        json.decodeFromString(BoxpaceArquivo.serializer(), conteudo)

    fun codificar(arquivo: BoxpaceArquivo): String =
        json.encodeToString(BoxpaceArquivo.serializer(), arquivo)

    /** Encomenda de domínio → registro vivo canônico. [fechadaEm] vira [Fechado]. */
    fun encomendaParaRegistro(encomenda: Encomenda): RegistroEncomenda = RegistroEncomenda(
        codigo = encomenda.codigo,
        transportadora = encomenda.transportadora.scraperId,
        etiqueta = encomenda.etiqueta,
        ultimoStatus = encomenda.ultimoStatus,
        statusEntregue = encomenda.statusEntregue,
        fechado = encomenda.fechadaEm?.let { Fechado(valor = true, atualizadoEm = it) },
        criadaEm = encomenda.criadaEm,
        updatedAt = encomenda.atualizadaEm,
        cpfDestinatario = encomenda.cpfDestinatario,
        eventos = encomenda.eventos.map { EventoBoxpace(it.data, it.descricao, it.cidade, it.uf, it.unidade) },
        tombstone = false,
    )

    /** Registro vivo canônico → encomenda de domínio (id = "transportadora:código"). */
    fun registroParaEncomenda(registro: RegistroEncomenda): Encomenda {
        val transportadora = Transportadora.fromScraperId(registro.transportadora) ?: Transportadora.CORREIOS
        val codigo = registro.codigo
        return Encomenda(
            id = "${transportadora.scraperId}:$codigo",
            codigo = codigo,
            transportadora = transportadora,
            etiqueta = registro.etiqueta ?: codigo,
            ultimoStatus = registro.ultimoStatus,
            statusEntregue = registro.statusEntregue,
            eventos = registro.eventos.map {
                Evento(data = it.data, descricao = it.descricao, cidade = it.cidade, uf = it.uf, unidade = it.unidade)
            },
            criadaEm = registro.criadaEm ?: "",
            atualizadaEm = registro.updatedAt,
            fechadaEm = registro.fechado?.takeIf { it.valor }?.atualizadoEm,
            cpfDestinatario = registro.cpfDestinatario,
        )
    }

    /** Tombstone → registro canônico (identidade preservada para nunca ressuscitar). */
    fun tombstoneParaRegistro(codigo: String, transportadora: Transportadora, updatedAt: String): RegistroEncomenda =
        RegistroEncomenda(
            codigo = codigo,
            transportadora = transportadora.scraperId,
            updatedAt = updatedAt,
            tombstone = true,
        )
}

/** Contorno top-level do canônico. */
@Serializable
data class BoxpaceArquivo(
    val schemaVersion: Int = SchemaBoxpace.SCHEMA_VERSION,
    val encomendas: List<RegistroEncomenda> = emptyList(),
    val preferencias: List<RegistroPreferencia> = emptyList(),
)

/** Registro de encomenda — vivo (`tombstone == false`) ou tombstone (`tombstone == true`). */
@Serializable
data class RegistroEncomenda(
    val codigo: String,
    val transportadora: String,
    val etiqueta: String? = null,
    val ultimoStatus: String? = null,
    val statusEntregue: Boolean = false,
    val fechado: Fechado? = null,
    val criadaEm: String? = null,
    val updatedAt: String,
    val cpfDestinatario: String? = null,
    val eventos: List<EventoBoxpace> = emptyList(),
    val tombstone: Boolean = false,
)

/** `fechado` é 1ª classe: valor + atualizadoEm (AD-FECHADO), distinto de `entregue`. */
@Serializable
data class Fechado(
    val valor: Boolean,
    val atualizadoEm: String,
)

@Serializable
data class EventoBoxpace(
    val data: String,
    val descricao: String,
    val cidade: String? = null,
    val uf: String? = null,
    val unidade: String? = null,
)

/** Um registro por chave (`preferencias:<nome>`), LWW por `updatedAt` (AD-PREFS-GRANULARIDADE). */
@Serializable
data class RegistroPreferencia(
    val chave: String,
    val valor: String,
    val updatedAt: String,
)
