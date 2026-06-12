#!/usr/bin/env bash
set -euo pipefail

repo="sky22333/frplib"
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
asset_name="frplib-universal.aar"
asset_url="https://github.com/${repo}/releases/latest/download/${asset_name}"
target_file="${root_dir}/app/libs/${asset_name}"

mkdir -p "$(dirname "${target_file}")"
echo "下载 ${asset_name}"
curl -fL -o "${target_file}" "${asset_url}"
echo "frplib universal AAR 下载完成"
