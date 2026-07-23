package com.sky22333.frpandroid

import android.app.Application
import com.sky22333.frpandroid.core.runtime.FrpForegroundService
import com.sky22333.frpandroid.core.runtime.LogCleanupWorker
import com.sky22333.frpandroid.core.runtime.NetworkReconnectMonitor

class FrpAndroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FrpForegroundService.restoreDesired(this)
        LogCleanupWorker.schedule(this)
        NetworkReconnectMonitor.start(this)
    }
}
