package com.boxpace.presentation.ui

import com.boxpace.domain.Transportadora
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatExtTest {

    @Test
    fun `formatarHorario valida ISO-8601 e retorna HH-mm`() {
        val resultado = formatarHorario("2026-09-01T14:30:00Z")
        assertEquals(5, resultado.length)
        val partes = resultado.split(":")
        assertEquals(2, partes.size)
    }

    @Test
    fun `formatarHorario com string malformada retorna vazia`() {
        assertEquals("", formatarHorario("nao-e-data"))
    }

    @Test
    fun `formatarHorario com string vazia retorna vazia`() {
        assertEquals("", formatarHorario(""))
    }

    @Test
    fun `nomeExibicao CORREIOS retorna Correios`() {
        assertEquals("Correios", Transportadora.CORREIOS.nomeExibicao())
    }

    @Test
    fun `nomeExibicao JT retorna J E T`() {
        assertEquals("J&T", Transportadora.JT.nomeExibicao())
    }
}
