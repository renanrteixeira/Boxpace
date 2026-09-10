package com.boxpace.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.boxpace.data.BuildConfig
import com.boxpace.data.cloud.CoordenadorDeSync
import com.boxpace.data.cloud.DriveCliente
import com.boxpace.data.cloud.TokenOAuthProvider
import com.boxpace.data.local.EncomendaDatabase
import com.boxpace.data.local.EncomendaLocalRepository
import com.boxpace.data.local.PreferenciasRepositoryImpl
import com.boxpace.data.remote.EncomendaRemoteDataSourceImpl
import com.boxpace.domain.EncomendaRemoteDataSource
import com.boxpace.domain.EncomendaRepository
import com.boxpace.domain.PreferenciasRepository
import com.boxpace.domain.SincronizacaoRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

/**
 * Ponto de injeção de dependência da camada `data` — fiação (engine Ktor +
 * base URL do scraper, banco Room + repositório local) para o `presentation`
 * consumir.
 *
 * Fonte canônica do contrato do scraper: a base URL vem do [BuildConfig] —
 * debug aponta para o backend local (`10.0.2.2`, emulador → host); para um
 * backend hospedado, passe `-Pscraper.baseUrl=https://...` na build (mesmo
 * valor vale para release, que deve usar HTTPS).
 */
object DataModule {

    private val BASE_URL: String = BuildConfig.SCRAPER_BASE_URL
    private const val TIMEOUT_MS = 10_000L

    /** `Json` do contrato HTTP — compartilhado pelo client e pelos testes. */
    val json: Json = Json { ignoreUnknownKeys = true }

    /**
     * `HttpClient` único e compartilhado entre chamadas: o engine OkHttp mantém
     * o connection pool e a arena de threads, então recriá-lo a cada rastreio
     * vazaria recursos.
     */
    private val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MS
                connectTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
        }
    }

    /** Data source remoto sem estado por chamada — também único. */
    private val remoteDataSource: EncomendaRemoteDataSource by lazy {
        EncomendaRemoteDataSourceImpl(
            client = httpClient,
            baseUrl = BASE_URL,
        )
    }

    /** Banco Room único (singleton manual) — evita múltiplas conexões ao mesmo arquivo. */
    @Volatile
    private var database: EncomendaDatabase? = null

    /**
     * Repositório local (Room) — espelho persistido das encomendas. O banco é
     * criado lazy a partir do [context] (application) na primeira chamada.
     */
    fun provideEncomendaRepository(context: Context): EncomendaRepository {
        val db = database ?: synchronized(this) {
            database ?: EncomendaDatabase.criar(context.applicationContext).also { database = it }
        }
        return EncomendaLocalRepository(
            database = db,
            json = json,
            // Gatilho da mutação (Epic 5): dispara o sync no coordenador (fire-and-forget).
            // Referencia o campo (não uma val local) para capturar o coordenador criado depois.
            aoRegistrarDelta = { coordenador?.dispararSync() },
        )
    }

    /** Coordenador de sincronização — único escritor do Drive (AD-SYNC-1). */
    @Volatile
    private var coordenador: CoordenadorDeSync? = null

    /** Escopo do coordenador: coroutines de sync em background (fire-and-forget). */
    private val syncScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    fun provideTokenOAuthProvider(): TokenOAuthProvider = tokenOAuthProvider

    fun provideDriveCliente(): DriveCliente =
        DriveCliente(client = httpClient, tokenProvider = tokenOAuthProvider, json = json)

    fun provideCoordenadorDeSync(context: Context): CoordenadorDeSync {
        coordenador?.let { return it }
        return synchronized(this) {
            coordenador ?: CoordenadorDeSync(
                encomendaRepository = provideEncomendaRepository(context),
                preferenciasRepository = providePreferenciasRepository(context),
                drive = provideDriveCliente(),
                tokenProvider = tokenOAuthProvider,
                scope = syncScope,
            ).also { coordenador = it }
        }
    }

    fun provideSincronizacaoRepository(context: Context): SincronizacaoRepository =
        provideCoordenadorDeSync(context)

    fun provideHttpClient(): HttpClient = httpClient

    /** Token OAuth em memória — único por processo (AD-SYNC-3). */
    private val tokenOAuthProvider: TokenOAuthProvider by lazy { TokenOAuthProvider() }

    fun provideEncomendaRemoteDataSource(): EncomendaRemoteDataSource = remoteDataSource

    /** DataStore Preferences compartilhado (chave-valor simples, ex.: tema) — único por processo. */
    @Volatile
    private var dataStore: DataStore<Preferences>? = null

    private fun provideDataStore(context: Context): DataStore<Preferences> {
        dataStore?.let { return it }
        return synchronized(this) {
            dataStore ?: PreferenceDataStoreFactory.create {
                context.applicationContext.preferencesDataStoreFile("boxpace_prefs")
            }.also { dataStore = it }
        }
    }

    fun providePreferenciasRepository(context: Context): PreferenciasRepository =
        PreferenciasRepositoryImpl(provideDataStore(context))
}