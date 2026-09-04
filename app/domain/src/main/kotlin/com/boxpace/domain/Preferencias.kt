package com.boxpace.domain

/**
 * Preferências do usuário (tema claro/escuro/automático).
 */
data class Preferencias(
    val tema: Tema = Tema.SISTEMA,
)

enum class Tema(val id: String) {
    SISTEMA("sistema"),
    CLARO("claro"),
    ESCURO("escuro");

    companion object {
        fun fromId(id: String?): Tema = when (id?.trim()?.lowercase()) {
            "claro" -> CLARO
            "escuro" -> ESCURO
            else -> SISTEMA
        }
    }
}
