package com.boxpace.presentation.notificacao

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.boxpace.domain.Encomenda
import com.boxpace.domain.Transportadora
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Testes do [NotificadorTransicao] (Story 3.1).
 *
 * Cobre a regra AD-DADO-SENSIVEL (título cita a etiqueta, **nunca** o CPF) e a
 * supressão silenciosa quando `POST_NOTIFICATIONS` está negada
 * (PERMISSAO_NEGADA no I/O & Edge-Case Matrix).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificadorTransicaoTest {

    private val application = RuntimeEnvironment.getApplication()

    private val gerente: NotificationManager
        get() = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val shadowGerente: ShadowNotificationManager
        get() = org.robolectric.Shadows.shadowOf(gerente)

    private fun encomenda(
        etiqueta: String = "Fone de ouvido",
        cpf: String? = "12345678909",
    ) = Encomenda(
        id = "correios:AA123456789BR",
        codigo = "AA123456789BR",
        transportadora = Transportadora.CORREIOS,
        etiqueta = etiqueta,
        ultimoStatus = "Saiu para entrega",
        criadaEm = "2026-09-01T12:00:00Z",
        atualizadaEm = "2026-09-01T12:00:00Z",
        cpfDestinatario = cpf,
    )

    private fun concederPermissao() =
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun com_permissao_notifica_com_etiqueta_sem_cpf() {
        concederPermissao()
        val notificador = NotificadorTransicao(application)

        notificador.notificarTransicao(encomenda())

        val notificacao = shadowGerente.allNotifications
        assertTrue(notificacao.size == 1, "deve publicar exatamente uma notificação")
        val titulo = notificacao.single()
            .extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertTrue(titulo.contains("Fone de ouvido"), "título deve citar a etiqueta")
        assertTrue(titulo.contains(" — "), "título deve seguir o padrão UX-DR8 (status — etiqueta)")
        assertFalse(titulo.contains("12345678909"), "título jamais deve citar o CPF")
    }

    @Test
    fun sem_permissao_nao_notifica_nada() {
        // sem conceder: POST_NOTIFICATIONS negada → worker roda, fica silencioso
        val notificador = NotificadorTransicao(application)

        notificador.notificarTransicao(encomenda())

        assertTrue(shadowGerente.allNotifications.isEmpty(), "não deve publicar notificação sem permissão")
    }
}
