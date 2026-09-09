package com.boxpace.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Estado reativo da sincronização com o Google Drive (Epic 5).
 *
 * O domínio não conhece Android/HTTP/Drive: este estado espelha o que o
 * usuário enxerga na seção de Configurações (AD-SYNC-7). Transições possíveis:
 *
 * - [Desvinculado] — "Encomendas só neste aparelho" (inicial / após desvincular).
 * - [Restaurando] — restauração pull-only (canônico → Room) em voo.
 * - [Sincronizando] — ciclo read→merge→write→reconcile em voo.
 * - [Vinculado] — conta autorizada e sync operacional.
 * - [SincronizacaoPerdida] — token expirado/revogado; "Sincronização perdida — toque para reconectar".
 * - [SincronizacaoEmPausa] — vínculo ativo, mas o sync está pausado: o arquivo
 *   canônico tem schema mais novo que este app (SCHEMA_MAIOR); nunca sobrescreve.
 */
sealed interface SyncState {
    data object Desvinculado : SyncState
    data object Restaurando : SyncState
    data object Sincronizando : SyncState
    data object Vinculado : SyncState
    data object SincronizacaoPerdida : SyncState

    /** Aviso discreto persistido (ex.: schema do Drive mais novo que o app). */
    data class SincronizacaoEmPausa(val motivo: String) : SyncState
}

/**
 * Porta de saída da sincronização com o Google Drive.
 *
 * Implementação vive em `data/cloud` ([CoordenadorDeSync]). A UI chama estas
 * operações; o picker OAuth (que exige Activity) permanece em `presentation` e
 * entrega o token ao `data/cloud` — nunca o contrário.
 */
interface SincronizacaoRepository {
    /** Estado reativo observável pela seção de Configurações. */
    val syncState: StateFlow<SyncState>

    /**
     * Vincula a conta Google. Primeiro vínculo com cache local = promoção do
     * cache ao canônico (∅); vínculo com canônico presente = sync (AD-SYNC-5A/6A).
     * Retorna `false` se o OAuth for negado — sem efeito (permanece desvinculado).
     */
    suspend fun vincular(): Boolean

    /**
     * Restaura do Drive (pull-only, AD-SYNC-5A/6A): lê o canônico → migra →
     * merge LWW com deltas pendentes → espelha no Room. **Nunca** escreve no
     * Drive e **não** limpa deltas (só o sync consome deltas — AD-SYNC-9).
     * Durante o carregamento o estado fica [SyncState.Restaurando].
     * Retorna `false` se sem token / rede indisponível / 401 — sem spinner preso.
     */
    suspend fun restaurar(): Boolean

    /** Desvincula: descarrega deltas pendentes, para o sync, volta a [SyncState.Desvinculado]. */
    suspend fun desvincular(): Boolean

    /**
     * Reconecta (reautorização silenciosa) após 401. Retoma do re-merge LWW;
     * dados e deltas permanecem intactos.
     */
    suspend fun reconectar(): Boolean
}
