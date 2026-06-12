#!/usr/bin/env bash
set -euo pipefail

repo="sky22333/frplib"
api_url="https://api.github.com/repos/${repo}/releases/latest"
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
libs_dir="${root_dir}/app/libs"

auth_args=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  auth_args=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

echo "获取 frplib latest release: ${api_url}"
release_json="$(curl -fsSL "${auth_args[@]}" "${api_url}")"
tag_name="$(printf '%s' "${release_json}" | jq -r '.tag_name')"
echo "frplib latest tag: ${tag_name}"

download_asset() {
  local asset_name="$1"
  local target_file="$2"
  local url

  url="$(printf '%s' "${release_json}" | jq -r --arg name "${asset_name}" '.assets[] | select(.name == $name) | .browser_download_url' | head -n 1)"
  if [[ -z "${url}" || "${url}" == "null" ]]; then
    echo "缺少 frplib release asset: ${asset_name}" >&2
    exit 1
  fi

  mkdir -p "$(dirname "${target_file}")"
  echo "下载 ${asset_name}"
  curl -fL "${auth_args[@]}" -o "${target_file}" "${url}"
}

download_asset "frplib-universal.aar" "${libs_dir}/frplib-universal.aar"

echo "frplib universal AAR 下载完成"
