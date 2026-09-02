package com.boxpace.domain

/**
 * Preferências do usuário (atualmente apenas tema claro/escuro).
 */
data class Preferencias(
    val tema: Tema = Tema.CLARO,
)

enum class Tema(val id: String) {
    CLARO("claro"),
    ESCURO("escuro");

    companion object {
        fun fromId(id: String?): Tema = when (id?.trim()?.lowercase()) {
            "escuro" -> ESCURO
            else -> CLARO
        }
    }
}
