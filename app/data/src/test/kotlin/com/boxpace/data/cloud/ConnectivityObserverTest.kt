package com.boxpace.data.cloud

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Decisão pura de conectividade (D-1): só tráfego com internet **e** validação
 * do Android conta como conectado. Testável sem Android SDK (função de companion).
 */
class ConnectivityObserverTest {

    @Test
    fun `conexao valida exige internet e rede validada`() {
        assertTrue(ConnectivityObserver.conexaoValida(hasInternet = true, hasValidated = true))
        assertFalse(ConnectivityObserver.conexaoValida(hasInternet = true, hasValidated = false))
        assertFalse(ConnectivityObserver.conexaoValida(hasInternet = false, hasValidated = true))
        assertFalse(ConnectivityObserver.conexaoValida(hasInternet = false, hasValidated = false))
    }
}