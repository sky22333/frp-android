package com.sky22333.frpandroid.core.data

import com.sky22333.frpandroid.core.frp.FrpInstanceStatus
import com.sky22333.frpandroid.core.frp.FrpLog
import com.sky22333.frpandroid.core.frp.FrpLogSink
import com.sky22333.frpandroid.core.frp.FrpProfile
import com.sky22333.frpandroid.core.frp.FrpResult
import com.sky22333.frpandroid.core.frp.FrpRuntimeGateway
import com.sky22333.frpandroid.core.frp.FrpRuntimeManager
import com.sky22333.frpandroid.core.frp.FrpRuntimeQueryResult
import com.sky22333.frpandroid.core.frp.FrpRuntimeState
import com.sky22333.frpandroid.core.frp.FrpType
import com.sky22333.frpandroid.core.frp.LanguageMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream

class FrpRepository(
    private val dao: FrpDao,
    private val settingsStore: SettingsGateway,
    private val appCacheDir: File,
    private val appFilesDir: File,
    private val runtimeManager: FrpRuntimeGateway = FrpRuntimeManager(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val logFlushDelayMs: Long = 750,
    private val logBatchSize: Int = 100,
    private val logBufferLimit: Int = 1_000,
    private val logTrimInterval: Int = 1_000,
    private val logMaxCount: Int = 100_000,
) {
    private val logChannel = Channel<FrpLog>(
        capacity = logBufferLimit,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val logWriteMutex = Mutex()
    private val initMutex = Mutex()
    private val runtimeOperationMutex = Mutex()
    private var logConsumerJob: Job? = null
    private var logsSinceTrim = 0
    private var initialized = false
    private var runtimeInitResult = FrpResult(code = null, message = "")

    init {
        startLogConsumer()
    }

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

    suspend fun deleteProfile(id: String): FrpResult {
        return runtimeOperationMutex.withLock {
            val profile = dao.getProfile(id)?.toModel()
            val state = dao.getRuntimeStates().firstOrNull { it.id == id }?.state
            if (profile != null && (state.isRecoverable() || state == FrpInstanceStatus.Stopping)) {
                val stopResult = stopLocked(profile)
                if (!stopResult.isSuccess) return@withLock stopResult
            }
            dao.deleteProfile(id)
            dao.deleteRuntimeState(id)
            withContext(Dispatchers.IO) { deleteTlsFiles(id) }
            FrpResult(code = null, message = "")
        }
    }

    suspend fun getProfile(id: String): FrpProfile? = dao.getProfile(id)?.toModel()

    fun getTlsFiles(profileId: String): List<TlsFileInfo> =
        TlsFileRole.entries.mapNotNull { role ->
            val file = tlsFile(profileId, role)
            if (file.isFile) TlsFileInfo(role, file.name, file.absolutePath) else null
        }

    fun importTlsFile(profileId: String, role: TlsFileRole, input: InputStream): TlsFileInfo {
        val target = tlsFile(profileId, role)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            input.use { source ->
                temporary.outputStream().use { output -> source.copyTo(output) }
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
            }
        } finally {
            temporary.delete()
        }
        return TlsFileInfo(role, target.name, target.absolutePath)
    }

    fun deleteTlsFile(profileId: String, role: TlsFileRole): Boolean =
        tlsFile(profileId, role).let { file -> !file.exists() || file.delete() }

    suspend fun getAutoStartProfiles(): List<FrpProfile> =
        dao.getAutoStartProfiles().map { it.toModel() }

    suspend fun getNetworkRecoverableProfiles(): List<FrpProfile> {
        val states = dao.getRuntimeStates()
            .filter { it.state.isRecoverable() }
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
        return state == FrpInstanceStatus.Running || state == FrpInstanceStatus.Stopping || state == FrpInstanceStatus.Failed
    }

    suspend fun start(profile: FrpProfile): FrpResult {
        val result = runtimeOperationMutex.withLock {
            startLocked(profile)
        }
        appendLifecycleLog(profile.id, profile.type, result, "start")
        return result
    }

    suspend fun recoverProfile(id: String): FrpResult? {
        val recovered = runtimeOperationMutex.withLock {
            val profile = dao.getProfile(id)?.toModel() ?: return@withLock null
            val state = dao.getRuntimeStates().firstOrNull { it.id == id }?.state
            if (!state.isRecoverable()) return@withLock null
            profile to startLocked(profile)
        } ?: return null
        appendLifecycleLog(recovered.first.id, recovered.first.type, recovered.second, "recover")
        return recovered.second
    }

    suspend fun reload(profile: FrpProfile): FrpResult {
        val result = runtimeOperationMutex.withLock {
            reloadLocked(profile)
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
        val result = runtimeOperationMutex.withLock {
            stopLocked(profile)
        }
        appendLifecycleLog(profile.id, profile.type, result, "stop")
        return result
    }

    suspend fun stopAll(): FrpResult {
        val result = runtimeOperationMutex.withLock {
            dao.getRuntimeStates()
                .filter { it.state == FrpInstanceStatus.Running || it.state == FrpInstanceStatus.Stopping }
                .forEach { state ->
                    dao.upsertRuntimeState(state.toModel().copy(state = FrpInstanceStatus.Stopping, lastError = null).toEntity())
            }
            runtimeManager.stopAll().also {
                syncRuntimeStatesFromNative()
            }
        }
        appendLog(FrpLog("", "frp", if (result.isSuccess) "info" else "error", "stopAll ${result.message}", System.currentTimeMillis()))
        return result
    }

    suspend fun syncRuntimeStates(): List<FrpRuntimeState> {
        val ready = ensureRuntimeReady()
        if (!ready.isSuccess) return emptyList()
        return runtimeOperationMutex.withLock {
            syncRuntimeStatesFromNative()
        }
    }

    private suspend fun syncRuntimeStatesFromNative(): List<FrpRuntimeState> {
        val query = runtimeManager.listInstances()
        if (query is FrpRuntimeQueryResult.Failure) {
            appendLog(FrpLog("", "frp", "error", query.message, System.currentTimeMillis()))
            return emptyList()
        }
        val states = (query as FrpRuntimeQueryResult.Success).states
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

    private suspend fun startLocked(profile: FrpProfile): FrpResult {
        val ready = ensureRuntimeReady()
        val tlsFilesReady = if (ready.isSuccess) validateManagedTlsFiles(profile) else ready
        val result = if (tlsFilesReady.isSuccess) runtimeManager.start(profile) else tlsFilesReady
        when {
            result.isSuccess || result.isAlreadyRunning -> {
                dao.upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
            }
            result.isInvalidToml || result.isTlsFileMissing -> {
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
        return result
    }

    private suspend fun stopLocked(profile: FrpProfile): FrpResult {
        dao.upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Stopping, null).toEntity())
        return runtimeManager.stop(profile.id, profile.type).also {
            syncRuntimeStatesFromNative()
        }
    }

    private suspend fun reloadLocked(profile: FrpProfile): FrpResult {
        val ready = ensureRuntimeReady()
        val tlsFilesReady = if (ready.isSuccess) validateManagedTlsFiles(profile) else ready
        val result = if (tlsFilesReady.isSuccess) runtimeManager.reload(profile) else tlsFilesReady
        when {
            result.isSuccess || result.isAlreadyRunning -> {
                dao.upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
            }
            result.isInvalidToml || result.isTlsFileMissing -> Unit
            else -> {
                dao.upsertRuntimeState(
                    FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Failed, result.message.ifBlank { null }).toEntity(),
                )
            }
        }
        return result
    }

    suspend fun pruneLogs(retentionDays: Int) {
        val olderThan = System.currentTimeMillis() - retentionDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        dao.deleteLogsOlderThan(olderThan)
    }

    suspend fun clearLogs() {
        logConsumerJob?.cancelAndJoin()
        while (logChannel.tryReceive().isSuccess) Unit
        try {
            logWriteMutex.withLock {
                logsSinceTrim = 0
                dao.clearLogs()
            }
        } finally {
            startLogConsumer()
        }
    }

    suspend fun setBootStartEnabled(enabled: Boolean) = settingsStore.setBootStartEnabled(enabled)
    suspend fun setNetworkReconnectEnabled(enabled: Boolean) = settingsStore.setNetworkReconnectEnabled(enabled)
    suspend fun setAutoRetryEnabled(enabled: Boolean) = settingsStore.setAutoRetryEnabled(enabled)
    suspend fun setScreenOffKeepAliveEnabled(enabled: Boolean) = settingsStore.setScreenOffKeepAliveEnabled(enabled)
    suspend fun setLogRetentionDays(days: Int) = settingsStore.setLogRetentionDays(days)
    suspend fun setThemeSeedColor(color: Int) = settingsStore.setThemeSeedColor(color)
    suspend fun setLanguageMode(mode: LanguageMode) = settingsStore.setLanguageMode(mode)
    suspend fun setPendingStart(pending: Boolean) = settingsStore.setPendingStart(pending)

    private suspend fun appendLifecycleLog(id: String, type: FrpType, result: FrpResult, action: String) {
        val level = if (result.isSuccess) "info" else "error"
        val message = if (result.message.isBlank()) "$action success" else "$action ${result.message}"
        appendLog(FrpLog(id, type.name.lowercase(), level, message, System.currentTimeMillis()))
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
                    logChannel.trySend(log)
                },
            )
            initialized = true
            FrpResult(code = null, message = "")
        }

    private fun appendLog(log: FrpLog) {
        logChannel.trySend(log)
    }

    private fun startLogConsumer() {
        if (logConsumerJob?.isActive == true) return
        logConsumerJob = scope.launch {
            consumeLogs()
        }
    }

    private suspend fun consumeLogs() {
        while (true) {
            val batch = ArrayList<FrpLog>(logBatchSize)
            batch += logChannel.receive()
            if (batch.size < logBatchSize) {
                withTimeoutOrNull(logFlushDelayMs) {
                    while (batch.size < logBatchSize) {
                        batch += logChannel.receive()
                    }
                }
            }

            persistLogs(batch)
        }
    }

    private suspend fun persistLogs(logs: List<FrpLog>) {
        val safeLogs = logs.map { log -> log.copy(message = redact(log.message)).toEntity() }
        logWriteMutex.withLock {
            dao.insertLogs(safeLogs)
            logsSinceTrim += logs.size
            if (logsSinceTrim >= logTrimInterval) {
                dao.trimLogs(logMaxCount)
                logsSinceTrim %= logTrimInterval
            }
        }
    }

    private fun redact(message: String): String =
        message
            .replace(Regex("(?i)(token|password|secret|authorization)\\s*=\\s*[^\\s,;]+"), "\$1=***")
            .replace(Regex("(?i)(token|password|secret|authorization):\\s*[^\\s,;]+"), "\$1: ***")

    private fun validateManagedTlsFiles(profile: FrpProfile): FrpResult {
        val managedDirectory = tlsDirectory(profile.id).canonicalFile
        val missing = runtimeManager.tlsFilePaths(profile.toml)
            .firstOrNull { path ->
                val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return@firstOrNull false
                file.path.startsWith(managedDirectory.path + File.separator) && !file.isFile
            }
        return if (missing == null) {
            FrpResult(code = null, message = "")
        } else {
            FrpResult(code = "TLS_FILE_MISSING", message = "TLS_FILE_MISSING: $missing")
        }
    }

    private fun deleteTlsFiles(profileId: String) {
        tlsDirectory(profileId).deleteRecursively()
    }

    private fun tlsFile(profileId: String, role: TlsFileRole): File =
        File(tlsDirectory(profileId), role.fileName)

    private fun tlsDirectory(profileId: String): File {
        val root = File(appFilesDir, "certificates").canonicalFile
        val directory = File(root, profileId).canonicalFile
        require(directory.path.startsWith(root.path + File.separator)) { "Invalid profile ID" }
        return directory
    }

    private fun FrpInstanceStatus?.isRecoverable(): Boolean =
        this == FrpInstanceStatus.Running || this == FrpInstanceStatus.Failed
}
