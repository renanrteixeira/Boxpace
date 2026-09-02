package com.boxpace.domain

/**
 * Um evento de rastreio individual de uma encomenda (uma entrada na timeline).
 */
data class Evento(
    /** Data/hora do evento em formato ISO-8601. */
    val data: String,
    val descricao: String,
    val cidade: String? = null,
    val uf: String? = null,
    val unidade: String? = null,
)
