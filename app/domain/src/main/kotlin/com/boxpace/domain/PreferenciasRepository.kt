package com.boxpace.domain

/**
 * Porta de saída para persistir e consultar [Preferencias].
 */
interface PreferenciasRepository {
    suspend fun carregar(): Preferencias
    suspend fun salvar(preferencias: Preferencias)
}
