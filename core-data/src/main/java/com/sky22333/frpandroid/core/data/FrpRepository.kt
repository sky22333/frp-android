package com.sky22333.frpandroid.core.data

import com.sky22333.frpandroid.core.frp.FrpInstanceStatus
import com.sky22333.frpandroid.core.frp.FrpLog
import com.sky22333.frpandroid.core.frp.FrpLogSink
import com.sky22333.frpandroid.core.frp.FrpProfile
import com.sky22333.frpandroid.core.frp.FrpResult
import com.sky22333.frpandroid.core.frp.FrpRuntimeGateway
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
import java.io.File

class FrpRepository(
    private val dao: FrpDao,
    private val settingsStore: SettingsGateway,
    private val appCacheDir: File,
    private val runtimeManager: FrpRuntimeGateway = FrpRuntimeManager(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val logFlushDelayMs: Long = 750,
) {
    private val recentLogBuffer = ArrayDeque<FrpLog>()
    private val pendingLogBuffer = ArrayDeque<FrpLog>()
    private val logMutex = Mutex()
    private val initMutex = Mutex()
    private var flushJob: Job? = null
    private var initialized = false
    private var runtimeInitResult = FrpResult(code = null, message = "")

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
        val ready = ensureRuntimeReady()
        if (!ready.isSuccess) return
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
        val profile = dao.getProfile(id)?.toModel()
        if (profile != null) {
            val state = dao.getRuntimeStates().firstOrNull { it.id == id }?.state
            if (state == FrpInstanceStatus.Running || state == FrpInstanceStatus.Failed) {
                val stopResult = stop(profile)
                if (!stopResult.isSuccess) return
            }
        }
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

    suspend fun diagnostics(): FrpDiagnostics {
        val states = dao.getRuntimeStates()
        val currentSettings = settings.first()
        return FrpDiagnostics(
            nativeAvailable = isNativeAvailable,
            runtimeInitialized = initialized,
            tempDirStatus = runtimeInitResult.message.ifBlank { "OK" },
            runningCount = states.count { it.state == FrpInstanceStatus.Running },
            failedCount = states.count { it.state == FrpInstanceStatus.Failed },
            pendingStart = currentSettings.pendingStart,
            lastError = states.lastOrNull { !it.lastError.isNullOrBlank() }?.lastError,
        )
    }

    fun validateToml(toml: String): FrpResult = runtimeManager.validateToml(toml)

    suspend fun isProfileActive(id: String): Boolean {
        val state = dao.getRuntimeStates().firstOrNull { it.id == id }?.state
        return state == FrpInstanceStatus.Running || state == FrpInstanceStatus.Failed
    }

    suspend fun start(profile: FrpProfile): FrpResult {
        val ready = ensureRuntimeReady()
        val result = if (ready.isSuccess) runtimeManager.start(profile) else ready
        when {
            result.isSuccess || result.isAlreadyRunning -> {
                dao.upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
            }
            result.isInvalidToml -> {
                val current = dao.getRuntimeStates().firstOrNull { it.id == profile.id }
                if (current == null) {
                    dao.upsertRuntimeState(
                        FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Stopped, result.message).toEntity(),
                    )
                }
            }
            else -> {
                dao.upsertRuntimeState(
                    FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Failed, result.message.ifBlank { null }).toEntity(),
                )
            }
        }
        appendLifecycleLog(profile.id, profile.type, result, "start")
        return result
    }

    suspend fun reload(profile: FrpProfile): FrpResult {
        val ready = ensureRuntimeReady()
        val result = if (ready.isSuccess) runtimeManager.reload(profile) else ready
        when {
            result.isSuccess || result.isAlreadyRunning -> {
                dao.upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
            }
            result.isInvalidToml -> Unit
            else -> {
                dao.upsertRuntimeState(
                    FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Failed, result.message.ifBlank { null }).toEntity(),
                )
            }
        }
        appendLifecycleLog(profile.id, profile.type, result, "reload")
        return result
    }

    suspend fun saveAndRestart(profile: FrpProfile): FrpResult {
        val validation = validateToml(profile.toml)
        if (!validation.isSuccess) {
            appendLifecycleLog(profile.id, profile.type, validation, "validate")
            return validation
        }

        return if (isProfileActive(profile.id)) {
            val result = reload(profile)
            if (result.isSuccess || result.isAlreadyRunning) {
                upsertProfile(profile)
            }
            result
        } else {
            upsertProfile(profile)
            FrpResult(code = null, message = "")
        }
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
        val ready = ensureRuntimeReady()
        if (!ready.isSuccess) return emptyList()
        val states = runtimeManager.listInstances()
        val nativeIds = states.map { it.id }.toSet()
        dao.getRuntimeStates()
            .filter { it.id !in nativeIds && it.state != FrpInstanceStatus.Stopped }
            .forEach { stale ->
                dao.upsertRuntimeState(
                    FrpRuntimeState(stale.id, stale.type, FrpInstanceStatus.Stopped, null).toEntity(),
                )
            }
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

    private suspend fun ensureRuntimeReady(): FrpResult =
        initMutex.withLock {
            if (initialized) return@withLock FrpResult(code = null, message = "")

            val tempDirResult = runtimeManager.configureTempDir(appCacheDir)
            runtimeInitResult = tempDirResult
            if (!tempDirResult.isSuccess) {
                appendLog(FrpLog("", "frp", "error", tempDirResult.message, System.currentTimeMillis()))
                return@withLock tempDirResult
            }

            runtimeManager.registerLogCallbackOnce(
                FrpLogSink { log ->
                    scope.launch { appendLog(log) }
                },
            )
            initialized = true
            FrpResult(code = null, message = "")
        }

    private suspend fun appendLog(log: FrpLog) {
        logMutex.withLock {
            val safeLog = log.copy(message = redact(log.message))
            recentLogBuffer.addLast(safeLog)
            pendingLogBuffer.addLast(safeLog)
            while (recentLogBuffer.size > 1000) recentLogBuffer.removeFirst()
            if (flushJob?.isActive != true) {
                flushJob = scope.launch {
                    delay(logFlushDelayMs)
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
