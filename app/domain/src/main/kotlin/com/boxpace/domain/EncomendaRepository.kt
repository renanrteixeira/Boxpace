package com.boxpace.domain

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Porta de saída para persistir e consultar [Encomenda].
 *
 * Implementações vivem em `data/` (local Room, cloud Drive). O domínio só conhece
 * esta interface — nada de Room, HTTP ou Drive aqui.
 */
interface EncomendaRepository {
    suspend fun salvar(encomenda: Encomenda)

    /**
     * Persiste [encomenda] e registra o [DeltaPendente.Salvar] correspondente em
     * uma única operação — consolida o padrão `salvar` + `registrarDeltaPendente`
     * que antes estava duplicado no ViewModel e no UseCase.
     *
     * Retorna `false` se a escrita falhar (conservador: não lança exceção).
     */
    suspend fun salvarComDelta(encomenda: Encomenda): Boolean

    suspend fun buscarPorId(id: String): Encomenda?
    suspend fun buscarPorCodigo(codigo: String, transportadora: Transportadora): Encomenda?
    suspend fun listar(): List<Encomenda>
    suspend fun listarAtivas(): List<Encomenda>
    suspend fun listarFechadas(): List<Encomenda>
    suspend fun excluir(id: String, criadoEm: String = Instant.now().toString())

    /** Observa reativamente todas as encomendas persistidas (Room emite). */
    fun observar(): Flow<List<Encomenda>>

    suspend fun registrarDeltaPendente(delta: DeltaPendente)
    suspend fun listarDeltasPendentes(): List<DeltaPendente>
    suspend fun limparDeltasPendentes()

    /** Apaga do armazenamento local Fechados com `fechadaEm` mais antigo que [dias]. */
    suspend fun purgarFechadasAntigas(dias: Int)
}
