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
    /** Buscas consecutivas sem eventos (badge "Sem dados" — AC 1.3). */
    val buscasSemEventos: Int = 0,
) {
    init {
        require(codigo.isNotBlank()) { "codigo obrigatorio" }
    }

    /**
     * Fechamento manual/persistido (arquivamento). Distinto de [estaEntregue]:
     * "fechado" é escolha persistida; "entregue" é derivado do rastreio (AD-FECHADO).
     *
     * `fechadaEm` é persistido 1ª classe (não derivado).
     */
    fun estaFechada(): Boolean = fechadaEm != null

    /**
     * Derivado do rastreio: `true` quando algum evento carrega a sinalização
     * estruturada `Evento.entregue` (AD-6). Fonte primária é o código de
     * varredura/status do provedor, não o texto.
     * Não depende de [estaFechada] nem de [statusEntregue].
     */
    fun estaEntregue(): Boolean = eventosIndicamEntrega(eventos)

    companion object {
        /**
         * União booleana das sinalizações estruturadas de entrega (AD-6).
         * Distinta de "fechado" (AD-FECHADO): fechar arquiva; entregue encerra
         * o ciclo de rastreio. Usada por [estaEntregue] e pelo fluxo de adição.
         */
        fun eventosIndicamEntrega(eventos: List<Evento>): Boolean =
            eventos.any { it.entregue }
    }
}
