package com.sky22333.frpandroid.core.frp

import java.lang.reflect.Proxy

class FrplibBridge {
    private val frplibClass: Class<*>? = runCatching {
        Class.forName("io.github.sky22333.frplib.Frplib")
    }.getOrNull()

    val isAvailable: Boolean
        get() = frplibClass != null

    fun startClient(id: String, toml: String): String = invokeString("StartClientWithID", id, toml)
    fun startServer(id: String, toml: String): String = invokeString("StartServerWithID", id, toml)
    fun reloadClient(id: String, toml: String): String = invokeString("ReloadClientWithID", id, toml)
    fun reloadServer(id: String, toml: String): String = invokeString("ReloadServerWithID", id, toml)
    fun stopClient(id: String): String = invokeString("StopClientWithID", id)
    fun stopServer(id: String): String = invokeString("StopServerWithID", id)
    fun stopAll(): String = invokeString("StopAll")
    fun listInstances(): String = invokeString("ListInstances")

    fun setLogCallback(sink: FrpLogSink) {
        val target = frplibClass ?: return
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
            target.getMethod("SetLogCallback", callbackClass).invoke(null, callback)
        }
    }

    private fun invokeString(name: String, vararg args: String): String {
        val target = frplibClass ?: return "FRPLIB_MISSING: frplib AAR is not available"
        return runCatching {
            val types = Array(args.size) { String::class.java }
            target.getMethod(name, *types).invoke(null, *args) as? String ?: ""
        }.getOrElse { error ->
            "FRPLIB_CALL_FAILED: ${error.message.orEmpty()}"
        }
    }
}
