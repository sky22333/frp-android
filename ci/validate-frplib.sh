#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp_dir="${root_dir}/build/frplib-validation"

validate_aar() {
  local aar="$1"
  local name

  if [[ ! -f "${aar}" ]]; then
    echo "缺少 AAR: ${aar}" >&2
    exit 1
  fi

  name="$(basename "${aar}" .aar)"
  rm -rf "${tmp_dir}/${name}"
  mkdir -p "${tmp_dir}/${name}"
  unzip -q "${aar}" -d "${tmp_dir}/${name}"

  if [[ ! -f "${tmp_dir}/${name}/classes.jar" ]]; then
    echo "AAR 缺少 classes.jar: ${aar}" >&2
    exit 1
  fi

  jar tf "${tmp_dir}/${name}/classes.jar" | grep -q "io/github/sky22333/frplib/Frplib.class" || {
    echo "AAR 缺少 Frplib.class: ${aar}" >&2
    exit 1
  }

  jar tf "${tmp_dir}/${name}/classes.jar" | grep -q "io/github/sky22333/frplib/FrpLogCallback.class" || {
    echo "AAR 缺少 FrpLogCallback.class: ${aar}" >&2
    exit 1
  }

  local frplib_api
  frplib_api="$(javap -classpath "${tmp_dir}/${name}/classes.jar" io.github.sky22333.frplib.Frplib)"
  for method in \
    startClientWithID \
    startServerWithID \
    reloadClientWithID \
    reloadServerWithID \
    stopClientWithID \
    stopServerWithID \
    stopAll \
    listInstances \
    setLogCallback; do
    printf '%s' "${frplib_api}" | grep -q "${method}" || {
      echo "AAR Frplib 缺少方法: ${method} (${aar})" >&2
      exit 1
    }
  done

  local callback_api
  callback_api="$(javap -classpath "${tmp_dir}/${name}/classes.jar" io.github.sky22333.frplib.FrpLogCallback)"
  printf '%s' "${callback_api}" | grep -q "onLog" || {
    echo "AAR FrpLogCallback 缺少 onLog (${aar})" >&2
    exit 1
  }

  echo "AAR 验收通过: ${aar}"
}

validate_aar "${root_dir}/app/libs/universal/frplib-universal.aar"
validate_aar "${root_dir}/app/libs/arm64-v8a/frplib-arm64-v8a.aar"
validate_aar "${root_dir}/app/libs/armeabi-v7a/frplib-armeabi-v7a.aar"
validate_aar "${root_dir}/app/libs/x86_64/frplib-x86_64.aar"