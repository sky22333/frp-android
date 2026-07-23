package com.sky22333.frpandroid.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
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
    private var lastReconnectAt = 0L

    fun start() {
        if (registered) return
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { reconnectRunningProfiles() }
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        registered = true
    }

    private suspend fun reconnectRunningProfiles() {
        reconnectMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            if (now - lastReconnectAt < RECONNECT_DEBOUNCE_MS) return
            lastReconnectAt = now
            val repository = AppGraph.repository(context)
            if (!repository.shouldReconnectOnNetworkRecovery()) return
            repository.getDesiredRunningProfiles().forEach { profile ->
                runCatching {
                    FrpForegroundService.recoverProfile(context, profile.id, attempt = 0, reason = RecoveryReason.Network)
                }.onFailure {
                    repository.setPendingStart(true)
                    FrpRetryWorker.enqueue(context, profile.id, attempt = 1, reason = RecoveryReason.Network)
                }
            }
        }
    }

    companion object {
        private const val RECONNECT_DEBOUNCE_MS = 5_000L
        @Volatile private var instance: NetworkReconnectMonitor? = null

        fun start(context: Context) {
            instance ?: synchronized(this) {
                instance ?: NetworkReconnectMonitor(context.applicationContext).also { instance = it }
            }.start()
        }
    }
}
