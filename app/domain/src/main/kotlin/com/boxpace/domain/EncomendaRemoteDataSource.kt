package com.boxpace.domain

/**
 * Porta de saída efêmera e não-persistente para buscar rastreio no backend de
 * scraping. Definida no domínio para inverter a dependência: `data/remote`
 * implementa esta interface; o domínio não conhece HTTP.
 */
interface EncomendaRemoteDataSource {
    /**
     * Busca os eventos de rastreio de [codigo] na [transportadora] informada.
     *
     * @param cpfDestinatario opcional, apenas para provedores que exigem (J&T).
     *   Dado pessoal sensível — não deve ser logado.
     */
    suspend fun rastrear(
        codigo: String,
        transportadora: Transportadora,
        cpfDestinatario: String? = null,
    ): RastreioResult
}

/**
 * Resultado de uma consulta de rastreio na porta remota.
 */
sealed interface RastreioResult {
    /** Consulta bem-sucedida; [eventos] pode ser vazio (stub/nenhum evento). */
    data class Sucesso(val codigo: String, val eventos: List<Evento>) : RastreioResult

    /** O provedor ainda não implementa rastreio real (stub). */
    data class NaoImplementado(val transportadora: Transportadora) : RastreioResult
}
