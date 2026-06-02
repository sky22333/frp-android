# frp-android

基于 `sky22333/frplib` AAR 的 Android frp 控制台 App。

## 构建方式

后续构建和发布依靠 GitHub Actions，不要求在本机编译。

工作流：

```text
.github/workflows/android-release.yml
```

触发方式：

```text
Actions -> 安卓构建与发布 -> Run workflow
```

手动输入发布 tag，默认值：

```text
v0.0.1
```

## frplib 多架构依赖

CI 会自动从 `frplib` latest release 下载以下 AAR：

```text
https://github.com/sky22333/frplib/releases/latest
```

下载后的布局：

```text
app/libs/universal/frplib-universal.aar
app/libs/arm64-v8a/frplib-arm64-v8a.aar
app/libs/armeabi-v7a/frplib-armeabi-v7a.aar
app/libs/x86_64/frplib-x86_64.aar
```

Gradle product flavors：

```text
universal
arm64V8a
armeabiV7a
x86_64
```

CI 会在构建前验收 AAR：

```text
检查四个 AAR 文件存在
检查 Frplib.class
检查 FrpLogCallback.class
检查 setTempDir、start/stop/reload、isRunning、stopAll、listInstances、setLogCallback 方法
```

## frplib 使用原则

Go 源码中的导出函数是大写开头，例如 `SetTempDir`。gomobile 生成到 Java/Kotlin 后会转成小驼峰，例如 `setTempDir`。

App 初始化仓库时会先调用：

```text
Frplib.setTempDir(context.cacheDir.absolutePath)
```

如果返回非空错误，例如 `INVALID_TEMP_DIR: ...`，应用不得继续启动或重载 frp。

运行时统一使用多实例 API：

```text
startClientWithID / startServerWithID
reloadClientWithID / reloadServerWithID
stopClientWithID / stopServerWithID
listInstances / stopAll / setLogCallback
```

## 流水线顺序

```text
拉取源码
配置 JDK
配置 Gradle
下载 frplib latest 多架构 AAR
验收 frplib AAR
先构建 Debug 多架构 APK
运行单元测试
运行 Android Lint
构建 Release 多架构 APK
整理 APK 文件名
上传 Debug/Release artifacts
发布 GitHub Release
```

## 产物命名

最终 APK 文件名不带版本号：

```text
frp-android-universal-debug.apk
frp-android-arm64-v8a-debug.apk
frp-android-armeabi-v7a-debug.apk
frp-android-x86_64-debug.apk
frp-android-universal-release.apk
frp-android-arm64-v8a-release.apk
frp-android-armeabi-v7a-release.apk
frp-android-x86_64-release.apk
```

## 可选 Release 签名

如需发布已签名 release APK，在仓库 Secrets 配置：

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

未配置这些 Secrets 时，流水线仍会构建 unsigned release APK。

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- Room + DataStore
- ForegroundService + BootReceiver + WorkManager
- Gradle Version Catalog

## Android 版本

- `minSdk`: 23
- `compileSdk`: 36
- `targetSdk`: 36
