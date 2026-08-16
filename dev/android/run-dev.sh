#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/../.." && pwd)
compose_file="$script_dir/compose.yaml"

export LOCAL_UID=${LOCAL_UID:-$(id -u)}
export LOCAL_GID=${LOCAL_GID:-$(id -g)}
export KVM_GID=${KVM_GID:-$(stat -c '%g' /dev/kvm)}

cd "$repo_root"

docker compose -f "$compose_file" build builder
docker compose -f "$compose_file" --profile smoke stop emulator-smoke
docker compose -f "$compose_file" --profile dev up -d emulator-dev
docker compose -f "$compose_file" run --rm \
  -e ANDROID_ADB_TARGET=emulator-dev:5555 \
  -e ANDROID_ARTIFACTS_DIR=/workspace/build/android-dev \
  builder \
  /workspace/dev/android/container-emulator-ready.sh
docker compose -f "$compose_file" --profile dev ps
