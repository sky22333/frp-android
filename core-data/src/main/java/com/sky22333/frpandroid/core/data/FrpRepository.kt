package com.sky22333.frpandroid.core.data

import com.sky22333.frpandroid.core.frp.FrpInstanceStatus
import com.sky22333.frpandroid.core.frp.FrpLog
import com.sky22333.frpandroid.core.frp.FrpLogSink
import com.sky22333.frpandroid.core.frp.FrpProfile
import com.sky22333.frpandroid.core.frp.FrpResult
import com.sky22333.frpandroid.core.frp.FrpRuntimeManager
import com.sky22333.frpandroid.core.frp.FrpRuntimeState
import com.sky22333.frpandroid.core.frp.FrpType
import com.sky22333.frpandroid.core.frp.LanguageMode
import com.sky22333.frpandroid.core.frp.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FrpRepository(
    private val dao: FrpDao,
    private val settingsStore: SettingsStore,
    private val runtimeManager: FrpRuntimeManager = FrpRuntimeManager(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val recentLogBuffer = ArrayDeque<FrpLog>()
    private val pendingLogBuffer = ArrayDeque<FrpLog>()
    private val logMutex = Mutex()
    private var flushJob: Job? = null

    val profiles: Flow<List<FrpProfile>> = dao.observeProfiles().map { entities ->
        entities.map { it.toModel() }
    }

    val runtimeStates: Flow<List<FrpRuntimeState>> = dao.observeRuntimeStates().map { entities ->
        entities.map { it.toModel() }
    }

    val settings: Flow<FrpSettings> = settingsStore.settings

    val isNativeAvailable: Boolean
        get() = runtimeManager.isNativeAvailable

    suspend fun initialize() {
        runtimeManager.registerLogCallbackOnce(
            FrpLogSink { log ->
                scope.launch { appendLog(log) }
            },
        )
        syncRuntimeStates()
    }

    fun observeLogs(
        instanceId: String? = null,
        type: String? = null,
        level: String? = null,
        keyword: String? = null,
        limit: Int = 200,
        offset: Int = 0,
    ): Flow<List<FrpLog>> = dao.observeLogs(
        instanceId = instanceId?.ifBlank { null },
        type = type?.ifBlank { null },
        level = level?.ifBlank { null },
        keyword = keyword?.ifBlank { null },
        limit = limit,
        offset = offset,
    ).map { entities -> entities.map { it.toModel() } }

    suspend fun upsertProfile(profile: FrpProfile) {
        dao.upsertProfile(profile.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    suspend fun deleteProfile(id: String) {
        dao.deleteProfile(id)
        dao.deleteRuntimeState(id)
    }

    suspend fun getProfile(id: String): FrpProfile? = dao.getProfile(id)?.toModel()

    suspend fun getAutoStartProfiles(): List<FrpProfile> =
        dao.getAutoStartProfiles().map { it.toModel() }

    suspend fun getNetworkRecoverableProfiles(): List<FrpProfile> {
        val states = dao.getRuntimeStates()
            .filter { it.state == FrpInstanceStatus.Running || it.state == FrpInstanceStatus.Failed }
            .associateBy { it.id }
        if (states.isEmpty()) return emptyList()
        return states.keys.mapNotNull { id -> dao.getProfile(id)?.toModel() }
    }

    suspend fun shouldReconnectOnNetworkRecovery(): Boolean =
        settings.first().networkReconnectEnabled

    suspend fun shouldAutoRetryFailures(): Boolean =
        settings.first().autoRetryEnabled

    suspend fun start(profile: FrpProfile): FrpResult {
        val result = runtimeManager.start(profile)
        val state = if (result.isSuccess) FrpInstanceStatus.Running else FrpInstanceStatus.Failed
        dao.upsertRuntimeState(
            FrpRuntimeState(profile.id, profile.type, state, result.message.ifBlank { null }).toEntity(),
        )
        appendLifecycleLog(profile.id, profile.type, result, "start")
        return result
    }

    suspend fun reload(profile: FrpProfile): FrpResult {
        val result = runtimeManager.reload(profile)
        if (result.isSuccess) {
            dao.upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        appendLifecycleLog(profile.id, profile.type, result, "reload")
        return result
    }

    suspend fun stop(profile: FrpProfile): FrpResult {
        val result = runtimeManager.stop(profile.id, profile.type)
        if (result.isSuccess) {
            dao.upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Stopped, null).toEntity())
        }
        appendLifecycleLog(profile.id, profile.type, result, "stop")
        return result
    }

    suspend fun stopAll(): FrpResult {
        val result = runtimeManager.stopAll()
        if (result.isSuccess) dao.clearRuntimeStates()
        appendLog(FrpLog("", "frp", if (result.isSuccess) "info" else "error", "stopAll ${result.message}", System.currentTimeMillis()))
        return result
    }

    suspend fun syncRuntimeStates(): List<FrpRuntimeState> {
        val states = runtimeManager.listInstances()
        states.forEach { dao.upsertRuntimeState(it.toEntity()) }
        return states
    }

    suspend fun pruneLogs(retentionDays: Int) {
        val olderThan = System.currentTimeMillis() - retentionDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        dao.deleteLogsOlderThan(olderThan)
    }

    suspend fun setBootStartEnabled(enabled: Boolean) = settingsStore.setBootStartEnabled(enabled)
    suspend fun setNetworkReconnectEnabled(enabled: Boolean) = settingsStore.setNetworkReconnectEnabled(enabled)
    suspend fun setAutoRetryEnabled(enabled: Boolean) = settingsStore.setAutoRetryEnabled(enabled)
    suspend fun setDiagnosticsSamplingEnabled(enabled: Boolean) = settingsStore.setDiagnosticsSamplingEnabled(enabled)
    suspend fun setLogRetentionDays(days: Int) = settingsStore.setLogRetentionDays(days)
    suspend fun setThemeMode(mode: ThemeMode) = settingsStore.setThemeMode(mode)
    suspend fun setLanguageMode(mode: LanguageMode) = settingsStore.setLanguageMode(mode)
    suspend fun setPendingStart(pending: Boolean) = settingsStore.setPendingStart(pending)

    private suspend fun appendLifecycleLog(id: String, type: FrpType, result: FrpResult, action: String) {
        val level = if (result.isSuccess) "info" else "error"
        val message = if (result.message.isBlank()) "$action success" else "$action ${result.message}"
        appendLog(FrpLog(id, type.name.lowercase(), level, redact(message), System.currentTimeMillis()))
    }

    private suspend fun appendLog(log: FrpLog) {
        logMutex.withLock {
            val safeLog = log.copy(message = redact(log.message))
            recentLogBuffer.addLast(safeLog)
            pendingLogBuffer.addLast(safeLog)
            while (recentLogBuffer.size > 1000) recentLogBuffer.removeFirst()
            if (flushJob?.isActive != true) {
                flushJob = scope.launch {
                    delay(750)
                    flushLogs()
                }
            }
        }
    }

    private suspend fun flushLogs() {
        val batch = logMutex.withLock {
            val items = pendingLogBuffer.toList()
            pendingLogBuffer.clear()
            items
        }
        if (batch.isNotEmpty()) dao.insertLogs(batch.map { it.toEntity() })
    }

    private fun redact(message: String): String =
        message
            .replace(Regex("(?i)(token|password|secret|authorization)\\s*=\\s*[^\\s,;]+"), "\$1=***")
            .replace(Regex("(?i)(token|password|secret|authorization):\\s*[^\\s,;]+"), "\$1: ***")
}
