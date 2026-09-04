package com.boxpace.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.boxpace.domain.Preferencias
import com.boxpace.domain.PreferenciasRepository
import com.boxpace.domain.Tema
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Implementação de [PreferenciasRepository] usando DataStore Preferences.
 * Chave única `tema` — serializa/deserializa via [Tema.id].
 */
class PreferenciasRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : PreferenciasRepository {

    override suspend fun carregar(): Preferencias {
        val id = dataStore.data.map { prefs -> prefs[CHAVE_TEMA] }.first()
        return Preferencias(tema = Tema.fromId(id))
    }

    override suspend fun salvar(preferencias: Preferencias) {
        dataStore.edit { prefs -> prefs[CHAVE_TEMA] = preferencias.tema.id }
    }

    companion object {
        private val CHAVE_TEMA = stringPreferencesKey("tema")
    }
}
