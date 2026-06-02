# 迭代计划表

本文档用于记录当前项目已经完善的内容、后续计划和每次迭代的同步要求。

## 迭代记录要求

- 每次修改代码、CI、文档、资源或测试后，都必须同步更新本文档。
- 更新内容至少包含：已完成事项、影响范围、下一步建议。
- 如果某项计划被取消或调整，必须写明原因。
- 不允许只改代码不更新计划记录。
- 本文档作为后续验收、复盘和继续开发的入口。

## 已完善内容

| 模块 | 已完善内容 | 状态 |
| --- | --- | --- |
| frplib 集成 | 对齐 gomobile Java/Kotlin 小驼峰 API：`setTempDir`、`startClientWithID`、`reloadClientWithID`、`stopAll`、`listInstances`、`setLogCallback` | 已完成 |
| 临时目录 | App 启动 frp 前通过 `setTempDir(context.cacheDir.absolutePath)` 使用应用私有目录，避免 `/data/local/tmp/frplib` 权限问题 | 已完成 |
| Runtime 初始化 | Repository 按需初始化 frplib，启动、重载、同步状态前确保 tempDir 和日志回调已配置 | 已完成 |
| 状态机 | `ALREADY_RUNNING` 视为 Running；`INVALID_TOML` 不覆盖旧 Running；`RELOAD_FAILED` 写入 Failed | 已完成 |
| 删除配置 | 删除运行中配置前先停止实例，停止失败则不删除 | 已完成 |
| 保存并重启 | 未运行配置只保存并由前台服务启动；运行中配置 reload 成功后才保存新配置 | 已完成 |
| 前台服务 | frp 长期运行统一走 `FrpForegroundService`，自动重试不直接启动 native frp | 已完成 |
| 自动重试 | 抽出 `FrpRuntimePolicy`，明确哪些错误可重试，`INVALID_TOML` 和 `INVALID_TEMP_DIR` 不重试 | 已完成 |
| 开机自启 | 只处理解锁后的 `BOOT_COMPLETED`，失败时记录 `pendingStart` | 已完成 |
| 日志 | 日志回调集中注册，日志脱敏、缓冲和批量持久化；日志导出在 IO 协程执行 | 已完成 |
| 导入导出 | TOML 导入和日志导出使用系统文件选择器，并放到 IO 线程处理 | 已完成 |
| 设置页诊断 | 增加诊断摘要：frplib 可用性、runtime 初始化状态、tempDir 状态、运行/失败数量、pendingStart、最近错误 | 已完成 |
| CI AAR 验收 | 校验多架构 AAR、`Frplib.class`、`FrpLogCallback.class` 和 gomobile 小驼峰 API | 已完成 |
| 单元测试 | 补充 `FrpResult`、`FrpRuntimeManager`、`FrpRuntimePolicy`、`FrpRepository` 状态机测试 | 已完成 |
| 文档 | `README.md`、`docs/frplib.md`、`docs/ANDROID_APP_DESIGN.md` 已同步新版 frplib 用法和关键规则 | 持续维护 |

## 下一步重点

当前下一阶段重点是 **UI 和交互体验闭环**。

| 优先级 | 计划事项 | 目标 |
| --- | --- | --- |
| P0 | 启动、停止、保存并重启、删除增加 loading/disabled 状态 | 避免重复点击导致状态抖动或重复 start/stop/reload |
| P0 | 删除运行中配置时明确提示“会先停止实例” | 让用户知道删除行为会影响正在运行的隧道 |
| P0 | stop/reload/tempDir 失败在 UI 明确展示错误 | 避免失败被静默吞掉，提高真实可用性 |
| P1 | Dashboard、Profiles、Editor 的运行状态展示统一 | 避免不同页面状态不一致 |
| P1 | 日志页增加“仅错误”快捷筛选 | 提高排障效率 |
| P1 | 日志页增加“清空日志” | 支持快速清理历史日志 |
| P1 | 日志导出当前筛选结果 | 让导出结果符合用户当前排查上下文 |
| P1 | 设置页诊断摘要增加 frplib version、通知权限状态、当前 ABI | 提升诊断完整度 |
| P2 | pendingStart 从全局布尔值升级为待恢复 profile 列表 | 避免多个配置恢复状态不清晰 |
| P2 | 配置模板和证书/密钥文件导入管理 | 提升配置编辑体验 |

## 后续阶段计划

| 阶段 | 重点 | 说明 |
| --- | --- | --- |
| 第一阶段 | UI 和交互体验 | loading、禁用重复操作、错误反馈、删除确认、状态展示统一 |
| 第二阶段 | 日志与诊断 | 错误筛选、清空日志、导出筛选结果、诊断摘要增强 |
| 第三阶段 | 测试补齐 | ViewModel 测试、runtime 边界测试、diagnostics 测试、sync 多实例测试 |
| 第四阶段 | 后台恢复 | pendingStart 细化、恢复队列、避免重复恢复同一 profile |
| 第五阶段 | 配置能力 | TOML 模板、证书/密钥/include 文件管理、配置校验提示增强 |
| 第六阶段 | 发布维护 | 验收清单、release notes 模板、frplib 升级兼容策略 |

## 最近更新记录

| 日期 | 更新内容 | 影响范围 | 下一步 |
| --- | --- | --- | --- |
| 2026-06-03 | 补齐 `app` 直接使用 `collectAsStateWithLifecycle` 所需的 `androidx.lifecycle:lifecycle-runtime-compose` 依赖 | `app` | 继续保持模块依赖与实际 imports 显式一致 |
| 2026-06-03 | 补齐 `feature-editor` 使用 Activity Result API 所需的 `androidx.activity:activity-compose` 依赖 | `feature-editor` | 继续检查各 feature 模块依赖与实际 imports 是否一致 |
| 2026-06-03 | 修复 `feature-logs` 日志导出协程缺少 `kotlinx.coroutines.launch` 导入导致的编译错误 | `feature-logs` | 继续补 UI loading/disabled、删除失败反馈和日志页交互 |
| 2026-06-03 | 新增本文档；整理已完善内容和后续计划；明确每次修改必须同步记录 | docs | 优先补 UI loading/disabled、删除失败反馈和日志页交互 |
