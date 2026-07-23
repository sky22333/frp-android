# frplib AAR

将 gomobile 产物放在这里：

```text
app/libs/frplib-universal.aar
```

从仓库根目录构建：

```bash
bash ci/build-frplib.sh
```

```powershell
.\ci\build-frplib.ps1
```

该文件已 gitignore；CI 在打包 APK 前会自动生成。App 的 ABI splits 会在打包时从 universal AAR 中筛选对应 `.so`。
