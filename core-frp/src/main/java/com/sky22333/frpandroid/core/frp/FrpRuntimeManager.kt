package com.sky22333.frpandroid.core.frp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

interface FrpRuntimeGateway {
    fun configureTempDir(directory: File): FrpResult
    fun validateToml(toml: String): FrpResult
    fun tlsFilePaths(toml: String): List<String>
    suspend fun registerLogCallbackOnce(sink: FrpLogSink)
    suspend fun start(profile: FrpProfile): FrpResult
    suspend fun reload(profile: FrpProfile): FrpResult
    suspend fun stop(id: String, type: FrpType): FrpResult
    suspend fun stopAll(): FrpResult
    suspend fun listInstances(): FrpRuntimeQueryResult
    suspend fun version(): String
}

class FrpRuntimeManager(
    private val bridge: FrplibBridge = FrplibBridge(),
    private val tomlValidator: TomlValidator = TomlValidator(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FrpRuntimeGateway {
    private val logMutex = Mutex()
    private var logCallbackRegistered = false
    private var tempDirResult = FrpResult(code = null, message = "")

    override fun configureTempDir(directory: File): FrpResult {
        tempDirResult = FrpResult.fromRaw(bridge.configureTempDir(directory))
        return tempDirResult
    }

    override fun validateToml(toml: String): FrpResult = tomlValidator.validate(toml)
    override fun tlsFilePaths(toml: String): List<String> = tomlValidator.tlsFilePaths(toml)

    override suspend fun registerLogCallbackOnce(sink: FrpLogSink) {
        logMutex.withLock {
            if (!logCallbackRegistered) {
                bridge.setLogCallback(sink)
                logCallbackRegistered = true
            }
        }
    }

    override suspend fun start(profile: FrpProfile): FrpResult = withContext(ioDispatcher) {
        if (!tempDirResult.isSuccess) return@withContext tempDirResult
        val validation = tomlValidator.validate(profile.toml)
        if (!validation.isSuccess) return@withContext validation
        val raw = when (profile.type) {
            FrpType.Client -> bridge.startClient(profile.id, profile.toml)
            FrpType.Server -> bridge.startServer(profile.id, profile.toml)
        }
        FrpResult.fromRaw(raw)
    }

    override suspend fun reload(profile: FrpProfile): FrpResult = withContext(ioDispatcher) {
        if (!tempDirResult.isSuccess) return@withContext tempDirResult
        val validation = tomlValidator.validate(profile.toml)
        if (!validation.isSuccess) return@withContext validation
        val raw = when (profile.type) {
            FrpType.Client -> bridge.reloadClient(profile.id, profile.toml)
            FrpType.Server -> bridge.reloadServer(profile.id, profile.toml)
        }
        FrpResult.fromRaw(raw)
    }

    override suspend fun stop(id: String, type: FrpType): FrpResult = withContext(ioDispatcher) {
        val raw = when (type) {
            FrpType.Client -> bridge.stopClient(id)
            FrpType.Server -> bridge.stopServer(id)
        }
        val result = FrpResult.fromRaw(raw)
        if (result.isAlreadyStopped) FrpResult(code = null, message = "") else result
    }

    override suspend fun stopAll(): FrpResult = withContext(ioDispatcher) {
        FrpResult.fromRaw(bridge.stopAll())
    }

    override suspend fun listInstances(): FrpRuntimeQueryResult = withContext(ioDispatcher) {
        when (val result = bridge.listInstances()) {
            is BridgeCallResult.Failure -> FrpRuntimeQueryResult.Failure(result.message)
            is BridgeCallResult.Success -> parseInstances(result.value)
        }
    }

    override suspend fun version(): String = withContext(ioDispatcher) {
        bridge.version()
    }

    internal fun parseInstances(raw: String): FrpRuntimeQueryResult {
        if (raw.isBlank()) return FrpRuntimeQueryResult.Success(emptyList())
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        val states = lines.mapNotNull { line -> parseInstanceLine(line) }
        return if (states.size == lines.size) {
            FrpRuntimeQueryResult.Success(states)
        } else {
            FrpRuntimeQueryResult.Failure("FRPLIB_INVALID_RESPONSE: listInstances returned unparseable data")
        }
    }

    internal fun parseInstanceLine(line: String): FrpRuntimeState? {
        val parts = line.split(":", limit = 4)
        if (parts.size < 3) return null
        val type = when (parts[0].lowercase()) {
            "client", "frpc" -> FrpType.Client
            "server", "frps" -> FrpType.Server
            else -> return null
        }
        val status = when (parts[2].lowercase()) {
            "running" -> FrpInstanceStatus.Running
            "stopping" -> FrpInstanceStatus.Stopping
            "failed" -> FrpInstanceStatus.Failed
            else -> FrpInstanceStatus.Stopped
        }
        return FrpRuntimeState(
            id = parts[1],
            type = type,
            state = status,
            lastError = parts.getOrNull(3)?.ifBlank { null },
        )
    }
}
