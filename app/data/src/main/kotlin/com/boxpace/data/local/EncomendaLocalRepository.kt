package com.boxpace.data.local

import androidx.room.withTransaction
import com.boxpace.data.di.DataModule
import com.boxpace.domain.DeltaPendente
import com.boxpace.domain.Encomenda
import com.boxpace.domain.EncomendaRepository
import com.boxpace.domain.Evento
import com.boxpace.domain.Transportadora
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Implementação local (Room) da porta [EncomendaRepository] — espelho das
 * encomendas rastreadas + deltas pendentes para o Epic 5 (Drive).
 *
 * Mappers entidade↔domínio vivem `data/local`; o domínio não conhece Room.
 */
class EncomendaLocalRepository(
    private val database: EncomendaDatabase,
    private val json: Json = DataModule.json,
) : EncomendaRepository {

    private val dao = database.encomendaDao()
    private val deltaDao = database.deltaPendenteDao()

    override suspend fun salvar(encomenda: Encomenda) {
        database.withTransaction {
            dao.upsert(encomenda.paraEntity())
            dao.excluirEventos(encomenda.id)
            dao.inserirEventos(encomenda.eventos.map { it.paraEntity(encomenda.id) })
        }
    }

    override suspend fun buscarPorId(id: String): Encomenda? {
        val entidade = dao.buscarPorId(id) ?: return null
        return entidade.paraDominio(dao.eventosDe(id))
    }

    override suspend fun buscarPorCodigo(codigo: String, transportadora: Transportadora): Encomenda? {
        val entidade = dao.buscarPorCodigo(codigo, transportadora.scraperId) ?: return null
        return entidade.paraDominio(dao.eventosDe(entidade.id))
    }

    override suspend fun listar(): List<Encomenda> =
        dao.listar().map { it.paraDominio(dao.eventosDe(it.id)) }

    override suspend fun listarAtivas(): List<Encomenda> =
        dao.listar().map { it.paraDominio(dao.eventosDe(it.id)) }.filter { it.fechadaEm == null }

    override suspend fun listarFechadas(): List<Encomenda> =
        dao.listar().map { it.paraDominio(dao.eventosDe(it.id)) }.filter { it.fechadaEm != null }

    override suspend fun excluir(id: String) {
        database.withTransaction {
            dao.excluirEventos(id)
            dao.excluir(id)
        }
    }

    override fun observar(): Flow<List<Encomenda>> =
        dao.observeAll().map { rows ->
            rows.map { it.paraDominio() }
        }

    override suspend fun registrarDeltaPendente(delta: DeltaPendente) {
        deltaDao.inserir(
            DeltaPendenteEntity(
                alvoId = delta.alvoId,
                tipo = when (delta) {
                    is DeltaPendente.Salvar -> TIPO_SALVAR
                    is DeltaPendente.Excluir -> TIPO_EXCLUIR
                },
                criadoEm = delta.criadoEm,
                payload = (delta as? DeltaPendente.Salvar)?.let { EncomendaPayloadMapper.paraJson(it.encomenda, json) },
            ),
        )
    }

    override suspend fun listarDeltasPendentes(): List<DeltaPendente> =
        deltaDao.listar().map { entidade ->
            when (entidade.tipo) {
                TIPO_SALVAR -> DeltaPendente.Salvar(
                    encomenda = EncomendaPayloadMapper
                        .doJson(entidade.payload ?: "", json),
                    alvoId = entidade.alvoId,
                    criadoEm = entidade.criadoEm,
                )
                else -> DeltaPendente.Excluir(
                    alvoId = entidade.alvoId,
                    criadoEm = entidade.criadoEm,
                )
            }
        }

    override suspend fun limparDeltasPendentes() = deltaDao.limpar()

    override suspend fun purgarFechadasAntigas(dias: Int) {
        require(dias > 0) { "dias deve ser maior que zero" }
        val limiteIso = Instant.now().minus(Duration.ofDays(dias.toLong())).toString()
        database.withTransaction {
            dao.purgarEventosDeFechadasAntigas(limiteIso)
            dao.purgarFechadasAntigas(limiteIso)
        }
    }

    private fun Encomenda.paraEntity(): EncomendaEntity = EncomendaEntity(
        id = id,
        codigo = codigo,
        transportadora = transportadora.scraperId,
        etiqueta = etiqueta,
        ultimoStatus = ultimoStatus,
        statusEntregue = statusEntregue,
        criadaEm = criadaEm,
        atualizadaEm = atualizadaEm,
        fechadaEm = fechadaEm,
        cpfDestinatario = cpfDestinatario,
    )

    private fun EncomendaEntity.paraDominio(eventos: List<EventoEntity> = emptyList()): Encomenda = Encomenda(
        id = id,
        codigo = codigo,
        transportadora = Transportadora.fromScraperId(transportadora) ?: Transportadora.CORREIOS,
        etiqueta = etiqueta,
        ultimoStatus = ultimoStatus,
        statusEntregue = statusEntregue,
        eventos = eventos.map { it.paraDominio() },
        criadaEm = criadaEm,
        atualizadaEm = atualizadaEm,
        fechadaEm = fechadaEm,
        cpfDestinatario = cpfDestinatario,
    )

    private fun EncomendaComEventos.paraDominio(): Encomenda =
        encomenda.paraDominio().copy(eventos = eventos.map { it.paraDominio() })

    private fun Evento.paraEntity(idEncomenda: String): EventoEntity = EventoEntity(
        idEncomenda = idEncomenda,
        data = data,
        descricao = descricao,
        cidade = cidade,
        uf = uf,
        unidade = unidade ?: "",
    )

    private fun EventoEntity.paraDominio(): Evento = Evento(
        data = data,
        descricao = descricao,
        cidade = cidade,
        uf = uf,
        unidade = unidade.takeIf { it.isNotEmpty() },
    )

    private companion object {
        const val TIPO_SALVAR = "salvar"
        const val TIPO_EXCLUIR = "excluir"
    }
}
