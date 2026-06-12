package com.sky22333.frpandroid.core.frp

import java.io.File
import java.lang.reflect.Proxy

class FrplibBridge {
    private var cachedFrplibClass: Class<*>? = null

    fun configureTempDir(directory: File): String =
        if ((directory.exists() || directory.mkdirs()) && directory.isDirectory) {
            invokeString("setTempDir", directory.absolutePath)
        } else {
            "INVALID_TEMP_DIR: failed to create ${directory.absolutePath}"
        }

    fun startClient(id: String, toml: String): String = invokeString("startClientWithID", id, toml)
    fun startServer(id: String, toml: String): String = invokeString("startServerWithID", id, toml)
    fun reloadClient(id: String, toml: String): String = invokeString("reloadClientWithID", id, toml)
    fun reloadServer(id: String, toml: String): String = invokeString("reloadServerWithID", id, toml)
    fun stopClient(id: String): String = invokeString("stopClientWithID", id)
    fun stopServer(id: String): String = invokeString("stopServerWithID", id)
    fun stopAll(): String = invokeString("stopAll")
    fun listInstances(): BridgeCallResult = invoke("listInstances")
    fun version(): String = invokeString("version")

    fun setLogCallback(sink: FrpLogSink) {
        val target = frplibClass() ?: return
        val callbackClass = runCatching {
            Class.forName("io.github.sky22333.frplib.FrpLogCallback")
        }.getOrNull() ?: return
        val callback = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
        ) { _, method, args ->
            if (method.name == "onLog" && args != null && args.size >= 4) {
                sink.onLog(
                    FrpLog(
                        instanceId = args[0]?.toString().orEmpty(),
                        type = args[1]?.toString().orEmpty(),
                        level = args[2]?.toString().orEmpty(),
                        message = args[3]?.toString().orEmpty(),
                        time = System.currentTimeMillis(),
                    ),
                )
            }
            null
        }
        runCatching {
            target.getMethod("setLogCallback", callbackClass).invoke(null, callback)
        }
    }

    private fun invokeString(name: String, vararg args: String): String {
        return when (val result = invoke(name, *args)) {
            is BridgeCallResult.Success -> result.value
            is BridgeCallResult.Failure -> result.message
        }
    }

    private fun invoke(name: String, vararg args: String): BridgeCallResult {
        val target = frplibClass() ?: return BridgeCallResult.Failure("FRPLIB_MISSING: frplib AAR is not available")
        return runCatching {
            val types = Array(args.size) { String::class.java }
            BridgeCallResult.Success(target.getMethod(name, *types).invoke(null, *args) as? String ?: "")
        }.getOrElse { error ->
            BridgeCallResult.Failure("FRPLIB_CALL_FAILED: ${error.message.orEmpty()}")
        }
    }

    private fun frplibClass(): Class<*>? =
        cachedFrplibClass ?: runCatching {
            Class.forName("io.github.sky22333.frplib.Frplib")
        }.getOrNull()?.also { cachedFrplibClass = it }

}

sealed interface BridgeCallResult {
    data class Success(val value: String) : BridgeCallResult
    data class Failure(val message: String) : BridgeCallResult
}
