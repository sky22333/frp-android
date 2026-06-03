package com.sky22333.frpandroid.core.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sky22333.frpandroid.core.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = AppGraph.repository(context)
                val settings = repository.settings.first()
                if (settings.bootStartEnabled) {
                    val autoStartProfiles = repository.getAutoStartProfiles()
                    runCatching {
                        autoStartProfiles.forEach { profile ->
                            FrpForegroundService.startProfile(context, profile.id)
                        }
                    }.onFailure {
                        repository.setPendingStart(autoStartProfiles.isNotEmpty())
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
