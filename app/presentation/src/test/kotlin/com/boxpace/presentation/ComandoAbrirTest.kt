package com.boxpace.presentation

import android.content.Intent
import com.boxpace.extrairIdDeAbertura
import com.boxpace.presentation.notificacao.NotificadorTransicao
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Testes da decodificação do deep link de abertura (TOCAR_NOTIFICACAO).
 *
 * [extrairIdDeAbertura] só produz um `id` quando a action é a de abrir detalhe
 * e há `EXTRA_ID` — cobrindo o caso válido, action errada e extra ausente.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComandoAbrirTest {

    @Test
    fun abrir_acao_com_extra_decodifica_id() {
        val intent = Intent().apply {
            action = NotificadorTransicao.ACAO_ABRIR_DETALHE
            putExtra(NotificadorTransicao.EXTRA_ID, "correios:AA123456789BR")
        }

        assertEquals("correios:AA123456789BR", extrairIdDeAbertura(intent))
    }

    @Test
    fun acao_diferente_decodifica_nulo() {
        val intent = Intent().apply {
            action = "com.boxpace.OUTRA_ACAO"
            putExtra(NotificadorTransicao.EXTRA_ID, "correios:AA123456789BR")
        }

        assertNull(extrairIdDeAbertura(intent))
    }

    @Test
    fun sem_extra_decodifica_nulo() {
        val intent = Intent().apply {
            action = NotificadorTransicao.ACAO_ABRIR_DETALHE
        }

        assertNull(extrairIdDeAbertura(intent))
    }

    @Test
    fun intent_nulo_decodifica_nulo() {
        assertNull(extrairIdDeAbertura(null))
    }
}
