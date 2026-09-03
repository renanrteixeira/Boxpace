package com.boxpace.data.remote

import com.boxpace.domain.EncomendaRemoteDataSource
import com.boxpace.domain.ErroDeRastreio
import com.boxpace.domain.RastreioResult
import com.boxpace.domain.Transportadora
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * Implementação real da porta remota [EncomendaRemoteDataSource] sobre o backend
 * de scraping: `POST {base}/rastrear` com o contrato fixo (AD-SCRAPER-CONTRACT),
 * timeout, e erros de rede/4xx/5xx mapeados para [ErroDeRastreio].
 *
 * Cold start (AC 1.3): se a primeira tentativa falha (~timeout, servidor dormindo)
 * re-tenta uma única vez com [TIMEOUT_COLD_START_MS] de 30s para essa requisição.
 */
class EncomendaRemoteDataSourceImpl(
    private val client: HttpClient,
    private val baseUrl: String,
) : EncomendaRemoteDataSource {

    override suspend fun rastrear(
        codigo: String,
        transportadora: Transportadora,
        cpfDestinatario: String?,
    ): RastreioResult {
        val cpfSanitizado = cpfDestinatario
            ?.filter(Char::isDigit)
            ?.takeIf { it.isNotEmpty() }

        val resposta = try {
            postRastrear(codigo, transportadora, cpfSanitizado, timeoutMs = null)
        } catch (e: Exception) {
            // cold start do scraper: em vez de falhar já de cara, dá uma nova
            // chance com timeout de 30s só para esta requisição.
            try {
                postRastrear(codigo, transportadora, cpfSanitizado, timeoutMs = TIMEOUT_COLD_START_MS)
            } catch (e2: Exception) {
                throw ErroDeRastreio.SemConexao(e2)
            }
        }

        return when {
            resposta.status.isSuccess() -> {
                try {
                    val corpo: ContratoRastrearResponse = resposta.body()
                    RastreioResult.Sucesso(
                        codigo = corpo.codigo,
                        eventos = corpo.eventos.map(RastreioMapper::paraEvento),
                    )
                } catch (e: Exception) {
                    throw ErroDeRastreio.SemConexao(e)
                }
            }
            resposta.status.value == 501 -> RastreioResult.NaoImplementado(transportadora)
            resposta.status.value in 400 until 500 -> throw ErroDeRastreio.CodigoNaoEncontrado()
            else -> throw ErroDeRastreio.SemConexao()
        }
    }

    private suspend fun postRastrear(
        codigo: String,
        transportadora: Transportadora,
        cpfSanitizado: String?,
        timeoutMs: Long?,
    ): HttpResponse = client.post("$baseUrl/rastrear") {
        header(HttpHeaders.ContentType, "application/json")
        header(HttpHeaders.Accept, "application/json")
        timeouts(timeoutMs)
        setBody(
            ContratoRastrearRequest(
                transportadora = transportadora.scraperId,
                codigo = codigo.trim(),
                cpf = cpfSanitizado,
            ),
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.timeouts(timeoutMs: Long?) {
        timeoutMs?.let { ms ->
            timeout {
                requestTimeoutMillis = ms
                connectTimeoutMillis = ms
                socketTimeoutMillis = ms
            }
        }
    }

    private companion object {
        const val TIMEOUT_COLD_START_MS = 30_000L
    }
}