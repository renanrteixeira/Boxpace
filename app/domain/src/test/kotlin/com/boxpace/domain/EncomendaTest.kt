package com.boxpace.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncomendaTest {

    private fun encomenda(
        eventos: List<Evento> = emptyList(),
        fechadaEm: String? = null,
    ): Encomenda = Encomenda(
        id = "correios:AA123456789BR",
        codigo = "AA123456789BR",
        transportadora = Transportadora.CORREIOS,
        etiqueta = "Fone de ouvido",
        eventos = eventos,
        criadaEm = "2026-09-01T12:00:00Z",
        atualizadaEm = "2026-09-01T12:00:00Z",
        fechadaEm = fechadaEm,
    )

    @Test
    fun `estaFechada reflete fechadaEm null`() {
        assertFalse(encomenda(fechadaEm = null).estaFechada())
        assertTrue(encomenda(fechadaEm = "2026-09-01T12:00:00Z").estaFechada())
    }

    @Test
    fun `estaEntregue quando algum evento indica entrega case-insensitive`() {
        val comEntrega = encomenda(
            eventos = listOf(
                Evento("2026-09-01T10:00:00", "Objeto postado"),
                Evento("2026-09-01T11:00:00", "OBJETO ENTREGUE AO DESTINATÁRIO"),
            ),
        )
        assertTrue(comEntrega.estaEntregue())
    }

    @Test
    fun `estaEntregue falso sem evento de entrega`() {
        val semEntrega = encomenda(
            eventos = listOf(Evento("2026-09-01T10:00:00", "Objeto postado")),
        )
        assertFalse(semEntrega.estaEntregue())
        assertFalse(encomenda(eventos = emptyList()).estaEntregue())
    }

    @Test
    fun `entregue e fechado sao campos distintos`() {
        val entregueNaoFechada = encomenda(
            eventos = listOf(Evento("2026-09-01T11:00:00", "Objeto entregue ao destinatário")),
            fechadaEm = null,
        )
        assertTrue(entregueNaoFechada.estaEntregue())
        assertFalse(entregueNaoFechada.estaFechada())
    }

    @Test
    fun `entregue e fechado podem coexistir`() {
        val entregueEFechada = encomenda(
            eventos = listOf(Evento("2026-09-01T11:00:00", "Objeto entregue ao destinatário")),
            fechadaEm = "2026-09-01T12:00:00Z",
        )
        assertTrue(entregueEFechada.estaEntregue())
        assertTrue(entregueEFechada.estaFechada())
    }

    @Test
    fun `estaEntregue nao depende de statusEntregue nem da flag`() {
        val comFlagMasSemEvento = encomenda(
            eventos = listOf(Evento("2026-09-01T10:00:00", "Objeto postado")),
        ).copy(statusEntregue = true)
        assertFalse(comFlagMasSemEvento.estaEntregue())

        val semFlagMasComEvento = encomenda(
            eventos = listOf(Evento("2026-09-01T11:00:00", "Objeto entregue ao destinatário")),
        ).copy(statusEntregue = false)
        assertTrue(semFlagMasComEvento.estaEntregue())
    }
}
