package com.boxpace.data.remote

import com.boxpace.data.di.DataModule
import com.boxpace.domain.ErroDeRastreio
import com.boxpace.domain.RastreioResult
import com.boxpace.domain.Transportadora
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EncomendaRemoteDataSourceImplTest {

    private fun cliente(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(DataModule.json) }
    }

    private fun MockRequestHandleScope.jsonResponse(corpo: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(
            content = corpo,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    @Test
    fun `sucesso mapeia eventos do contrato para o dominio`() = runTest {
        val engine = MockEngine { request ->
            jsonResponse(
                """{"codigo":"AA123456789BR","eventos":[{"data":"2026-09-01T10:30:00","descricao":"Objeto entregue ao destinatário","cidade":"Cuiabá","uf":"MT","unidade":"CTE CUIABA"}]}""",
            )
        }
        val impl = EncomendaRemoteDataSourceImpl(cliente(engine), baseUrl = "http://scraper")

        val resultado = impl.rastrear("AA123456789BR", Transportadora.CORREIOS)

        assertTrue(resultado is RastreioResult.Sucesso)
        assertEquals("AA123456789BR", (resultado as RastreioResult.Sucesso).codigo)
        assertEquals("Objeto entregue ao destinatário", resultado.eventos.single().descricao)
        assertEquals("Cuiabá", resultado.eventos.single().cidade)
        assertEquals("MT", resultado.eventos.single().uf)
        assertEquals("CTE CUIABA", resultado.eventos.single().unidade)
    }

    @Test
    fun `envia contrato com transportadora codigo e cpf para jt`() = runTest {
        var corpoEnviado: String? = null
        val engine = MockEngine { request ->
            corpoEnviado = request.body.toByteArray().decodeToString()
            jsonResponse("""{"codigo":"888123456","eventos":[]}""")
        }
        val impl = EncomendaRemoteDataSourceImpl(cliente(engine), baseUrl = "http://scraper")

        val resultado = impl.rastrear("888123456", Transportadora.JT, cpfDestinatario = "123.456.789-09")

        assertTrue(resultado is RastreioResult.Sucesso)
        assertTrue(corpoEnviado!!.contains("\"transportadora\":\"jt\""))
        assertTrue(corpoEnviado!!.contains("\"codigo\":\"888123456\""))
        assertTrue(corpoEnviado!!.contains("\"cpf\":\"12345678909\""))
    }

    @Test
    fun `nao envia cpf para correios`() = runTest {
        var corpoEnviado: String? = null
        val engine = MockEngine { request ->
            corpoEnviado = request.body.toByteArray().decodeToString()
            jsonResponse("""{"codigo":"AA123456789BR","eventos":[]}""")
        }
        val impl = EncomendaRemoteDataSourceImpl(cliente(engine), baseUrl = "http://scraper")

        impl.rastrear("AA123456789BR", Transportadora.CORREIOS)

        assertTrue(!corpoEnviado!!.contains("cpf"))
    }

    @Test
    fun `501 e mapeado como nao implementado`() = runTest {
        val engine = MockEngine { request ->
            jsonResponse("""{"detail":"Provedor J&T ainda não implementado"}""", status = HttpStatusCode.fromValue(501))
        }
        val impl = EncomendaRemoteDataSourceImpl(cliente(engine), baseUrl = "http://scraper")

        val resultado = impl.rastrear("888123", Transportadora.JT, cpfDestinatario = "12345678909")

        assertEquals(RastreioResult.NaoImplementado(Transportadora.JT), resultado)
    }

    @Test
    fun `404 e mapeado como codigo nao encontrado`() = runTest {
        val engine = MockEngine { request ->
            jsonResponse("""{"detail":"Objeto não encontrado"}""", status = HttpStatusCode.NotFound)
        }
        val impl = EncomendaRemoteDataSourceImpl(cliente(engine), baseUrl = "http://scraper")

        assertFailsWith<ErroDeRastreio.CodigoNaoEncontrado> {
            impl.rastrear("AA123456789BR", Transportadora.CORREIOS)
        }
    }

    @Test
    fun `5xx e mapeado como sem conexao`() = runTest {
        val engine = MockEngine { request ->
            jsonResponse("""{"detail":"erro interno"}""", status = HttpStatusCode.InternalServerError)
        }
        val impl = EncomendaRemoteDataSourceImpl(cliente(engine), baseUrl = "http://scraper")

        val erro = assertFailsWith<ErroDeRastreio.SemConexao> {
            impl.rastrear("AA123456789BR", Transportadora.CORREIOS)
        }

        assertEquals("Sem conexão agora", erro.mensagem)
    }

    @Test
    fun `falha de rede e mapeada como sem conexao`() = runTest {
        val engine = MockEngine { request -> throw IOException("tempo de conexão esgotado") }
        val impl = EncomendaRemoteDataSourceImpl(cliente(engine), baseUrl = "http://scraper")

        assertFailsWith<ErroDeRastreio.SemConexao> {
            impl.rastrear("AA123456789BR", Transportadora.CORREIOS)
        }
    }

    @Test
    fun `resposta com json invalido e mapeada como sem conexao`() = runTest {
        val engine = MockEngine { request ->
            jsonResponse("\"não é um objeto\"")
        }
        val impl = EncomendaRemoteDataSourceImpl(cliente(engine), baseUrl = "http://scraper")

        assertFailsWith<ErroDeRastreio.SemConexao> {
            impl.rastrear("AA123456789BR", Transportadora.CORREIOS)
        }
    }

    @Test
    fun `cold start primeira tentativa falha e retentativa com timeout maior recupera`() = runTest {
        var chamadas = 0
        val engine = MockEngine { request ->
            chamadas++
            if (chamadas == 1) throw IOException("servidor dormindo") else
                jsonResponse("""{"codigo":"AA123456789BR","eventos":[{"data":"2026-09-01T10:30:00","descricao":"Objeto postado"}]}""")
        }
        val impl = EncomendaRemoteDataSourceImpl(cliente(engine), baseUrl = "http://scraper")

        val resultado = impl.rastrear("AA123456789BR", Transportadora.CORREIOS)

        assertEquals(2, chamadas)
        assertTrue(resultado is RastreioResult.Sucesso)
        assertEquals("Objeto postado", (resultado as RastreioResult.Sucesso).eventos.single().descricao)
    }

    @Test
    fun `cold start falha nas duas tentativas e vira sem conexao`() = runTest {
        var chamadas = 0
        val engine = MockEngine { request ->
            chamadas++
            throw IOException("servidor dormindo")
        }
        val impl = EncomendaRemoteDataSourceImpl(cliente(engine), baseUrl = "http://scraper")

        assertFailsWith<ErroDeRastreio.SemConexao> {
            impl.rastrear("AA123456789BR", Transportadora.CORREIOS)
        }
        assertEquals(2, chamadas)
    }
}