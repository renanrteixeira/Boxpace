package com.boxpace.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.boxpace.domain.Preferencias
import com.boxpace.domain.PreferenciasRepository
import com.boxpace.domain.Tema
import kotlinx.coroutines.flow.first

/**
 * Implementação de [PreferenciasRepository] usando DataStore Preferences.
 * Chaves `tema` + `updated_at` — serializa/deserializa via [Tema.id].
 */
class PreferenciasRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : PreferenciasRepository {

    override suspend fun carregar(): Preferencias {
        val prefs = dataStore.data.first()
        return Preferencias(
            tema = Tema.fromId(prefs[CHAVE_TEMA]),
            updatedAt = prefs[CHAVE_UPDATED_AT] ?: "",
        )
    }

    override suspend fun salvar(preferencias: Preferencias) {
        dataStore.edit { prefs ->
            prefs[CHAVE_TEMA] = preferencias.tema.id
            prefs[CHAVE_UPDATED_AT] = preferencias.updatedAt
        }
    }

    companion object {
        private val CHAVE_TEMA = stringPreferencesKey("tema")
        private val CHAVE_UPDATED_AT = stringPreferencesKey("updated_at")
    }
}
