package com.boxpace.domain

/**
 * Delta pendente de uma mutação offline — espelho local para o Epic 5 (Drive).
 * Aqui apenas persistimos; nada é aplicado ao Drive nesta story.
 *
 * [criadoEm] é um timestamp ISO-8601 usado para LWW (last-write-wins) por registro.
 */
sealed interface DeltaPendente {
    /** Id do alvo = "transportadora:código". */
    val alvoId: String

    /** Timestamp ISO-8601 (LWW por registro). */
    val criadoEm: String

    data class Salvar(
        val encomenda: Encomenda,
        override val alvoId: String,
        override val criadoEm: String,
    ) : DeltaPendente

    data class Excluir(
        override val alvoId: String,
        override val criadoEm: String,
    ) : DeltaPendente
}
