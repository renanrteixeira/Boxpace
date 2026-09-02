package com.boxpace.domain

/**
 * Falha de fronteira do rastreio mapeada para mensagem de usuário (microcopy do
 * EXPERIENCE.md — o ViewModel apenas reflete [mensagem]; nada de HTTP na UI).
 *
 * Pertence ao domínio (AD-1/Regra de dependência): `presentation` depende de
 * `domain`, nunca de `data`, então a falha precisa viver aqui para a camada de
 * UI distinguir sem conhecer o transporte.
 *
 * - [SemConexao]: rede ausente, timeout ou back-end 5xx/resto — `Sem conexão agora`.
 * - [CodigoNaoEncontrado]: correios respondeu 4xx (objeto não encontrado/captcha) — `Confere o código?`.
 */
sealed class ErroDeRastreio(
    val mensagem: String,
    cause: Throwable? = null,
) : Exception(mensagem, cause) {
    class SemConexao(cause: Throwable? = null) : ErroDeRastreio("Sem conexão agora", cause)
    class CodigoNaoEncontrado(cause: Throwable? = null) : ErroDeRastreio("Confere o código?", cause)
}