package com.boxpace.data.di

import com.boxpace.data.remote.EncomendaRemoteDataSourceImpl
import com.boxpace.domain.EncomendaRemoteDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Ponto de injeção de dependência da camada `data` — fiação básica do remote
 * (engine Ktor + base URL do scraper) para o `presentation` consumir.
 *
 * Fonte canônica do contrato do scraper: a base URL aponta para o backend local
 * de scraping (via `10.0.2.2` no emulador Android); quando o scraper for
 * hospedado, basta trocar [BASE_URL].
 */
object DataModule {

    private const val BASE_URL = "http://10.0.2.2:8000"
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

    fun provideHttpClient(): HttpClient = httpClient

    fun provideEncomendaRemoteDataSource(): EncomendaRemoteDataSource = remoteDataSource
}