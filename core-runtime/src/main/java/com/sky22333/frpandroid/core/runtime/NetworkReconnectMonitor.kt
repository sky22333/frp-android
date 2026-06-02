package com.sky22333.frpandroid.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.sky22333.frpandroid.core.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NetworkReconnectMonitor private constructor(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconnectMutex = Mutex()
    private var registered = false

    fun start() {
        if (registered) return
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { reconnectRunningProfiles() }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.registerDefaultNetworkCallback(callback)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            manager.registerNetworkCallback(request, callback)
        }
        registered = true
    }

    private suspend fun reconnectRunningProfiles() {
        reconnectMutex.withLock {
            val repository = AppGraph.repository(context)
            if (!repository.shouldReconnectOnNetworkRecovery()) return
            repository.getNetworkRecoverableProfiles().forEach { profile ->
                runCatching {
                    FrpForegroundService.startProfile(context, profile.id)
                }.onFailure {
                    repository.setPendingStart(true)
                }
            }
        }
    }

    companion object {
        @Volatile private var instance: NetworkReconnectMonitor? = null

        fun start(context: Context) {
            instance ?: synchronized(this) {
                instance ?: NetworkReconnectMonitor(context.applicationContext).also { instance = it }
            }.start()
        }
    }
}
