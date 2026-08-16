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
docker compose -f "$compose_file" run --rm builder \
  ./gradlew --no-daemon --console=plain :app:testAutomationUnitTest :app:assembleAutomation
docker compose -f "$compose_file" up -d emulator
docker compose -f "$compose_file" run --rm builder \
  /workspace/dev/android/container-smoke.sh
docker compose -f "$compose_file" ps
