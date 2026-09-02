package com.boxpace.data.remote

import com.boxpace.data.di.DataModule
import com.boxpace.domain.Evento
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RastreioMapperTest {

    @Test
    fun `mapa evento do contrato para o dominio`() {
        val dto = ContratoEventoDto(
            data = "2026-09-01T10:30:00",
            descricao = "Objeto entregue ao destinatário",
            cidade = "Cuiabá",
            uf = "MT",
            unidade = "CTE CUIABA",
        )

        val evento = RastreioMapper.paraEvento(dto)

        assertEquals(
            Evento(data = "2026-09-01T10:30:00", descricao = "Objeto entregue ao destinatário", cidade = "Cuiabá", uf = "MT", unidade = "CTE CUIABA"),
            evento,
        )
    }

    @Test
    fun `mapa evento com campos opcionais ausentes`() {
        val dto = ContratoEventoDto(data = "2026-09-01T10:30:00", descricao = "Objeto postado")

        val evento = RastreioMapper.paraEvento(dto)

        assertEquals(null, evento.cidade)
        assertEquals(null, evento.uf)
        assertEquals(null, evento.unidade)
    }

    @Test
    fun `desserializa resposta completa do contrato`() {
        val resposta = DataModule.json.decodeFromString<ContratoRastrearResponse>(
            """
            {
              "codigo": "AA123456789BR",
              "eventos": [
                {
                  "data": "2026-09-01T10:30:00",
                  "descricao": "Objeto entregue ao destinatário",
                  "cidade": "Cuiabá",
                  "uf": "MT",
                  "unidade": "CTE CUIABA"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("AA123456789BR", resposta.codigo)
        assertEquals(1, resposta.eventos.size)
        assertEquals("Cuiabá", resposta.eventos.single().cidade)
    }

    @Test
    fun `desserializa resposta sem eventos`() {
        val resposta = DataModule.json.decodeFromString<ContratoRastrearResponse>("""{"codigo":"AA123456789BR"}""")

        assertTrue(resposta.eventos.isEmpty())
    }
}