package com.boxpace.presentation.notificacao

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Testes do [RevalidacaoGate] — o mutex por `codigo` compartilhado entre o
 * foreground (`revalidar` do ViewModel) e o worker de background
 * (CONCORRENCIA no I/O & Edge-Case Matrix).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RevalidacaoGateTest {

    // --- CONCORRENCIA: mesmo código serializa — o 2º espera o 1º ---

    @Test
    fun `mesmo codigo serializa a segunda chamada`() = runTest {
        val gate = RevalidacaoGate()
        val primeiraEntrou = CompletableDeferred<Unit>()
        val liberarPrimeira = CompletableDeferred<Unit>()
        var segundaEntrou = false

        val primeira = async {
            gate.comLock("AA123456789BR") {
                primeiraEntrou.complete(Unit)
                liberarPrimeira.await()
                "primeira"
            }
        }
        primeiraEntrou.await()

        val segunda = async {
            gate.comLock("AA123456789BR") {
                segundaEntrou = true
                "segunda"
            }
        }

        // Antes de a 1ª liberar, a 2ª não pode entrar no bloco crítico.
        advanceUntilIdle()
        assertFalse(segundaEntrou, "2ª não deve entrar enquanto a 1ª segura o lock")

        liberarPrimeira.complete(Unit)

        assertEquals(listOf("primeira", "segunda"), listOf(primeira, segunda).awaitAll())
        assertTrue(segundaEntrou)
    }

    // --- Chaves diferentes rodam em paralelo (sem serialização indevida) ---

    @Test
    fun `chaves distintas mantem 2 chamadas simultaneas`() = runTest {
        val gate = RevalidacaoGate()
        var ativos = 0
        var maxAtivos = 0

        fun rodar(chave: String) = launch {
            gate.comLock(chave) {
                ativos++
                maxAtivos = maxOf(maxAtivos, ativos)
                delay(200)
                ativos--
            }
        }

        val a = rodar("chave-A")
        val b = rodar("chave-B")
        advanceUntilIdle()

        assertEquals(2, maxAtivos, "locks distintos não devem se serializar")
        a.join()
        b.join()
    }

    // --- Limpeza permite nova execução na mesma chave ---

    @Test
    fun `limpar permite nova execucao na mesma chave`() = runTest {
        val gate = RevalidacaoGate()
        gate.comLock("alvo") { }
        gate.limpar()
        gate.comLock("alvo") { }
    }
}
