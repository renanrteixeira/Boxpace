package com.boxpace.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportadoraTest {

    @Test
    fun `fromScraperId resolve correios`() {
        assertEquals(Transportadora.CORREIOS, Transportadora.fromScraperId("correios"))
        assertEquals(Transportadora.CORREIOS, Transportadora.fromScraperId("CORREIOS "))
    }

    @Test
    fun `fromScraperId resolve jt`() {
        assertEquals(Transportadora.JT, Transportadora.fromScraperId("jt"))
        assertEquals(Transportadora.JT, Transportadora.fromScraperId(" JT "))
    }

    @Test
    fun `fromScraperId rejeita valor invalido ou ausente`() {
        assertNull(Transportadora.fromScraperId("sedex"))
        assertNull(Transportadora.fromScraperId(""))
        assertNull(Transportadora.fromScraperId(null))
    }

    @Test
    fun `scraperId round trip`() {
        Transportadora.entries.forEach { t ->
            assertEquals(t, Transportadora.fromScraperId(t.scraperId))
        }
    }
}
