package com.flowseal.tgwsproxy.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.flowseal.tgwsproxy.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Мониторинг смены сети (фича из анализа конкурентов: Wi-Fi <-> mobile рвёт
 * WS-пулы, а reconnect-шторм выглядит как «прокси сломался»). При появлении
 * сети после потери дёргает [onChanged] с debounce, чтобы серия onLost/onAvailable
 * от handoff не вызвала несколько рестартов.
 */
class NetworkMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val onChanged: suspend () -> Unit,
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** null — ещё не видели сеть с момента регистрации (первый available игнорируем). */
    @Volatile
    private var hadNetwork: Boolean? = null

    private var debounce: Job? = null

    fun register() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val prev = hadNetwork
                hadNetwork = true
                if (prev == false) {
                    trigger("network regained")
                } else {
                    Log.debug("NetworkMonitor: onAvailable (initial=$prev)")
                }
            }

            override fun onLost(network: Network) {
                hadNetwork = false
                Log.debug("NetworkMonitor: onLost")
            }
        }
        callback = cb
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
            Log.info("NetworkMonitor registered")
        } catch (e: Exception) {
            Log.error("NetworkMonitor register failed", e)
        }
    }

    fun unregister() {
        debounce?.cancel()
        debounce = null
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
    }

    private fun trigger(reason: String) {
        debounce?.cancel()
        debounce = scope.launch {
            delay(DEBOUNCE_MS)
            Log.warn("NetworkMonitor: network changed ($reason), resetting pools")
            try {
                onChanged()
            } catch (e: Exception) {
                Log.error("NetworkMonitor onChanged failed", e)
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 2_000L
    }
}
