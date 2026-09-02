package com.boxpace.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidadorDeCodigoTest {

    @Test
    fun `correios aceita formato AA000000000BR`() {
        assertTrue(ValidadorDeCodigo.validar("AA123456789BR", Transportadora.CORREIOS))
    }

    @Test
    fun `correios aceita letras minusculas`() {
        assertTrue(ValidadorDeCodigo.validar("aa123456789br", Transportadora.CORREIOS))
    }

    @Test
    fun `correios rejeita formato invalido`() {
        assertFalse(ValidadorDeCodigo.validar("AA12345BR", Transportadora.CORREIOS))
        assertFalse(ValidadorDeCodigo.validar("AA123456789BRX", Transportadora.CORREIOS))
        assertFalse(ValidadorDeCodigo.validar("", Transportadora.CORREIOS))
        assertFalse(ValidadorDeCodigo.validar(null, Transportadora.CORREIOS))
    }

    @Test
    fun `jt aceita prefixo 888`() {
        assertTrue(ValidadorDeCodigo.validar("888123456789", Transportadora.JT))
    }

    @Test
    fun `jt rejeita prefixo diferente`() {
        assertFalse(ValidadorDeCodigo.validar("999123", Transportadora.JT))
        assertFalse(ValidadorDeCodigo.validar("888", Transportadora.JT)) // sem dígito além do prefixo? prefixo+0 => "888" nao casa 888[0-9]+
    }

    @Test
    fun `validador aplica trim`() {
        assertTrue(ValidadorDeCodigo.validar("  AA123456789BR  ", Transportadora.CORREIOS))
    }

    @Test
    fun `tema fromId defaults claro`() {
        assertEquals(Tema.CLARO, Tema.fromId(null))
        assertEquals(Tema.ESCURO, Tema.fromId("escuro"))
        assertEquals(Tema.CLARO, Tema.fromId("qualquer"))
    }
}
