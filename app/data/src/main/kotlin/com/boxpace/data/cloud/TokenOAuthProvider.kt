package com.boxpace.data.cloud

/**
 * Detentor do token OAuth em **memória apenas** — AD-SYNC-3/8. O token nunca é
 * persistido em disco nem logado. O launcher do picker (em `presentation`)
 * entrega o token via [fornecer]; o coordinator consome via [atual]. Revogar
 * limpa o estado e a memória.
 */
class TokenOAuthProvider {
    @Volatile
    private var token: String? = null

    /** Entrega o token curto (bearer) obtido do AuthorizationClient. */
    fun fornecer(token: String) {
        this.token = token
    }

    /** Token atual, ou `null` se ainda não autorizado. */
    fun atual(): String? = token

    /** Limpa o token da memória (desvincular / 401 irrecuperável). */
    fun limpar() {
        token = null
    }
}
