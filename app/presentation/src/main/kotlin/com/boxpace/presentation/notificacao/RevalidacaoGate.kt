package com.boxpace.presentation.notificacao

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **Mutex por `codigo`** compartilhado entre o foreground (`revalidar` do
 * ViewModel) e o worker de background (`RevalidarWorker`) — AD-NOTIFY-REFRESH.
 *
 * Garante que, no mesmo `codigo`, o segundo atualizador **pula** enquanto já há
 * um fetch em voo: nenhum fetch/notificação duplicado por concorrência
 * (CONCORRENCIA no I/O & Edge-Case Matrix).
 *
 * O mesmo gate (instância compartilhada em [Gates]) é consumido pelo ViewModel e
 * pelo worker — é isso que torna o mutex efetivamente comum às duas fontes.
 *
 * Um `Mutex` é criado sob demanda por chave; `NoFurthers`/`withLock` enfileira a
 * coroutine concorrente até a primeira terminar. O bloqueio é por encomenda e
 * durável apenas enquanto durar a revalidação (não persiste entre execuções).
 */
class RevalidacaoGate {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Executa [block] sob um lock exclusivo para [chave]. Chamadas concorrentes
     * na mesma [chave] são serializadas (a segunda espera a primeira terminar).
     *
     * Usa [ConcurrentHashMap.computeIfAbsent] (atômico no Java) em vez de
     * [MutableMap.getOrPut] (Kotlin, não atômico) para garantir que duas
     * coroutines concorrentes compartilham o mesmo [Mutex] por chave.
     */
    suspend fun <T> comLock(chave: String, block: suspend () -> T): T {
        val mutex = mutexes.computeIfAbsent(chave) { Mutex() }
        return mutex.withLock { block() }
    }

    /** Limpa os locks em memória (uso em teste). */
    fun limpar() = mutexes.clear()
}

/** Instância única compartilhada pelo ViewModel `revalidar` e pelo worker. */
object Gates {
    val revalidacao: RevalidacaoGate = RevalidacaoGate()
}
