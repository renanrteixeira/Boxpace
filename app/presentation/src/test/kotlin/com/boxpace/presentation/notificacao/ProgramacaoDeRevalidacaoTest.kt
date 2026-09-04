package com.boxpace.presentation.notificacao

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Testes da programação do refresh de background (Story 3.1) — cobre os
 * não-negociáveis sem depender do runtime do WorkManager:
 * intervalo ≥ 30 min, backoff exponencial e network constraint.
 */
class ProgramacaoDeRevalidacaoTest {

    @Test
    fun `intervalo e de no minimo 30 minutos`() {
        val request = ProgramacaoDeRevalidacao.criarRequest()
        // `intervalDuration` é expresso em milissegundos no WorkSpec.
        val intervaloSegundos = TimeUnit.MILLISECONDS.toSeconds(request.workSpec.intervalDuration)

        assert(intervaloSegundos >= 30 * 60) {
            "intervalo deve ser >= 30 min, mas era ${intervaloSegundos / 60} min"
        }
        assertEquals(30, intervaloSegundos / 60, "intervalo deve ser exatamente o mínimo contratado (30 min)")
    }

    @Test
    fun `backoff e exponencial em falha`() {
        val request = ProgramacaoDeRevalidacao.criarRequest()
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
    }

    @Test
    fun `exige conectividade de rede`() {
        val request = ProgramacaoDeRevalidacao.criarRequest()
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `work e identificado de forma estavel`() {
        assertEquals("revalidacao_periodica", ProgramacaoDeRevalidacao.WORK_NOME)
        assertEquals(30L, ProgramacaoDeRevalidacao.INTERVALO_MINIMO_MINUTOS)
    }
}
