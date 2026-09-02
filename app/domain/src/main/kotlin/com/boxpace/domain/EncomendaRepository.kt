package com.boxpace.domain

/**
 * Porta de saída para persistir e consultar [Encomenda].
 *
 * Implementações vivem em `data/` (local Room, cloud Drive). O domínio só conhece
 * esta interface — nada de Room, HTTP ou Drive aqui.
 */
interface EncomendaRepository {
    suspend fun salvar(encomenda: Encomenda)
    suspend fun buscarPorId(id: String): Encomenda?
    suspend fun buscarPorCodigo(codigo: String, transportadora: Transportadora): Encomenda?
    suspend fun listar(): List<Encomenda>
    suspend fun listarAtivas(): List<Encomenda>
    suspend fun listarFechadas(): List<Encomenda>
    suspend fun excluir(id: String)
}
