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
| 状态机 | `ALREADY_RUNNING` 视为 Running；`INVALID_TOML` 不覆盖旧 Running；`RELOAD_FAILED` 写入 Failed；对齐 frplib `stopping` 状态 | 已完成 |
| Runtime 操作串行化 | Repository 对 start、reload、stop、stopAll 串行执行，避免 stop 尚未完成时重复 start/stop 导致状态错乱 | 已完成 |
| 删除配置 | 删除运行中配置前先停止实例，停止失败则不删除 | 已完成 |
| 保存并重启 | 未运行配置只保存并由前台服务启动；运行中配置 reload 成功后才保存新配置 | 已完成 |
| 前台服务 | frp 长期运行统一走 `FrpForegroundService`，自动重试不直接启动 native frp | 已完成 |
| 自动重试 | 抽出 `FrpRuntimePolicy`，明确哪些错误可重试，`INVALID_TOML` 和 `INVALID_TEMP_DIR` 不重试 | 已完成 |
| 开机自启 | 只处理解锁后的 `BOOT_COMPLETED`，失败时记录 `pendingStart` | 已完成 |
| 日志 | 日志回调集中注册，日志脱敏、缓冲和批量持久化；日志导出在 IO 协程执行 | 已完成 |
| 日志稳定性 | 日志模型保留 Room 自增 `uid`，日志列表使用稳定唯一 key，避免重复错误日志导致滚动崩溃 | 已完成 |
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
| 2026-06-03 | 修复停止服务超时/重启路径状态错乱：对齐 frplib `stopping` 状态，Repository 串行化 native 操作，单实例和 stopAll 在同一操作锁内回查 `listInstances()`，Dashboard restart 仅在 stop 成功后 start，前台服务把 Running/Stopping 都视为 active；补状态机测试 | `core-data`、`core-frp`、`core-runtime`、`feature-dashboard`、tests | 下一步补 UI 操作 loading/disabled 和停止失败的页面级错误反馈 |
| 2026-06-03 | 审查并修正 stop 失败相关单测预期：删除配置停止失败测试显式模拟 native 仍 Running，并新增 native `Stopping` 同步覆盖 | tests、docs | 下一步补 Dashboard restart、runtime 操作串行化和前台服务 active 判断测试 |
| 2026-06-03 | 审查并收紧后台恢复状态语义：网络恢复/待恢复列表只恢复 Running 或 Failed，不把正在 Stopping 的实例重新拉起，并补单测覆盖 | `core-data`、tests、docs | 下一步补 Dashboard restart、runtime 操作串行化和前台服务 active 判断测试 |
| 2026-06-03 | 同步 `docs/frplib.md`，补充 `listInstances()` 的 `state` 可能值，确保文档、代码和测试对 `stopping` 的描述一致 | docs | 下一步继续清理未使用代码和补 UI/Service 层测试 |
| 2026-06-03 | 同步设计文档数据模型示例，将 Runtime state 补充为 `Stopped / Stopping / Running / Failed`，清理旧状态描述残留 | docs | 下一步继续做 UI loading/disabled 和错误反馈闭环 |
| 2026-06-03 | 清理未读取的 `recentLogBuffer` 死代码，并同步日志设计文档为“内存批量写入缓冲 + 数据库查询/导出” | `core-data`、docs | 下一步继续补日志清空和筛选体验 |
| 2026-06-03 | 修复日志批量 flush 竞态：flush job 活跃期间追加的新日志会在同一 job 内继续循环落库，避免日志滞留内存等待下一次触发 | `core-data`、docs | 下一步补日志 flush 竞态单测 |
| 2026-06-03 | 修复 `BootReceiver` 异步广播处理：`goAsync()` 的 `finish()` 统一放入 `finally`，避免异常路径导致广播未结束 | `core-runtime`、docs | 下一步将网络恢复启动迁移为 WorkManager 退避队列 |
| 2026-06-03 | 修复 Dashboard 通知权限回调动作丢失：重启申请权限后继续执行 restart 而不是误执行 start；顶部状态把 Stopping 视为 active | `feature-dashboard`、docs | 下一步补 Dashboard ViewModel/权限动作测试 |
| 2026-06-03 | 修复日志页筛选 chip 的 `all` 硬编码，补充中英文资源，保持 UI 文案资源化 | `feature-logs`、resources、docs | 下一步继续清理其他硬编码展示和补日志交互 |
| 2026-06-03 | 统一 App 根背景、TopAppBar、NavigationBar、系统状态栏和系统导航栏颜色为主题背景色，移除默认容器色和 `styles.xml` 系统栏硬编码造成的突兀色块 | `app`、resources、docs | 下一步用截图/真机确认浅色、深色、AMOLED 三种主题视觉一致性 |
| 2026-06-03 | 补齐 Dashboard 启动/停止/重启/停止全部、Editor 保存/保存并重启、Profiles 删除的 busy/disabled 状态；Dashboard 服务启动入口异常时会立即清理 busy，避免重复点击只依赖 Repository 锁兜底 | `feature-dashboard`、`feature-editor`、`feature-profiles`、docs | 下一步补页面级错误反馈 |
| 2026-06-03 | 补齐页面级错误反馈：Dashboard 操作失败、Editor 未运行配置保存后启动服务失败、Profiles 删除停止失败都会在当前页面展示错误 | `feature-dashboard`、`feature-editor`、`feature-profiles`、docs | 下一步继续补更细的错误本地化标题 |
| 2026-06-03 | 优化日志排障体验：新增“仅错误”快捷筛选和“清空”日志入口；清空时取消待执行 flush、清理待写入缓冲和数据库日志，并补 pending buffer 清理单测 | `core-data`、`feature-logs`、resources、tests、docs | 下一步补清空确认和错误详情复制体验 |
| 2026-06-03 | 收口 UI 和交互体验：浅色/深色/AMOLED 主题显式统一 background 与 surface container，系统导航栏关闭额外对比遮罩；移除顶部全局日志按钮和控制台“新建隧道”重复入口；配置列表明确自启开关语义并补删除确认说明；日志页操作区可换行、清空二次确认、复制/导出 Snackbar、时间格式化；设置页改为可滚动分组并精准显示/引导电池优化状态 | `app`、`core-ui`、`feature-dashboard`、`feature-profiles`、`feature-logs`、`feature-settings`、resources、docs | 下一步用真机/CI 截图验收浅色、深色、AMOLED 背景一致性和小屏日志页换行表现 |
| 2026-06-03 | 修复日志页重复错误日志滚动崩溃风险：`FrpLog` 保留数据库 `uid`，`LazyColumn` 使用稳定唯一 key | `core-data`、`core-frp`、`feature-logs` | 下一步补日志清空、仅错误快捷筛选和错误详情复制体验 |
| 2026-06-03 | 补齐 `feature-editor` 使用 Activity Result API 所需的 `androidx.activity:activity-compose` 依赖 | `feature-editor` | 继续检查各 feature 模块依赖与实际 imports 是否一致 |
| 2026-06-03 | 修复 `feature-logs` 日志导出协程缺少 `kotlinx.coroutines.launch` 导入导致的编译错误 | `feature-logs` | 继续补 UI loading/disabled、删除失败反馈和日志页交互 |
| 2026-06-03 | 新增本文档；整理已完善内容和后续计划；明确每次修改必须同步记录 | docs | 优先补 UI loading/disabled、删除失败反馈和日志页交互 |
