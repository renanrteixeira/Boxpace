package com.boxpace.domain

/**
 * Encomenda a ser rastreada. Identidade = [codigo] + [transportadora].
 *
 * @param etiqueta etiqueta do produto; obrigatória e não-nula.
 * @param cpfDestinatario dado pessoal sensível (LGPD) usado apenas por provedores
 *   que exigem (J&T). Nunca deve ser logado, compartilhado ou exposto fora do domínio.
 */
data class Encomenda(
    val id: String,
    val codigo: String,
    val transportadora: Transportadora,
    val etiqueta: String,
    val ultimoStatus: String? = null,
    val statusEntregue: Boolean = false,
    val eventos: List<Evento> = emptyList(),
    val criadaEm: String,
    val atualizadaEm: String,
    val fechadaEm: String? = null,
    val cpfDestinatario: String? = null,
) {
    init {
        require(codigo.isNotBlank()) { "codigo obrigatorio" }
    }
}
