#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${root_dir}/artifacts"
build_type="release"
source_dir="${root_dir}/app/build/outputs/apk/${build_type}"

rm -rf "${output_dir}"
mkdir -p "${output_dir}/${build_type}"

if [[ ! -d "${source_dir}" ]]; then
  echo "找不到 APK 输出目录: ${source_dir}" >&2
  exit 1
fi

for suffix in universal arm64-v8a armeabi-v7a x86_64; do
  source="$(find "${source_dir}" -maxdepth 1 -name "*${suffix}*${build_type}*.apk" | head -n 1)"
  if [[ -z "${source}" ]]; then
    echo "找不到 APK: ${suffix}" >&2
    exit 1
  fi

  cp "${source}" "${output_dir}/${build_type}/frp-android-${suffix}-${build_type}.apk"
done

echo "APK 已收集到 ${output_dir}"
