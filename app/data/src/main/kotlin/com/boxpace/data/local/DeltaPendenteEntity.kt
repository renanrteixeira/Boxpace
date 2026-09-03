package com.boxpace.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Delta pendente persistido (Room) para sincronização futura com o Drive
 * (Epic 5). Aqui apenas registramos/espelhamos — nada aplica ao Drive nesta story.
 *
 * [tipo]: "salvar" | "excluir". [payload] guarda o JSON da encomenda para
 * deltas de Salvar; nulo para Excluir.
 */
@Entity(tableName = "deltasPendentes")
data class DeltaPendenteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alvoId: String,
    val tipo: String,
    val criadoEm: String,
    val payload: String?,
)
