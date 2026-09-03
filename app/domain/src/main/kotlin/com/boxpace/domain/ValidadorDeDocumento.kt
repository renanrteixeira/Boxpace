package com.boxpace.domain

/**
 * Validação de documentos fiscais brasileiros de fato — com dígito verificador.
 *
 * CPF (11 dígitos) e CNPJ (14 dígitos) são validados pelos pesos oficiais, e
 * rejeita séries repetidas (ex. `00000000000`), que passariam pelo dígito.
 *
 * Regra de dado sensível (AD-DADO-SENSIVEL): a validação roda sobre os dígitos
 * apenas (sem máscara) e nunca loga o valor original.
 */
object ValidadorDeDocumento {

    private val DIGITOS_REPETIDOS = setOf(
        "00000000000", "11111111111", "22222222222", "33333333333", "44444444444",
        "55555555555", "66666666666", "77777777777", "88888888888", "99999999999",
    )

    /** True se os dígitos formarem um CPF válido (11 dígitos + dígito verificador). */
    fun cpfValido(digitos: String?): Boolean {
        val d = digitos?.filter(Char::isDigit) ?: return false
        if (d.length != 11 || d in DIGITOS_REPETIDOS) return false
        val num = d.map { it - '0' }
        val d1 = digitoVerificador(num.subList(0, 9), 10)
        val d2 = digitoVerificador(num.subList(0, 10), 11)
        return num[9] == d1 && num[10] == d2
    }

    /** True se os dígitos formarem um CNPJ válido (14 dígitos + dígito verificador). */
    fun cnpjValido(digitos: String?): Boolean {
        val d = digitos?.filter(Char::isDigit) ?: return false
        if (d.length != 14 || d in DIGITOS_REPETIDOS) return false
        val num = d.map { it - '0' }
        val pesos1 = listOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        val pesos2 = listOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        val d1 = digitoVerificadorComPesos(num.subList(0, 12), pesos1)
        val d2 = digitoVerificadorComPesos(num.subList(0, 13), pesos2)
        return num[12] == d1 && num[13] == d2
    }

    /** True se os dígitos correspondem a um CPF válido OU a um CNPJ válido. */
    fun documentoFiscalValido(digitos: String?): Boolean =
        cpfValido(digitos) || cnpjValido(digitos)

    /** CPF usa pesos decrescentes `[10..2]`/`[11..2]` sobre 9/10 dígitos. */
    private fun digitoVerificador(digitos: List<Int>, maiorPeso: Int): Int {
        val pesos = digitos.indices.map { maiorPeso - it }
        return digitoVerificadorComPesos(digitos, pesos)
    }

    private fun digitoVerificadorComPesos(digitos: List<Int>, pesos: List<Int>): Int {
        val soma = digitos.zip(pesos).sumOf { (d, p) -> d * p }
        val resto = soma % 11
        return if (resto < 2) 0 else 11 - resto
    }
}
