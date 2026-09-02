package com.boxpace.domain

/**
 * Transportadora que define o provedor de rastreio e a validação do código.
 *
 * - [CORREIOS]: códigos no formato `AA...BR`.
 * - [JT]: códigos com prefixo `888` (J&T Express); podem exigir `cpfDestinatario`.
 */
enum class Transportadora(val scraperId: String) {
    CORREIOS("correios"),
    JT("jt");

    companion object {
        /** O identificador textual esperado pelo contrato HTTP do scraper. */
        fun fromScraperId(id: String?): Transportadora? = when (id?.trim()?.lowercase()) {
            "correios" -> CORREIOS
            "jt" -> JT
            else -> null
        }
    }
}
