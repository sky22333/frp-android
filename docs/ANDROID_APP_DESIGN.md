# frp Android App

基于 `frplib` AAR 的 Android App 

`frplib`仓库地址：https://github.com/sky22333/frplib

## 项目结构
```
frp-android/
├─ .github/workflows/
│  └─ android-release.yml        # GitHub Actions 发布流水线，下载 frplib、构建签名 Release、上传产物
├─ app/                          # Android 应用壳层，负责入口、导航、主题、语言切换
│  ├─ build.gradle.kts           # app 模块 Gradle 配置，多 ABI AAR 依赖、签名、Compose 依赖
│  └─ src/main/
│     ├─ AndroidManifest.xml     # 应用声明、MainActivity、图标、主题
│     ├─ java/.../frpandroid/
│     │  ├─ FrpAndroidApplication.kt  # Application，初始化周期任务
│     │  ├─ LocaleController.kt       # 应用语言 Context 包装
│     │  └─ MainActivity.kt           # Compose 主入口、底部导航、页面路由
│     └─ res/                    # 应用级资源、启动图标、中英文字符串
├─ core-frp/                     # frplib 接入层
│  └─ src/main/java/.../core/frp/
│     ├─ FrplibBridge.kt         # 反射调用 gomobile 生成的 frplib API
│     ├─ FrpRuntimeManager.kt    # start/stop/reload/listInstances 运行管理
│     ├─ FrpModels.kt            # Profile、RuntimeState、Result、Theme/Language 等模型
│     ├─ FrpLogSink.kt           # frplib 日志回调接入
│     └─ TomlValidator.kt        # TOML 配置校验
├─ core-data/                    # 数据层
│  └─ src/main/java/.../core/data/
│     ├─ AppGraph.kt             # 简单依赖入口，提供 Repository
│     ├─ AppSettings.kt          # DataStore 设置项：主题、语言、自启、日志保留等
│     ├─ Database.kt             # Room 表、DAO、数据库定义
│     └─ FrpRepository.kt        # 核心业务编排：配置、运行状态、日志、设置
├─ core-runtime/                 # 后台运行能力
│  └─ src/main/java/.../core/runtime/
│     ├─ FrpForegroundService.kt # 前台服务，承载长期运行的 frp 实例
│     ├─ BootReceiver.kt         # 开机自启接收器
│     ├─ NetworkReconnectMonitor.kt # 网络恢复后重连
│     ├─ FrpRetryWorker.kt       # 启动失败后的 WorkManager 重试
│     ├─ LogCleanupWorker.kt     # 定期清理过期日志
│     └─ FrpRuntimePolicy.kt     # 运行/重试策略判断
├─ core-ui/                      # 公共 UI
│  └─ src/main/java/.../core/ui/
│     ├─ FrpTheme.kt             # Material3 主题、浅色/深色/AMOLED
│     └─ CommonUi.kt             # 通用列表行、分组标题等组件
├─ feature-dashboard/            # 控制台页面
│  └─ DashboardScreen.kt         # 实例概览、启动、停止、重启、停止全部
├─ feature-profiles/             # 配置列表页面
│  └─ ProfilesScreen.kt          # 新建、导入、删除、自动启动开关
├─ feature-editor/               # 配置编辑页面
│  └─ EditorScreen.kt            # TOML 编辑、校验、保存、保存并重启、TLS 文件管理
├─ feature-logs/                 # 日志页面
│  └─ LogsScreen.kt              # 日志筛选、暂停滚动、复制当前筛选日志、清空日志
├─ feature-settings/             # 设置页面
│  └─ SettingsScreen.kt          # 后台、自启、电池、主题、语言、诊断、版本入口
├─ ci/                           # CI 辅助脚本
│  ├─ download-frplib.sh         # 下载 sky22333/frplib Latest 多架构 AAR
│  ├─ validate-frplib.sh         # 校验 frplib AAR 和 gomobile API
│  └─ collect-apks.sh            # 收集 Release APK 产物
├─ docs/                         # 文档和迭代记录
│  ├─ ANDROID_APP_DESIGN.md      # 产品/架构/规范
│  └─ img/                       # 预览图
├─ gradle/
│  ├─ libs.versions.toml         # 统一依赖版本目录
│  └─ wrapper/                   # Gradle Wrapper
├─ build.gradle.kts              # 根 Gradle 配置
├─ settings.gradle.kts           # 模块声明
├─ gradle.properties             # Gradle/Android 构建参数
├─ README.md                     # 项目说明
└─ LICENSE                       # 开源协议
```

## 目标

- 直接导入 `frplib` AAR，无需手写 JNI。
- 支持官方 `frpc.toml` / `frps.toml`。
- 支持单实例和多实例。
- 支持启动、停止、Reload、StopAll。
- 支持日志回调、日志查看、复制当前筛选日志。
- 支持为配置导入 frp 传输层 TLS 文件。
- 支持前台服务常驻通知。
- 支持用户开启后的开机自启。
- 支持现代 Material Design 3 UI。
- 默认浅色主题，支持深色和 AMOLED。
- 支持中文和英文，默认跟随系统语言，也可在设置页手动切换。
- 不承诺绕过系统限制，不做隐藏通知和后台静默保活。

包名规范：com.sky22333.frpandroid

应用名称：frp-android

## Android 版本策略

```text
minSdk: 23
targetSdk: 跟随最新正式稳定 Android SDK
compileSdk: 跟随最新正式稳定 Android SDK
```

## 性能要求

- 冷启动首屏目标小于 1.5 秒。
- 首页只加载实例摘要，不加载完整日志。
- frplib 调用放在 `Dispatchers.IO`。
- 日志使用固定大小内存缓存。
- 日志批量写入数据库。
- 日志页面展示当前筛选条件下最近 150 条。
- 日志用于诊断；极端日志洪峰超过内存缓存时保留最新待处理日志，不以日志推断实例运行状态。
- Compose 列表使用稳定 key。
- 不在 Composable 内创建重对象。
- 无运行实例时停止前台服务。
- 默认不使用 WakeLock；仅用户开启息屏保活且存在运行中或停止中的活动实例时，在息屏期间持有。
- 不做无退避、不可取消或针对明确永久错误的后台无限重试。
- 不在主线程读写文件、数据库或调用 frplib。
- 不在 Service 中保存 Activity / Context 引用。
- 所有 callback 注册必须有明确生命周期，避免重复注册和泄漏。

建议限制：

```text
内存日志：最近 1000 条
日志保留：7 天或最近 100000 条
失败自动重试：默认关闭
网络变化重连：使用退避策略
息屏保活：默认关闭
恢复重试间隔：10 秒、30 秒、1 分钟、2 分钟、5 分钟，之后最多每 10 分钟一次
```

## 耗电优化

frp 是长期网络连接，耗电优化必须以“减少无意义工作”为主，而不是试图隐藏后台行为。

要求：

- 只有存在运行实例时才启动前台服务。
- 全部实例停止后立即退出前台服务。
- 息屏保活默认关闭，仅在用户开启、息屏且存在运行中或停止中的活动实例时持有 WakeLock；默认网络为 Wi-Fi 时同时持有 WifiLock。
- WifiLock 为尽力而为，不能绕过系统、厂商或用户的 Wi-Fi 与后台限制。
- 不使用高频定时器轮询状态。
- 通知只在状态变化、实例数量变化、错误变化时更新。
- 日志批量写入，避免每条日志触发一次数据库写入。
- 网络变化重连失败后必须使用有上限的退避策略。
- 失败自动重试默认关闭，由用户手动开启。
- 后台日志清理使用 WorkManager，避免自建常驻线程。
- 电池优化只做用户引导，不强制、不反复弹窗。

可选设置：

```text
失败后自动重试：默认关闭
网络恢复后重连：默认开启
息屏保活：默认关闭
日志保留天数：默认 7 天
```

## 开发规范

- 所有 AAR 调用集中在 `core-frp`。
- 所有 Service/Receiver 逻辑集中在 `core-runtime`。
- UI 只消费 ViewModel state。
- 错误码和错误消息分开解析。
- 敏感字段不写入日志。
- TLS 文件通过系统文件选择器导入到配置专属应用私有目录，不保存 URI、私钥内容或额外数据库状态。
- TLS 文件管理仅提供受信任 CA、本端证书和本端私钥的固定路径；TOML 仍是唯一配置来源。
- TLS 文件目录排除 Android 云备份和设备迁移，避免私钥离开当前设备。
- 新依赖必须说明用途。
- Compose 使用 BOM。
- 依赖版本放在 Version Catalog。
- 不使用即将弃用或已废弃的 Android API。
- 每次升级 targetSdk 前检查 Android 官方行为变更。
- 对低版本 Android 使用兼容封装，不在业务代码到处写 SDK 判断。

弃用 API 规避：

- 不使用旧版 `startActivityForResult`，使用 Activity Result API。
- 不使用旧版外部存储直读写，使用 Storage Access Framework 或 MediaStore。
- 不使用已废弃通知 API，通知通过 `NotificationCompat` 和 channel 管理。
- 不使用 AsyncTask，使用 Kotlin Coroutine / WorkManager。
- 不使用 LocalBroadcastManager，使用 Flow / repository state。
- 不使用 Material 2 组件作为主 UI。
- 不使用后台 Service 伪装常驻，长期运行必须走前台服务和可见通知。

旧版本兼容：

- Android 13 以下不请求 `POST_NOTIFICATIONS`。
- Android 13+ 首次启动前台服务前引导通知权限。
- Android 14+ 声明并校验前台服务类型。
- Android 12+ 处理后台启动前台服务限制。
- Android 8+ 创建通知 channel。
- Android 6+ 提供电池优化引导。
- 开机自启失败时记录 pendingStart，用户打开 App 后提示恢复。

## 维护规范

迭代准则：

- 功能必须完整闭环、生产可用，不以实验、演示或临时代码为交付目标。
- 实现必须直击需求根因，优先复用现有项目结构和已有组件。
- 不堆砌兜底逻辑，不用大量分支掩盖不清晰的状态设计。
- 代码量保持必要且最少。
- 不新增重复抽象、重复状态、重复入口或重复校验。
- 引入新实现时，必须同步删除被替代的旧实现、旧文案、旧状态和无用代码。
- 不保留死代码、过时注释、废弃开关和无人使用的兼容层。
- 开发前先阅读当前源码、已有 docs、相关依赖实际 API 和官方文档。
- 不凭空猜测，不依据过时博客或旧文档做实现决策。
- 设计、文档、代码、测试必须描述同一个真实行为。

发布前检查：

```text
官方 frpc.toml 启动
官方 frps.toml 启动
重复 Start 返回 ALREADY_RUNNING
重复 Stop no-op
错误 TOML 返回 INVALID_TOML
Reload 错误 TOML 不停止旧实例
StopAll 能停止全部实例
日志能回调到 App
通知能停止全部
开机自启失败时提示正确
Android 低版本 smoke test
Android 14+ 前台服务类型检查
Android 17 preview smoke test
```

## 资料来源

- Material 3 Compose：<https://developer.android.com/develop/ui/compose/designsystems/material3>
- Foreground service types：<https://developer.android.com/develop/background-work/services/fgs/service-types>
- Android 14 foreground service type requirement：<https://developer.android.com/about/versions/14/changes/fgs-types-required>
- Android 15 behavior changes：<https://developer.android.com/about/versions/15/behavior-changes-15>
- Notification runtime permission：<https://developer.android.com/develop/ui/views/notifications/notification-permission>
- Android Gradle Plugin release notes：<https://developer.android.com/build/releases/gradle-plugin>
