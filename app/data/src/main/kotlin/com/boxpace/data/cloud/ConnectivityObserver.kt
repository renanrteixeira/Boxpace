package com.boxpace.data.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Observa a conectividade de rede (AD-SYNC-2). Sem worker periódico: apenas
 * emite quando a rede cai/volta, alimentando o gatilho de reconexão do
 * coordenador.
 */
class ConnectivityObserver(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _conectado = MutableStateFlow(estaConectado())
    val conectado: StateFlow<Boolean> = _conectado

    /** Emite `true` quando uma rede validada com internet fica disponível. */
    val reconexao: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // deixa onCapabilitiesChanged decidir: conectado exige internet E validação
            }

            override fun onLost(network: Network) {
                _conectado.value = estaConectado()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val valida = conexaoValida(
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
                if (valida && !_conectado.value) trySend(true)
                _conectado.value = valida
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun estaConectado(): Boolean {
        val rede = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(rede) ?: return false
        return conexaoValida(
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }

    companion object {
        /**
         * Decisão pura de conectividade (D-1): internet presente **e** validada —
         * só tráfego que o Android validou é tratado como conectado pelo sync.
         */
        internal fun conexaoValida(hasInternet: Boolean, hasValidated: Boolean): Boolean =
            hasInternet && hasValidated
    }
}
