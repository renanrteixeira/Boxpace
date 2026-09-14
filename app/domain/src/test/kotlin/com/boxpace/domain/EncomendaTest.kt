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

    private fun eventoEntregue(descricao: String = "Objeto entregue ao destinatário"): Evento =
        Evento(data = "2026-09-01T11:00:00", descricao = descricao, entregue = true)

    @Test
    fun `estaFechada reflete fechadaEm null`() {
        assertFalse(encomenda(fechadaEm = null).estaFechada())
        assertTrue(encomenda(fechadaEm = "2026-09-01T12:00:00Z").estaFechada())
    }

    @Test
    fun `estaEntregue quando algum evento tem flag estruturada`() {
        val comEntrega = encomenda(
            eventos = listOf(
                Evento("2026-09-01T10:00:00", "Objeto postado"),
                eventoEntregue(),
            ),
        )
        assertTrue(comEntrega.estaEntregue())
    }

    @Test
    fun `estaEntregue via sinalizacao estruturada do J&T (assinatura)`() {
        val assinatura = encomenda(
            eventos = listOf(Evento("2026-09-09T17:34:56", "Assinar pelo próprio", entregue = true)),
        )
        assertTrue(assinatura.estaEntregue())
    }

    @Test
    fun `estado terminal devolvido marca entregue sem depender de palavra-chave`() {
        // Furo histórico: o texto "devolvido ao remetente" não contém
        // "entregue/assinado"; a sinalização estruturada do scraper cobre (AD-6).
        val devolvido = encomenda(
            eventos = listOf(Evento("2026-09-01T11:00:00", "Objeto devolvido ao remetente", entregue = true)),
        )
        assertTrue(devolvido.estaEntregue())
    }

    @Test
    fun `estaEntregue falso sem evento com flag`() {
        val semEntrega = encomenda(
            // texto "entregue" sem a flag estruturada NÃO conta mais (AD-6)
            eventos = listOf(
                Evento("2026-09-01T10:00:00", "Objeto postado"),
                Evento("2026-09-01T11:00:00", "OBJETO ENTREGUE AO DESTINATÁRIO"),
            ),
        )
        assertFalse(semEntrega.estaEntregue())
        assertFalse(encomenda(eventos = emptyList()).estaEntregue())
    }

    @Test
    fun `entregue e fechado sao campos distintos`() {
        val entregueNaoFechada = encomenda(
            eventos = listOf(eventoEntregue()),
            fechadaEm = null,
        )
        assertTrue(entregueNaoFechada.estaEntregue())
        assertFalse(entregueNaoFechada.estaFechada())
    }

    @Test
    fun `entregue e fechado podem coexistir`() {
        val entregueEFechada = encomenda(
            eventos = listOf(eventoEntregue()),
            fechadaEm = "2026-09-01T12:00:00Z",
        )
        assertTrue(entregueEFechada.estaEntregue())
        assertTrue(entregueEFechada.estaFechada())
    }

    @Test
    fun `estaEntregue nao depende de statusEntregue`() {
        val comFlagMasSemStatus = encomenda(
            eventos = listOf(Evento("2026-09-01T10:00:00", "Objeto postado")),
        ).copy(statusEntregue = true)
        assertFalse(comFlagMasSemStatus.estaEntregue())

        val semFlagMasComStatus = encomenda(
            eventos = listOf(eventoEntregue()),
        ).copy(statusEntregue = false)
        assertTrue(semFlagMasComStatus.estaEntregue())
    }
}