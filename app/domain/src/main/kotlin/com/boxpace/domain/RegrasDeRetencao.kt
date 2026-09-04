package com.boxpace.domain

/**
 * Regras de domínio sobre retenção/localização de encomendas.
 *
 * Centralizadas no domínio para não haver dependência invertida da camada de
 * apresentação (ex.: `notificacao` e `vm`) quando precisam da mesma regra.
 */
object RegrasDeRetencao {
    /**
     * Fechados com `fechadaEm` mais antigo que isso são purgados do repositório
     * local (Room). Regra de negócio de domínio (AD-PENDING-DELTAS).
     */
    const val PURGA_DIAS = 90
}
