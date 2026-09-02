package com.boxpace.data.remote

import com.boxpace.domain.EncomendaRemoteDataSource
import com.boxpace.domain.RastreioResult
import com.boxpace.domain.Transportadora

/**
 * Implementação da porta remota [EncomendaRemoteDataSource] sobre o backend de
 * scraping (HTTP). **Esqueleto desta story**: a comunicação HTTP/mapeamento real
 * é implementada nas Stories 1.2–1.6.
 */
class EncomendaRemoteDataSourceImpl : EncomendaRemoteDataSource {

    override suspend fun rastrear(
        codigo: String,
        transportadora: Transportadora,
        cpfDestinatario: String?,
    ): RastreioResult {
        // TODO(1.2+): chamar POST /rastrear do scraper e mapear a resposta.
        return RastreioResult.NaoImplementado(transportadora)
    }
}
