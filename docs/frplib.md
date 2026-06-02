# frplib

`frplib` 是 frp 的 Android AAR 绑定库。它接收官方 TOML 配置字符串，用于启动、停止、重启 `frpc` / `frps`。

## 导入

把 AAR 放到 App：

```text
app/libs/frplib-universal.aar
```

在 Gradle 中引入：

```kotlin
dependencies {
    implementation(files("libs/frplib-universal.aar"))
}
```

生成的 Java 包名：

```text
io.github.sky22333.frplib
```

## 基础用法

启动客户端：

```kotlin
val err = Frplib.StartClient(frpcToml)
if (err.isNotEmpty()) {
    // 处理错误
}

Frplib.ReloadClient(newFrpcToml)
Frplib.StopClient()
```

启动服务端：

```kotlin
Frplib.StartServer(frpsToml)
Frplib.ReloadServer(newFrpsToml)
Frplib.StopServer()
```

返回值：

```text
""           成功
"CODE: ..." 错误
```

常见错误：

```text
ALREADY_RUNNING: ...
INVALID_TOML: ...
START_FAILED: ...
STOP_FAILED: ...
RELOAD_FAILED: ...
```

## 多实例

```kotlin
Frplib.StartClientWithID("client-a", frpcTomlA)
Frplib.StartClientWithID("client-b", frpcTomlB)
Frplib.StopClientWithID("client-a")

Frplib.StartServerWithID("server-a", frpsTomlA)
Frplib.ReloadServerWithID("server-a", newFrpsTomlA)
Frplib.StopServerWithID("server-a")
```

辅助方法：

```kotlin
Frplib.IsClientRunning()
Frplib.IsClientRunningWithID("client-a")
Frplib.IsServerRunning()
Frplib.IsServerRunningWithID("server-a")

Frplib.StopAll()
Frplib.ListInstances()
```

`ListInstances()` 每行返回一个实例：

```text
type:id:state
type:id:state:lastError
```

## 日志

```kotlin
Frplib.SetLogCallback(object : FrpLogCallback {
    override fun onLog(instanceID: String, type: String, level: String, message: String) {
        // type: client / server / frp
        // level: trace / debug / info / warn / error
    }
})
```

生命周期日志包含实例 ID。frp 内部日志的 `type` 为 `frp`，实例 ID 为空。

## Reload

`ReloadClient` 和 `ReloadServer` 使用安全重启：

```text
验证新 TOML -> 停止旧实例 -> 启动新实例
```

如果新 TOML 验证失败，旧实例会继续运行。
