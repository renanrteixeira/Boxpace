package com.boxpace.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EncomendaDao {

    @Upsert
    suspend fun upsert(encomenda: EncomendaEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun inserirEventos(eventos: List<EventoEntity>): List<Long>

    @Transaction
    @Query("SELECT * FROM encomendas ORDER BY atualizadaEm DESC, criadaEm DESC")
    fun observeAll(): Flow<List<EncomendaComEventos>>

    @Query("SELECT * FROM encomendas WHERE id = :id")
    suspend fun buscarPorId(id: String): EncomendaEntity?

    @Query("SELECT * FROM encomendas WHERE codigo = :codigo AND transportadora = :transportadora")
    suspend fun buscarPorCodigo(codigo: String, transportadora: String): EncomendaEntity?

    @Query("SELECT * FROM encomendas ORDER BY atualizadaEm DESC, criadaEm DESC")
    suspend fun listar(): List<EncomendaEntity>

    @Query("SELECT * FROM eventos WHERE idEncomenda = :idEncomenda")
    suspend fun eventosDe(idEncomenda: String): List<EventoEntity>

    @Query("DELETE FROM encomendas WHERE id = :id")
    suspend fun excluir(id: String)

    @Query("DELETE FROM eventos WHERE idEncomenda = :idEncomenda")
    suspend fun excluirEventos(idEncomenda: String)

    /** Exclui Fechados cujo `fechadaEm` seja anterior a [limiteIso] (ISO-8601). */
    @Query("DELETE FROM encomendas WHERE fechadaEm IS NOT NULL AND fechadaEm < :limiteIso")
    suspend fun purgarFechadasAntigas(limiteIso: String): Int

    @Query("DELETE FROM eventos WHERE idEncomenda IN (SELECT id FROM encomendas WHERE fechadaEm IS NOT NULL AND fechadaEm < :limiteIso)")
    suspend fun purgarEventosDeFechadasAntigas(limiteIso: String): Int
}

@Dao
interface DeltaPendenteDao {

    @Insert
    suspend fun inserir(delta: DeltaPendenteEntity): Long

    @Query("SELECT * FROM deltasPendentes ORDER BY criadoEm ASC")
    suspend fun listar(): List<DeltaPendenteEntity>

    @Query("DELETE FROM deltasPendentes")
    suspend fun limpar()

    @Delete
    suspend fun excluir(delta: DeltaPendenteEntity)
}
