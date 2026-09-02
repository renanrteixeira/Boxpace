package com.boxpace.data.remote

import com.boxpace.domain.EncomendaRemoteDataSource
import com.boxpace.domain.ErroDeRastreio
import com.boxpace.domain.RastreioResult
import com.boxpace.domain.Transportadora
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * Implementação real da porta remota [EncomendaRemoteDataSource] sobre o backend
 * de scraping: `POST {base}/rastrear` com o contrato fixo (AD-SCRAPER-CONTRACT),
 * timeout, e erros de rede/4xx/5xx mapeados para [ErroDeRastreio].
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
            client.post("$baseUrl/rastrear") {
                header(HttpHeaders.ContentType, "application/json")
                header(HttpHeaders.Accept, "application/json")
                setBody(
                    ContratoRastrearRequest(
                        transportadora = transportadora.scraperId,
                        codigo = codigo.trim(),
                        cpf = cpfSanitizado,
                    ),
                )
            }
        } catch (e: Exception) {
            throw ErroDeRastreio.SemConexao(e)
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
}