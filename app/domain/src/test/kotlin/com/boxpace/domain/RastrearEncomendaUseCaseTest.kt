package com.boxpace.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RastrearEncomendaUseCaseTest {

    class StubRemote(private val stub: (String, Transportadora, String?) -> RastreioResult) :
        EncomendaRemoteDataSource {
        var ultimoCpf: String? = null
        override suspend fun rastrear(
            codigo: String,
            transportadora: Transportadora,
            cpfDestinatario: String?,
        ): RastreioResult {
            ultimoCpf = cpfDestinatario
            return stub(codigo, transportadora, cpfDestinatario)
        }
    }

    @Test
    fun `happy path devolve stub com eventos vazios`() = runTest {
        val remote = StubRemote { _, _, _ ->
            RastreioResult.Sucesso(codigo = "AA123456789BR", eventos = emptyList())
        }
        val result =
            RastrearEncomendaUseCase(remote).executar("AA123456789BR", Transportadora.CORREIOS)

        assertTrue(result is RastreioResult.Sucesso)
        assertEquals("AA123456789BR", result.codigo)
        assertTrue(result.eventos.isEmpty())
    }

    @Test
    fun `rota jt delega ao provedor e devolve stub no mesmo shape`() = runTest {
        val remote = StubRemote { _, t, _ ->
            assertEquals(Transportadora.JT, t)
            RastreioResult.Sucesso(codigo = "888123456", eventos = emptyList())
        }
        val result =
            RastrearEncomendaUseCase(remote).executar("888123456", Transportadora.JT)

        assertTrue(result is RastreioResult.Sucesso)
        assertEquals("888123456", result.codigo)
        assertTrue(result.eventos.isEmpty())
    }

    @Test
    fun `cpfDestinatario e repassado para o provedor remoto`() = runTest {
        val remote = StubRemote { _, t, cpf ->
            assertEquals(Transportadora.JT, t)
            assertEquals("12345678909", cpf)
            RastreioResult.Sucesso(codigo = "888123456", eventos = emptyList())
        }
        RastrearEncomendaUseCase(remote)
            .executar("888123456", Transportadora.JT, cpfDestinatario = "12345678909")

        assertEquals("12345678909", remote.ultimoCpf)
    }

    @Test
    fun `provedor nao implementado devolve erro claro`() = runTest {
        val remote = StubRemote { _, _, _ ->
            RastreioResult.NaoImplementado(Transportadora.JT)
        }
        val result =
            RastrearEncomendaUseCase(remote).executar("888123456", Transportadora.JT)

        assertEquals(RastreioResult.NaoImplementado(Transportadora.JT), result)
    }

    @Test
    fun `codigo em branco rejeitado`() = runTest {
        val remote = StubRemote { _, _, _ -> RastreioResult.Sucesso("", emptyList()) }
        val useCase = RastrearEncomendaUseCase(remote)
        var erro = false
        try {
            useCase.executar("  ", Transportadora.CORREIOS)
        } catch (e: IllegalArgumentException) {
            erro = true
        }
        assertTrue(erro)
    }
}