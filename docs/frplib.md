# frplib 使用规范

`frplib` 是 frp 的 Android AAR 绑定库。App 只向它传入 TOML 字符串，不传临时配置文件路径。

## gomobile 命名规则

`frplib` 的 Go 源码导出函数使用大写开头，例如 `SetTempDir`、`StartClientWithID`。

gomobile 生成到 Java/Kotlin 后会转换成小驼峰方法名：

```text
Go:      SetTempDir          -> Kotlin/Java: setTempDir
Go:      StartClient         -> Kotlin/Java: startClient
Go:      StartClientWithID   -> Kotlin/Java: startClientWithID
Go:      ListInstances       -> Kotlin/Java: listInstances
Go:      SetLogCallback      -> Kotlin/Java: setLogCallback
```

本项目所有 Android 侧代码和 CI AAR 验收都必须使用小驼峰方法名。

## 基础用法

App 启动后先设置私有临时目录：

```kotlin
val tempErr = Frplib.setTempDir(context.cacheDir.absolutePath)
if (tempErr.isNotEmpty()) {
    // 处理临时目录错误，不要继续启动 frp
}
```

启动客户端：

```kotlin
val err = Frplib.startClient(frpcToml)
if (err.isNotEmpty()) {
    // 处理错误
}

Frplib.reloadClient(newFrpcToml)
Frplib.stopClient()
```

启动服务端：

```kotlin
Frplib.startServer(frpsToml)
Frplib.reloadServer(newFrpsToml)
Frplib.stopServer()
```

返回值：

```text
""           成功
"CODE: ..." 错误
```

常见错误：

```text
ALREADY_RUNNING: ...
INVALID_TEMP_DIR: ...
INVALID_TOML: ...
START_FAILED: ...
STOP_FAILED: ...
RELOAD_FAILED: ...
```

## 多实例

```kotlin
Frplib.startClientWithID("client-a", frpcTomlA)
Frplib.startClientWithID("client-b", frpcTomlB)
Frplib.stopClientWithID("client-a")

Frplib.startServerWithID("server-a", frpsTomlA)
Frplib.reloadServerWithID("server-a", newFrpsTomlA)
Frplib.stopServerWithID("server-a")
```

辅助方法：

```kotlin
Frplib.isClientRunning()
Frplib.isClientRunningWithID("client-a")
Frplib.isServerRunning()
Frplib.isServerRunningWithID("server-a")

Frplib.stopAll()
Frplib.listInstances()
```

`listInstances()` 每行返回一个实例：

```text
type:id:state
type:id:state:lastError
```

## 日志

```kotlin
Frplib.setLogCallback(object : FrpLogCallback {
    override fun onLog(instanceID: String, type: String, level: String, message: String) {
        // type: client / server / frp
        // level: trace / debug / info / warn / error
    }
})
```

生命周期日志包含实例 ID。frp 内部日志的 `type` 为 `frp`，实例 ID 为空。

日志回调不保证在主线程。需要更新 UI 时，请切回 Android 主线程。

## 配置文件路径

传入的是 TOML 字符串。TOML 中如果引用证书、密钥、include 等文件，建议使用 App 私有目录下的绝对路径。

## Reload

`reloadClient` 和 `reloadServer` 使用安全重启：

```text
验证新 TOML -> 停止旧实例 -> 启动新实例
```

如果新 TOML 验证失败，旧实例会继续运行。
