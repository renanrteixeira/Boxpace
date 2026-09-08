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

    /** Emite `true` quando uma rede com internet fica disponível. */
    val reconexao: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _conectado.value = true
                trySend(true)
            }

            override fun onLost(network: Network) {
                _conectado.value = estaConectado()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val temInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (temInternet && !_conectado.value) trySend(true)
                _conectado.value = temInternet
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
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
