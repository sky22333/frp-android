package com.sky22333.frpandroid.core.data

import android.content.Context

object AppGraph {
    @Volatile private var repository: FrpRepository? = null

    fun repository(context: Context): FrpRepository =
        repository ?: synchronized(this) {
            repository ?: FrpRepository(
                dao = FrpDatabase.get(context).frpDao(),
                settingsStore = SettingsStore(context.applicationContext),
                appCacheDir = context.applicationContext.cacheDir,
            ).also { repository = it }
        }
}
