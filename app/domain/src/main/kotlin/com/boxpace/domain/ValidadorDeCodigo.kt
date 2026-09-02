package com.boxpace.domain

/**
 * Valida o formato do código de rastreio de acordo com a [Transportadora].
 *
 * - [Transportadora.CORREIOS]: formato `AA...BR` (2 letras + 9 dígitos + "BR").
 * - [Transportadora.JT]: prefixo `888` seguido de dígitos.
 */
object ValidadorDeCodigo {

    private val CORREIOS_REGEX = Regex("^[A-Za-z]{2}[0-9]{9}BR$", RegexOption.IGNORE_CASE)
    private val JT_REGEX = Regex("^888[0-9]+$")

    fun validar(codigo: String?, transportadora: Transportadora): Boolean {
        val c = codigo?.trim().orEmpty()
        if (c.isEmpty()) return false
        return when (transportadora) {
            Transportadora.CORREIOS -> CORREIOS_REGEX.matches(c)
            Transportadora.JT -> JT_REGEX.matches(c)
        }
    }
}
