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
    /**
     * Sinalização estruturada de estado terminal vinda do scraper (AD-6):
     * código de varredura/status do provedor (Correios "ENTREGUE"/"DEVOLVIDO";
     * J&T code 100/signatário). Substitui a heurística de texto como fonte
     * primária de decisão em [com.boxpace.domain.Encomenda.estaEntregue].
     */
    val entregue: Boolean = false,
)
