#!/usr/bin/env bash
set -euo pipefail

apk="${1:?usage: verify-release-apk.sh <apk>}"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_root" ]]; then
  echo "ANDROID_HOME or ANDROID_SDK_ROOT is required" >&2
  exit 2
fi

apksigner="$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -perm -u+x \
  | sort -V | tail -n 1 || true)"
if [[ -z "$apksigner" ]]; then
  echo "No apksigner found under $sdk_root/build-tools" >&2
  exit 2
fi
echo "Using $apksigner" >&2
"$apksigner" verify --print-certs "$apk"
