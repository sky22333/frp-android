#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${root_dir}/artifacts"

rm -rf "${output_dir}"
mkdir -p "${output_dir}/release"

copy_one() {
  local pattern="$1"
  local build_type="$2"
  local file_suffix="$3"
  local source_dir="${root_dir}/app/build/outputs/apk/${build_type}"
  local source

  if [[ ! -d "${source_dir}" ]]; then
    echo "找不到 APK 输出目录: ${source_dir}" >&2
    exit 1
  fi

  source="$(find "${source_dir}" -maxdepth 1 -name "${pattern}" | head -n 1)"
  if [[ -z "${source}" ]]; then
    echo "找不到 APK: ${source_dir}/${pattern}" >&2
    exit 1
  fi

  cp "${source}" "${output_dir}/${build_type}/frp-android-${file_suffix}-${build_type}.apk"
}

copy_one "*universal*release*.apk" "release" "universal"
copy_one "*arm64-v8a*release*.apk" "release" "arm64-v8a"
copy_one "*armeabi-v7a*release*.apk" "release" "armeabi-v7a"
copy_one "*x86_64*release*.apk" "release" "x86_64"

echo "APK 已收集到 ${output_dir}"
