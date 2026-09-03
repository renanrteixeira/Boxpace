package com.boxpace.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidadorDeDocumentoTest {

    @Test
    fun `cpf valido passa`() {
        assertTrue(ValidadorDeDocumento.cpfValido("12345678909"))
    }

    @Test
    fun `cpf com digito verificador errado falha`() {
        assertFalse(ValidadorDeDocumento.cpfValido("12345678900"))
    }

    @Test
    fun `cpf de tamanho errado falha`() {
        assertFalse(ValidadorDeDocumento.cpfValido("123"))
        assertFalse(ValidadorDeDocumento.cpfValido("123456789091"))
        assertFalse(ValidadorDeDocumento.cpfValido(""))
    }

    @Test
    fun `cpf nulo falha`() {
        assertFalse(ValidadorDeDocumento.cpfValido(null))
    }

    @Test
    fun `cpf todos os digitos iguais falha mesmo com digito valido`() {
        assertFalse(ValidadorDeDocumento.cpfValido("11111111111"))
        assertFalse(ValidadorDeDocumento.cpfValido("00000000000"))
    }

    @Test
    fun `cpf ignorante a mascara so considera digitos`() {
        assertTrue(ValidadorDeDocumento.cpfValido("123.456.789-09"))
    }

    @Test
    fun `cnpj valido passa`() {
        assertTrue(ValidadorDeDocumento.cnpjValido("11222333000181"))
    }

    @Test
    fun `cnpj com digito verificador errado falha`() {
        assertFalse(ValidadorDeDocumento.cnpjValido("11222333000180"))
    }

    @Test
    fun `documento fiscal aceita cpf ou cnpj validos`() {
        assertTrue(ValidadorDeDocumento.documentoFiscalValido("12345678909"))
        assertTrue(ValidadorDeDocumento.documentoFiscalValido("11222333000181"))
        assertFalse(ValidadorDeDocumento.documentoFiscalValido("123"))
        assertFalse(ValidadorDeDocumento.documentoFiscalValido(null))
    }
}
