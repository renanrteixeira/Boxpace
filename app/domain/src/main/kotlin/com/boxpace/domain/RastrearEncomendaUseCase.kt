package com.boxpace.domain

/**
 * Use case: rastrear uma encomenda na transportadora, delegando a busca à porta
 * remota [EncomendaRemoteDataSource]. Mantém o domínio livre de HTTP/Android/Drive.
 */
class RastrearEncomendaUseCase(
    private val remote: EncomendaRemoteDataSource,
) {
    suspend fun executar(
        codigo: String,
        transportadora: Transportadora,
        cpfDestinatario: String? = null,
    ): RastreioResult {
        require(ValidadorDeCodigo.validar(codigo, transportadora)) {
            "codigo invalido para a transportadora: $transportadora"
        }
        return remote.rastrear(codigo.trim(), transportadora, cpfDestinatario)
    }
}
