package com.boxpace.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.boxpace.domain.Tema
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferenciasRepositoryImplTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: PreferenciasRepositoryImpl

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dataStore = PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("test_prefs")
        }
        repo = PreferenciasRepositoryImpl(dataStore)
    }

    @After
    fun tearDown() {
        runBlocking {
            dataStore.edit { it.clear() }
        }
    }

    @Test
    fun DEFAULT_RETORNA_SISTEMA_quando_nao_ha_preferencia() = runBlocking {
        val prefs = repo.carregar()
        assertEquals(Tema.SISTEMA, prefs.tema)
        assertEquals("", prefs.updatedAt)
    }

    @Test
    fun ROUND_TRIP_salvar_e_carregar_mantem_tema() = runBlocking {
        repo.salvar(com.boxpace.domain.Preferencias(tema = Tema.ESCURO))
        assertEquals(Tema.ESCURO, repo.carregar().tema)

        repo.salvar(com.boxpace.domain.Preferencias(tema = Tema.CLARO))
        assertEquals(Tema.CLARO, repo.carregar().tema)

        repo.salvar(com.boxpace.domain.Preferencias(tema = Tema.SISTEMA))
        assertEquals(Tema.SISTEMA, repo.carregar().tema)
    }

    @Test
    fun ROUND_TRIP_UPDATED_AT_salvar_e_carregar_mantem_tema_e_updatedAt() = runBlocking {
        repo.salvar(com.boxpace.domain.Preferencias(tema = Tema.ESCURO, updatedAt = "2026-09-01T12:00:00Z"))
        val carregadas = repo.carregar()
        assertEquals(Tema.ESCURO, carregadas.tema)
        assertEquals("2026-09-01T12:00:00Z", carregadas.updatedAt)

        repo.salvar(com.boxpace.domain.Preferencias(tema = Tema.CLARO, updatedAt = "2026-09-02T08:00:00Z"))
        val recarregadas = repo.carregar()
        assertEquals(Tema.CLARO, recarregadas.tema)
        assertEquals("2026-09-02T08:00:00Z", recarregadas.updatedAt)
    }

    @Test
    fun FROM_ID_valores_invalidos_caiem_em_SISTEMA() {
        assertEquals(Tema.SISTEMA, Tema.fromId(null))
        assertEquals(Tema.SISTEMA, Tema.fromId(""))
        assertEquals(Tema.SISTEMA, Tema.fromId("desconhecido"))
    }

    @Test
    fun FROM_ID_valores_validos() {
        assertEquals(Tema.CLARO, Tema.fromId("claro"))
        assertEquals(Tema.ESCURO, Tema.fromId("escuro"))
        assertEquals(Tema.SISTEMA, Tema.fromId("sistema"))
    }
}
