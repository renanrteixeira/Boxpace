package com.boxpace.domain

/**
 * Preferências do usuário (tema claro/escuro/automático).
 *
 * [updatedAt] é um timestamp ISO-8601 usado para LWW (last-write-wins) por
 * registro nos deltas de sincronização (Epic 5 / Drive).
 */
data class Preferencias(
    val tema: Tema = Tema.SISTEMA,
    val updatedAt: String = "",
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
