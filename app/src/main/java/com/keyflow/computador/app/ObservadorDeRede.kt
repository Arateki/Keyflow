package com.keyflow.computador.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Há rede? A reconexão só tenta com `true` (CMP-22). Começa `true`: sem informação, deixa a conexão tentar e falhar com o motivo. */
class ObservadorDeRede(contexto: Context) {
    private val _temRede = MutableStateFlow(true)
    val temRede: StateFlow<Boolean> = _temRede

    init {
        val cm = contexto.applicationContext.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            runCatching {
                _temRede.value = cm.activeNetwork != null
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { _temRede.value = true }
                    override fun onLost(network: Network) { _temRede.value = cm.activeNetwork != null }
                })
            }
        }
    }
}
