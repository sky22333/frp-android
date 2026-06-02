package com.sky22333.frpandroid.core.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    System,
    Light,
    Dark,
    Amoled,
}

enum class LanguageMode {
    System,
    Chinese,
    English,
}

data class FrpSettings(
    val bootStartEnabled: Boolean = false,
    val networkReconnectEnabled: Boolean = true,
    val autoRetryEnabled: Boolean = false,
    val diagnosticsSamplingEnabled: Boolean = false,
    val logRetentionDays: Int = 7,
    val themeMode: ThemeMode = ThemeMode.System,
    val languageMode: LanguageMode = LanguageMode.System,
    val pendingStart: Boolean = false,
)

private val Context.frpSettingsStore by preferencesDataStore("frp_settings")

class SettingsStore(private val context: Context) {
    val settings: Flow<FrpSettings> = context.frpSettingsStore.data.map { preferences ->
        FrpSettings(
            bootStartEnabled = preferences[Keys.bootStart] ?: false,
            networkReconnectEnabled = preferences[Keys.networkReconnect] ?: true,
            autoRetryEnabled = preferences[Keys.autoRetry] ?: false,
            diagnosticsSamplingEnabled = preferences[Keys.diagnosticsSampling] ?: false,
            logRetentionDays = preferences[Keys.logRetentionDays] ?: 7,
            themeMode = preferences.enumValue(Keys.themeMode, ThemeMode.System),
            languageMode = preferences.enumValue(Keys.languageMode, LanguageMode.System),
            pendingStart = preferences[Keys.pendingStart] ?: false,
        )
    }

    suspend fun setBootStartEnabled(enabled: Boolean) = set(Keys.bootStart, enabled)
    suspend fun setNetworkReconnectEnabled(enabled: Boolean) = set(Keys.networkReconnect, enabled)
    suspend fun setAutoRetryEnabled(enabled: Boolean) = set(Keys.autoRetry, enabled)
    suspend fun setDiagnosticsSamplingEnabled(enabled: Boolean) = set(Keys.diagnosticsSampling, enabled)
    suspend fun setLogRetentionDays(days: Int) = set(Keys.logRetentionDays, days.coerceIn(1, 365))
    suspend fun setThemeMode(mode: ThemeMode) = set(Keys.themeMode, mode.name)
    suspend fun setLanguageMode(mode: LanguageMode) = set(Keys.languageMode, mode.name)
    suspend fun setPendingStart(pending: Boolean) = set(Keys.pendingStart, pending)

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.frpSettingsStore.edit { it[key] = value }
    }

    private fun <T : Enum<T>> Preferences.enumValue(key: Preferences.Key<String>, defaultValue: T): T {
        val value = this[key] ?: return defaultValue
        return runCatching {
            java.lang.Enum.valueOf(defaultValue.declaringJavaClass, value)
        }.getOrDefault(defaultValue)
    }

    private object Keys {
        val bootStart = booleanPreferencesKey("boot_start")
        val networkReconnect = booleanPreferencesKey("network_reconnect")
        val autoRetry = booleanPreferencesKey("auto_retry")
        val diagnosticsSampling = booleanPreferencesKey("diagnostics_sampling")
        val logRetentionDays = intPreferencesKey("log_retention_days")
        val themeMode = stringPreferencesKey("theme_mode")
        val languageMode = stringPreferencesKey("language_mode")
        val pendingStart = booleanPreferencesKey("pending_start")
    }
}
