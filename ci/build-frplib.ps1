# 本地 Windows 构建 frplib AAR
# 依赖: Go、官方 gomobile（golang.org/x/mobile）、Android NDK
# 可选升级上游:
#   $env:FRP_VERSION = "v0.70.1"; .\ci\build-frplib.ps1

$ErrorActionPreference = "Stop"
# 使用国内 Go 模块代理
$env:GOPROXY = "https://goproxy.cn,direct"
$env:GOSUMDB = "sum.golang.google.cn"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$frplib = Join-Path $root "frplib"
$outAar = Join-Path $root "app\libs\frplib-universal.aar"

if (-not $env:ANDROID_NDK_HOME) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $ndk = Get-ChildItem (Join-Path $sdk "ndk") -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $ndk) { throw "请设置 ANDROID_NDK_HOME" }
    $env:ANDROID_NDK_HOME = $ndk.FullName
}
if (-not (Get-Command gomobile -ErrorAction SilentlyContinue)) {
    throw "未找到 gomobile。请执行: go install golang.org/x/mobile/cmd/gomobile@latest"
}

Push-Location $frplib
try {
    if ($env:FRP_VERSION) {
        go get "github.com/fatedier/frp@$($env:FRP_VERSION)"
        go mod tidy
    }
    go test ./...
    New-Item -ItemType Directory -Force -Path (Split-Path $outAar) | Out-Null
    Remove-Item $outAar, ($outAar -replace '\.aar$', '-sources.jar') -Force -ErrorAction SilentlyContinue
    Write-Host "gomobile bind -> $outAar (NDK=$env:ANDROID_NDK_HOME)"
    gomobile bind -androidapi=24 -o $outAar -ldflags="-s -w -buildid= -checklinkname=0" -trimpath -javapkg=io.github.sky22333 github.com/sky22333/frplib
    Write-Host "完成: $outAar"
} finally {
    Pop-Location
}
