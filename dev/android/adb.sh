#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
compose_file="$script_dir/compose.yaml"
emulator_service=emulator-dev

if [[ "${1:-}" == --smoke ]]; then
  emulator_service=emulator-smoke
  shift
elif [[ "${1:-}" == --dev ]]; then
  shift
fi

export LOCAL_UID=${LOCAL_UID:-$(id -u)}
export LOCAL_GID=${LOCAL_GID:-$(id -g)}
export KVM_GID=${KVM_GID:-$(stat -c '%g' /dev/kvm)}

docker compose -f "$compose_file" run --rm \
  -e ANDROID_ADB_TARGET="${emulator_service}:5555" \
  builder \
  /workspace/dev/android/container-adb.sh "$@"
