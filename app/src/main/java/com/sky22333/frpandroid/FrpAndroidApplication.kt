package com.sky22333.frpandroid

import android.app.Application
import com.sky22333.frpandroid.core.runtime.FrpForegroundService
import com.sky22333.frpandroid.core.runtime.LogCleanupWorker
import com.sky22333.frpandroid.core.runtime.NetworkReconnectMonitor

class FrpAndroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Align Room runtime_states with native after process death (clears stale Stopping/Running).
        FrpForegroundService.sync(this)
        LogCleanupWorker.schedule(this)
        NetworkReconnectMonitor.start(this)
    }
}
