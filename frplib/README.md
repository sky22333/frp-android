# frplib

frp 的 Android AAR 绑定（gomobile），与本仓库 App 同仓维护。

上游依赖见 `go.mod`（当前 `github.com/fatedier/frp`）。Java 包名：`io.github.sky22333.frplib`。

## 构建 AAR

在仓库根目录：

```bash
# Linux / macOS / Git Bash
bash ci/build-frplib.sh
```

```powershell
# Windows PowerShell（本地开发）
.\ci\build-frplib.ps1
```

产物：`app/libs/frplib-universal.aar`（已在 `.gitignore`）。

需要：Go、Android SDK/NDK、官方 `gomobile`（`golang.org/x/mobile`）。CI 中由 `android-release.yml` 自动执行。

可选锁定/升级上游 frp：

```bash
FRP_VERSION=v0.70.1 bash ci/build-frplib.sh
```

```powershell
$env:FRP_VERSION = "v0.70.1"; .\ci\build-frplib.ps1
```

## 测试

```bash
cd frplib && go test ./...
```

## API 概要

App 通过 `core-frp` 的 `FrplibBridge` 调用。直接用法见历史文档要点：

- 先 `SetTempDir`（App 私有可写目录）
- `Start*WithID` / `Stop*WithID` / `Reload*WithID`
- 返回空串成功，否则 `CODE: message`
- `Reload*` = 校验 → 停旧 → 启新（非上游热 reload）

### 鸣谢

- [fatedier/frp](https://github.com/fatedier/frp)
