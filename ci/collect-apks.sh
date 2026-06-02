#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${root_dir}/artifacts"

rm -rf "${output_dir}"
mkdir -p "${output_dir}/debug" "${output_dir}/release"

copy_one() {
  local flavor="$1"
  local build_type="$2"
  local file_suffix="$3"
  local source_dir="${root_dir}/app/build/outputs/apk/${flavor}/${build_type}"
  local source

  source="$(find "${source_dir}" -maxdepth 1 -name "*.apk" | head -n 1)"
  if [[ -z "${source}" ]]; then
    echo "找不到 APK: ${source_dir}" >&2
    exit 1
  fi

  cp "${source}" "${output_dir}/${build_type}/frp-android-${file_suffix}-${build_type}.apk"
}

copy_one "universal" "debug" "universal"
copy_one "arm64V8a" "debug" "arm64-v8a"
copy_one "armeabiV7a" "debug" "armeabi-v7a"
copy_one "x86_64" "debug" "x86_64"

copy_one "universal" "release" "universal"
copy_one "arm64V8a" "release" "arm64-v8a"
copy_one "armeabiV7a" "release" "armeabi-v7a"
copy_one "x86_64" "release" "x86_64"

echo "APK 已收集到 ${output_dir}"
