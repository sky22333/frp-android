#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
frplib_dir="${root_dir}/frplib"
out_aar="${root_dir}/app/libs/frplib-universal.aar"
android_api="${ANDROID_API:-24}"
javapkg="${FRPLIB_JAVAPKG:-io.github.sky22333}"
module_path="github.com/sky22333/frplib"

if [[ ! -d "${frplib_dir}" ]]; then
  echo "缺少 frplib 源码目录: ${frplib_dir}" >&2
  exit 1
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "请设置 ANDROID_NDK_HOME" >&2
  exit 1
fi

if ! command -v gomobile >/dev/null 2>&1; then
  echo "未找到 gomobile，请先: go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init" >&2
  exit 1
fi

cd "${frplib_dir}"

if [[ -n "${FRP_VERSION:-}" ]]; then
  echo "更新上游 frp 到 ${FRP_VERSION}"
  go get "github.com/fatedier/frp@${FRP_VERSION}"
  go mod tidy
fi

echo "运行 frplib 单元测试"
go test ./...

mkdir -p "$(dirname "${out_aar}")"
rm -f "${out_aar}"

echo "gomobile bind -> ${out_aar}"
gomobile bind \
  -androidapi="${android_api}" \
  -o "${out_aar}" \
  -ldflags="-s -w -buildid= -checklinkname=0" \
  -trimpath \
  -javapkg="${javapkg}" \
  "${module_path}"

echo "frplib AAR 构建完成: ${out_aar}"
