package com.sky22333.frpandroid.core.data

import com.sky22333.frpandroid.core.frp.FrpInstanceStatus
import com.sky22333.frpandroid.core.frp.FrpLog
import com.sky22333.frpandroid.core.frp.FrpLogSink
import com.sky22333.frpandroid.core.frp.FrpProfile
import com.sky22333.frpandroid.core.frp.FrpResult
import com.sky22333.frpandroid.core.frp.FrpRuntimeGateway
import com.sky22333.frpandroid.core.frp.FrpRuntimeQueryResult
import com.sky22333.frpandroid.core.frp.FrpRuntimeState
import com.sky22333.frpandroid.core.frp.FrpType
import com.sky22333.frpandroid.core.frp.LanguageMode
import com.sky22333.frpandroid.core.frp.ThemeMode
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FrpRepositoryTest {
    private val profile = FrpProfile(
        id = "client-a",
        name = "client-a",
        type = FrpType.Client,
        toml = "serverAddr = \"127.0.0.1\"",
        autoStart = false,
        updatedAt = 1,
    )

    @Test
    fun alreadyRunningStartIsStoredAsRunning() = runTest {
        val dao = FakeFrpDao().apply { upsertProfile(profile.toEntity()) }
        val runtime = FakeRuntime(startResult = FrpResult.fromRaw("ALREADY_RUNNING: client-a"))
        val repository = repository(dao, runtime)

        val result = repository.start(profile)

        assertTrue(result.isAlreadyRunning)
        assertEquals(FrpInstanceStatus.Running, dao.runtimeState(profile.id)?.state)
        assertNull(dao.runtimeState(profile.id)?.lastError)
    }

    @Test
    fun invalidTempDirBlocksStartAndStoresFailure() = runTest {
        val dao = FakeFrpDao().apply { upsertProfile(profile.toEntity()) }
        val runtime = FakeRuntime(tempDirResult = FrpResult.fromRaw("INVALID_TEMP_DIR: bad cache"))
        val repository = repository(dao, runtime)

        val result = repository.start(profile)

        assertTrue(result.isInvalidTempDir)
        assertEquals(0, runtime.startCalls)
        assertEquals(FrpInstanceStatus.Failed, dao.runtimeState(profile.id)?.state)
        assertEquals("INVALID_TEMP_DIR: bad cache", dao.runtimeState(profile.id)?.lastError)
    }

    @Test
    fun invalidTomlDoesNotOverwriteExistingRunningState() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(startResult = FrpResult.fromRaw("INVALID_TOML: missing serverAddr"))
        val repository = repository(dao, runtime)

        val result = repository.start(profile)

        assertTrue(result.isInvalidToml)
        assertEquals(FrpInstanceStatus.Running, dao.runtimeState(profile.id)?.state)
        assertNull(dao.runtimeState(profile.id)?.lastError)
    }

    @Test
    fun reloadInvalidTomlKeepsExistingRunningState() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(reloadResult = FrpResult.fromRaw("INVALID_TOML: bad"))
        val repository = repository(dao, runtime)

        val result = repository.reload(profile)

        assertTrue(result.isInvalidToml)
        assertEquals(FrpInstanceStatus.Running, dao.runtimeState(profile.id)?.state)
        assertNull(dao.runtimeState(profile.id)?.lastError)
    }

    @Test
    fun reloadFailedStoresFailedState() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(reloadResult = FrpResult.fromRaw("RELOAD_FAILED: restart failed"))
        val repository = repository(dao, runtime)

        val result = repository.reload(profile)

        assertEquals("RELOAD_FAILED", result.code)
        assertEquals(FrpInstanceStatus.Failed, dao.runtimeState(profile.id)?.state)
        assertEquals("RELOAD_FAILED: restart failed", dao.runtimeState(profile.id)?.lastError)
    }

    @Test
    fun deleteRunningProfileStopsBeforeDelete() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(stopResult = FrpResult(code = null, message = ""))
        val repository = repository(dao, runtime)

        repository.deleteProfile(profile.id)

        assertEquals(1, runtime.stopCalls)
        assertNull(dao.getProfile(profile.id))
        assertNull(dao.runtimeState(profile.id))
    }

    @Test
    fun deleteRunningProfileIsAbortedWhenStopFails() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(
            stopResult = FrpResult.fromRaw("STOP_FAILED: socket close failed"),
            listInstancesResult = listOf(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null)),
        )
        val repository = repository(dao, runtime)

        repository.deleteProfile(profile.id)

        assertEquals(1, runtime.stopCalls)
        assertEquals(profile.id, dao.getProfile(profile.id)?.id)
        assertEquals(FrpInstanceStatus.Running, dao.runtimeState(profile.id)?.state)
    }

    @Test
    fun stopTimedOutSyncsNativeRunningState() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(
            stopResult = FrpResult.fromRaw("STOP_FAILED: stop client instance \"client-a\" timed out"),
            listInstancesResult = listOf(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null)),
        )
        val repository = repository(dao, runtime)

        val result = repository.stop(profile)

        assertEquals("STOP_FAILED", result.code)
        assertEquals(1, runtime.stopCalls)
        assertEquals(1, runtime.listInstancesCalls)
        assertEquals(FrpInstanceStatus.Running, dao.runtimeState(profile.id)?.state)
        assertNull(dao.runtimeState(profile.id)?.lastError)
    }

    @Test
    fun stopFailureMarksStoppedWhenNativeInstanceDisappeared() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(
            stopResult = FrpResult.fromRaw("STOP_FAILED: stop client instance \"client-a\" timed out"),
            listInstancesResult = emptyList(),
        )
        val repository = repository(dao, runtime)

        repository.stop(profile)

        assertEquals(FrpInstanceStatus.Stopped, dao.runtimeState(profile.id)?.state)
        assertNull(dao.runtimeState(profile.id)?.lastError)
    }

    @Test
    fun stopTimedOutSyncsNativeStoppingState() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(
            stopResult = FrpResult.fromRaw("STOP_FAILED: stop client instance \"client-a\" timed out"),
            listInstancesResult = listOf(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Stopping, null)),
        )
        val repository = repository(dao, runtime)

        repository.stop(profile)

        assertEquals(1, runtime.listInstancesCalls)
        assertEquals(FrpInstanceStatus.Stopping, dao.runtimeState(profile.id)?.state)
        assertNull(dao.runtimeState(profile.id)?.lastError)
    }

    @Test
    fun stopAllFailureSyncsNativeStates() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(
            stopAllResult = FrpResult.fromRaw("STOP_FAILED: stop all timed out"),
            listInstancesResult = listOf(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null)),
        )
        val repository = repository(dao, runtime)

        val result = repository.stopAll()

        assertEquals("STOP_FAILED", result.code)
        assertEquals(1, runtime.stopAllCalls)
        assertEquals(1, runtime.listInstancesCalls)
        assertEquals(FrpInstanceStatus.Running, dao.runtimeState(profile.id)?.state)
    }

    @Test
    fun saveAndRestartInactiveProfileSavesWithoutStartingNativeRuntime() = runTest {
        val dao = FakeFrpDao().apply { upsertProfile(profile.toEntity()) }
        val runtime = FakeRuntime()
        val repository = repository(dao, runtime)
        val updated = profile.copy(name = "updated")

        val result = repository.saveAndRestart(updated)

        assertTrue(result.isSuccess)
        assertEquals("updated", dao.getProfile(profile.id)?.name)
        assertEquals(0, runtime.startCalls)
        assertEquals(0, runtime.reloadCalls)
    }

    @Test
    fun saveAndRestartRunningProfilePersistsOnlyAfterSuccessfulReload() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(reloadResult = FrpResult(code = null, message = ""))
        val repository = repository(dao, runtime)
        val updated = profile.copy(name = "updated")

        val result = repository.saveAndRestart(updated)

        assertTrue(result.isSuccess)
        assertEquals(1, runtime.reloadCalls)
        assertEquals("updated", dao.getProfile(profile.id)?.name)
    }

    @Test
    fun saveAndRestartRunningProfileKeepsOldConfigWhenReloadFails() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(reloadResult = FrpResult.fromRaw("RELOAD_FAILED: restart failed"))
        val repository = repository(dao, runtime)
        val updated = profile.copy(name = "updated")

        val result = repository.saveAndRestart(updated)

        assertEquals("RELOAD_FAILED", result.code)
        assertEquals(1, runtime.reloadCalls)
        assertEquals(profile.name, dao.getProfile(profile.id)?.name)
    }

    @Test
    fun saveAndRestartInvalidTomlDoesNotPersistOrReload() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(validateResult = FrpResult.fromRaw("INVALID_TOML: bad"))
        val repository = repository(dao, runtime)
        val updated = profile.copy(name = "updated")

        val result = repository.saveAndRestart(updated)

        assertTrue(result.isInvalidToml)
        assertEquals(0, runtime.reloadCalls)
        assertEquals(profile.name, dao.getProfile(profile.id)?.name)
        assertEquals(FrpInstanceStatus.Running, dao.runtimeState(profile.id)?.state)
    }


    @Test
    fun syncRuntimeStatesMarksMissingNativeInstancesStopped() = runTest {
        val dao = FakeFrpDao().apply {
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(listInstancesResult = emptyList())
        val repository = repository(dao, runtime)

        repository.syncRuntimeStates()

        assertEquals(FrpInstanceStatus.Stopped, dao.runtimeState(profile.id)?.state)
        assertNull(dao.runtimeState(profile.id)?.lastError)
    }

    @Test
    fun syncRuntimeStatesKeepsExistingStateWhenNativeQueryFails() = runTest {
        val dao = FakeFrpDao().apply {
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(
            listInstancesQueryResult = FrpRuntimeQueryResult.Failure("FRPLIB_CALL_FAILED: unavailable"),
        )
        val repository = repository(dao, runtime)

        repository.syncRuntimeStates()

        assertEquals(FrpInstanceStatus.Running, dao.runtimeState(profile.id)?.state)
    }

    @Test
    fun recoverProfileDoesNotStartStoppedProfile() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Stopped, null).toEntity())
        }
        val runtime = FakeRuntime()
        val repository = repository(dao, runtime)

        val result = repository.recoverProfile(profile.id)

        assertNull(result)
        assertEquals(0, runtime.startCalls)
    }

    @Test
    fun recoverProfileStartsRunningProfile() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime()
        val repository = repository(dao, runtime)

        val result = repository.recoverProfile(profile.id)

        assertTrue(result?.isSuccess == true)
        assertEquals(1, runtime.startCalls)
    }

    @Test
    fun networkRecoveryDoesNotRestartStoppingProfiles() = runTest {
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Stopping, null).toEntity())
        }
        val repository = repository(dao, FakeRuntime())

        val profiles = repository.getNetworkRecoverableProfiles()

        assertTrue(profiles.isEmpty())
    }

    @Test
    fun clearLogsRemovesPersistedLogs() = runTest {
        val dao = FakeFrpDao().apply {
            insertLogs(listOf(FrpLogEntity(instanceId = profile.id, type = "client", level = "error", message = "failed", time = 1)))
        }
        val repository = repository(dao, FakeRuntime())

        repository.clearLogs()

        assertEquals(0, dao.logCount())
    }

    @Test
    fun clearLogsDropsPendingBufferedLogs() = runTest {
        val dao = FakeFrpDao()
        val runtime = FakeRuntime()
        val repository = repository(dao, runtime, logFlushDelayMs = 1_000)

        repository.initialize()
        runtime.logSink?.onLog(FrpLog(profile.id, "client", "error", "failed", time = 1))
        repository.clearLogs()
        advanceTimeBy(1_000)

        assertEquals(0, dao.logCount())
    }

    @Test
    fun logChannelKeepsNewestBufferedLogsWhenCapacityIsExceeded() = runTest {
        val dao = FakeFrpDao(logInsertDelayMs = 1_000)
        val runtime = FakeRuntime()
        val repository = repository(
            dao = dao,
            runtime = runtime,
            logBatchSize = 1,
            logBufferLimit = 3,
        )

        repository.initialize()
        repeat(5) { index ->
            runtime.logSink?.onLog(FrpLog(profile.id, "client", "info", "log-$index", time = index.toLong()))
        }
        advanceTimeBy(4_001)

        assertEquals(listOf("log-0", "log-2", "log-3", "log-4"), dao.logMessages())
    }

    @Test
    fun persistedLogsAreTrimmedAfterWriteThreshold() = runTest {
        val dao = FakeFrpDao()
        val runtime = FakeRuntime()
        val repository = repository(
            dao = dao,
            runtime = runtime,
            logTrimInterval = 3,
            logMaxCount = 2,
        )

        repository.initialize()
        repeat(3) { index ->
            runtime.logSink?.onLog(FrpLog(profile.id, "client", "info", "log-$index", time = index.toLong()))
        }

        assertEquals(listOf("log-1", "log-2"), dao.logMessages())
    }

    @Test
    fun tlsFilesAreImportedListedAndDeletedWithProfile() = runTest {
        val filesDir = File("build/test-files/tls-profile").apply { deleteRecursively() }
        val dao = FakeFrpDao().apply { upsertProfile(profile.toEntity()) }
        val repository = repository(dao, FakeRuntime(), appFilesDir = filesDir)

        val imported = repository.importTlsFile(
            profile.id,
            TlsFileRole.TrustedCa,
            "certificate".byteInputStream(),
        )

        assertEquals("ca.pem", imported.name)
        assertEquals(listOf(imported), repository.getTlsFiles(profile.id))
        repository.deleteProfile(profile.id)
        assertTrue(repository.getTlsFiles(profile.id).isEmpty())
    }

    @Test
    fun missingManagedTlsFileBlocksStart() = runTest {
        val filesDir = File("build/test-files/tls-missing").apply { deleteRecursively() }
        val missingPath = File(filesDir, "certificates/${profile.id}/ca.pem").absolutePath
        val dao = FakeFrpDao().apply { upsertProfile(profile.toEntity()) }
        val runtime = FakeRuntime(tlsFilePathsResult = listOf(missingPath))
        val repository = repository(dao, runtime, appFilesDir = filesDir)

        val result = repository.start(profile)

        assertEquals("TLS_FILE_MISSING", result.code)
        assertEquals(0, runtime.startCalls)
        assertEquals(FrpInstanceStatus.Stopped, dao.runtimeState(profile.id)?.state)
        assertEquals("TLS_FILE_MISSING: $missingPath", dao.runtimeState(profile.id)?.lastError)
    }

    @Test
    fun missingManagedTlsFileDoesNotOverwriteRunningStateOnReload() = runTest {
        val filesDir = File("build/test-files/tls-reload-missing").apply { deleteRecursively() }
        val missingPath = File(filesDir, "certificates/${profile.id}/ca.pem").absolutePath
        val dao = FakeFrpDao().apply {
            upsertProfile(profile.toEntity())
            upsertRuntimeState(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null).toEntity())
        }
        val runtime = FakeRuntime(tlsFilePathsResult = listOf(missingPath))
        val repository = repository(dao, runtime, appFilesDir = filesDir)

        val result = repository.reload(profile)

        assertEquals("TLS_FILE_MISSING", result.code)
        assertEquals(0, runtime.reloadCalls)
        assertEquals(FrpInstanceStatus.Running, dao.runtimeState(profile.id)?.state)
        assertNull(dao.runtimeState(profile.id)?.lastError)
    }

    private fun TestScope.repository(
        dao: FakeFrpDao,
        runtime: FakeRuntime,
        appFilesDir: File = File("build/test-files"),
        logFlushDelayMs: Long = 0,
        logBatchSize: Int = 100,
        logBufferLimit: Int = 1_000,
        logTrimInterval: Int = 1_000,
        logMaxCount: Int = 100_000,
    ): FrpRepository =
        FrpRepository(
            dao = dao,
            settingsStore = FakeSettings(),
            appCacheDir = File("build/test-cache"),
            appFilesDir = appFilesDir,
            runtimeManager = runtime,
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            logFlushDelayMs = logFlushDelayMs,
            logBatchSize = logBatchSize,
            logBufferLimit = logBufferLimit,
            logTrimInterval = logTrimInterval,
            logMaxCount = logMaxCount,
        )
}

private class FakeRuntime(
    var tempDirResult: FrpResult = FrpResult(code = null, message = ""),
    var validateResult: FrpResult = FrpResult(code = null, message = ""),
    var startResult: FrpResult = FrpResult(code = null, message = ""),
    var reloadResult: FrpResult = FrpResult(code = null, message = ""),
    var stopResult: FrpResult = FrpResult(code = null, message = ""),
    var stopAllResult: FrpResult = FrpResult(code = null, message = ""),
    var listInstancesResult: List<FrpRuntimeState> = emptyList(),
    var listInstancesQueryResult: FrpRuntimeQueryResult? = null,
    var tlsFilePathsResult: List<String> = emptyList(),
) : FrpRuntimeGateway {
    var startCalls = 0
    var reloadCalls = 0
    var stopCalls = 0
    var stopAllCalls = 0
    var listInstancesCalls = 0
    var logSink: FrpLogSink? = null

    override val isNativeAvailable: Boolean = true

    override fun configureTempDir(directory: File): FrpResult = tempDirResult
    override fun validateToml(toml: String): FrpResult = validateResult
    override fun tlsFilePaths(toml: String): List<String> = tlsFilePathsResult
    override suspend fun registerLogCallbackOnce(sink: FrpLogSink) {
        logSink = sink
    }
    override suspend fun start(profile: FrpProfile): FrpResult {
        startCalls += 1
        return startResult
    }
    override suspend fun reload(profile: FrpProfile): FrpResult {
        reloadCalls += 1
        return reloadResult
    }
    override suspend fun stop(id: String, type: FrpType): FrpResult {
        stopCalls += 1
        return stopResult
    }
    override suspend fun stopAll(): FrpResult {
        stopAllCalls += 1
        return stopAllResult
    }
    override suspend fun listInstances(): FrpRuntimeQueryResult {
        listInstancesCalls += 1
        return listInstancesQueryResult ?: FrpRuntimeQueryResult.Success(listInstancesResult)
    }
}

private class FakeSettings : SettingsGateway {
    private val mutableSettings = MutableStateFlow(FrpSettings())
    override val settings: Flow<FrpSettings> = mutableSettings

    override suspend fun setBootStartEnabled(enabled: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(bootStartEnabled = enabled)
    }
    override suspend fun setNetworkReconnectEnabled(enabled: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(networkReconnectEnabled = enabled)
    }
    override suspend fun setAutoRetryEnabled(enabled: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(autoRetryEnabled = enabled)
    }
    override suspend fun setLogRetentionDays(days: Int) {
        mutableSettings.value = mutableSettings.value.copy(logRetentionDays = days)
    }
    override suspend fun setThemeMode(mode: ThemeMode) {
        mutableSettings.value = mutableSettings.value.copy(themeMode = mode)
    }
    override suspend fun setLanguageMode(mode: LanguageMode) {
        mutableSettings.value = mutableSettings.value.copy(languageMode = mode)
    }
    override suspend fun setPendingStart(pending: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(pendingStart = pending)
    }
}

private class FakeFrpDao(
    private val logInsertDelayMs: Long = 0,
) : FrpDao {
    private val profiles = linkedMapOf<String, FrpProfileEntity>()
    private val states = linkedMapOf<String, FrpRuntimeStateEntity>()
    private val logs = mutableListOf<FrpLogEntity>()
    private var nextLogUid = 1L

    override fun observeProfiles(): Flow<List<FrpProfileEntity>> = flowOf(profiles.values.toList())
    override suspend fun getProfile(id: String): FrpProfileEntity? = profiles[id]
    override suspend fun getAutoStartProfiles(): List<FrpProfileEntity> = profiles.values.filter { it.autoStart }
    override suspend fun upsertProfile(profile: FrpProfileEntity) {
        profiles[profile.id] = profile
    }
    override suspend fun deleteProfile(id: String) {
        profiles.remove(id)
    }

    override fun observeRuntimeStates(): Flow<List<FrpRuntimeStateEntity>> = flowOf(states.values.toList())
    override suspend fun getRuntimeStates(): List<FrpRuntimeStateEntity> = states.values.toList()
    override suspend fun upsertRuntimeState(state: FrpRuntimeStateEntity) {
        states[state.id] = state
    }
    override suspend fun deleteRuntimeState(id: String) {
        states.remove(id)
    }
    override fun observeLogs(
        instanceId: String?,
        type: String?,
        level: String?,
        keyword: String?,
        limit: Int,
        offset: Int,
    ): Flow<List<FrpLogEntity>> = flowOf(logs.drop(offset).take(limit))

    override suspend fun insertLogs(logs: List<FrpLogEntity>) {
        if (logInsertDelayMs > 0) delay(logInsertDelayMs)
        this.logs += logs.map { log ->
            if (log.uid > 0) log else log.copy(uid = nextLogUid++)
        }
    }
    override suspend fun trimLogs(maxCount: Int) {
        val retainedIds = logs.sortedByDescending { it.uid }.take(maxCount).map { it.uid }.toSet()
        logs.removeAll { it.uid !in retainedIds }
    }
    override suspend fun deleteLogsOlderThan(olderThan: Long) {
        logs.removeAll { it.time < olderThan }
    }
    override suspend fun clearLogs() {
        logs.clear()
    }

    fun runtimeState(id: String): FrpRuntimeStateEntity? = states[id]
    fun logCount(): Int = logs.size
    fun logMessages(): List<String> = logs.sortedBy { it.uid }.map { it.message }
}
