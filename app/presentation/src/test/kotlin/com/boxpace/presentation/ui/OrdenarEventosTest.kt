package com.boxpace.presentation.ui

import com.boxpace.domain.Evento
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Testes de [ordenarEventos] — fecham a matriz de edge cases do spec:
 * ordem decrescente (mais recente primeiro), empate estável preservando a
 * sequência de origem, datas inválidas no fim e lista vazia.
 */
class OrdenarEventosTest {

    private fun evento(data: String, descricao: String = "Evento") =
        Evento(data = data, descricao = descricao)

    @Test
    fun `ordena do mais recente para o mais antigo`() {
        val armazenados = listOf(
            evento("2026-09-01T10:00:00Z", "antigo"),
            evento("2026-09-02T10:00:00Z", "meio"),
            evento("2026-09-03T10:00:00Z", "recente"),
        )

        val resultado = ordenarEventos(armazenados)

        assertEquals(listOf("recente", "meio", "antigo"), resultado.map { it.descricao })
    }

    @Test
    fun `empate de data preserva a sequencia de origem`() {
        val empatados = listOf(
            evento("2026-09-02T10:00:00Z", "primeiro"),
            evento("2026-09-03T10:00:00Z", "mais recente"),
            evento("2026-09-02T10:00:00Z", "segundo"),
        )

        val resultado = ordenarEventos(empatados)

        assertEquals(listOf("mais recente", "primeiro", "segundo"), resultado.map { it.descricao })
    }

    @Test
    fun `data invalida cai no fim depois dos parseaveis`() {
        val comInvalida = listOf(
            evento("data-invalida", "invalida"),
            evento("2026-09-01T10:00:00Z", "antiga"),
            evento("2026-09-02T10:00:00Z", "recente"),
        )

        val resultado = ordenarEventos(comInvalida)

        assertEquals(listOf("recente", "antiga", "invalida"), resultado.map { it.descricao })
    }

    @Test
    fun `invalidas entre si preservam a ordem de origem`() {
        val soInvalidas = listOf(
            evento("nao-eh-data", "primeira"),
            evento("texto-qualquer", "segunda"),
        )

        val resultado = ordenarEventos(soInvalidas)

        assertEquals(listOf("primeira", "segunda"), resultado.map { it.descricao })
    }

    @Test
    fun `aceita ISO naive sem offset como no contrato do scraper`() {
        val naive = listOf(
            evento("2026-09-01T10:00:00", "antigo"),
            evento("2026-09-02T10:00:00", "recente"),
        )

        val resultado = ordenarEventos(naive)

        assertEquals(listOf("recente", "antigo"), resultado.map { it.descricao })
    }

    @Test
    fun `lista vazia retorna lista vazia`() {
        assertEquals(emptyList(), ordenarEventos(emptyList()))
    }

    @Test
    fun `nao muta a lista de origem`() {
        val armazenados = listOf(
            evento("2026-09-01T10:00:00Z"),
            evento("2026-09-02T10:00:00Z"),
        )

        ordenarEventos(armazenados)

        assertEquals(listOf("2026-09-01T10:00:00Z", "2026-09-02T10:00:00Z"), armazenados.map { it.data })
    }
}