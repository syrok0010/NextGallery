#!/usr/bin/env bash
set -euo pipefail

adb_target=${ANDROID_ADB_TARGET:?ANDROID_ADB_TARGET не задан}
artifacts_dir=${ANDROID_ARTIFACTS_DIR:-/workspace/build/android-emulator}

dump_ui() {
  local remote_path=$1
  local local_path=$2

  for _ in $(seq 1 30); do
    adb -s "$adb_target" shell rm -f -- "$remote_path"
    if adb -s "$adb_target" shell uiautomator dump "$remote_path" >/dev/null 2>&1 \
      && adb -s "$adb_target" shell test -s "$remote_path" \
      && adb -s "$adb_target" pull "$remote_path" "$local_path" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done

  printf 'UI hierarchy не стала доступна за 60 секунд: %s\n' "$remote_path" >&2
  return 1
}

mkdir -p "$artifacts_dir"

booted=
for _ in $(seq 1 90); do
  adb connect "$adb_target" >/dev/null 2>&1 || true
  if [[ "$(adb -s "$adb_target" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
    booted=1
    break
  fi
  sleep 2
done

if [[ -z "$booted" ]]; then
  printf 'Эмулятор не загрузился за 180 секунд.\n' >&2
  exit 1
fi

sleep 10
dump_ui /data/local/tmp/nextgallery-preflight.xml "$artifacts_dir/preflight.xml"

if grep -q "System UI isn.t responding" "$artifacts_dir/preflight.xml"; then
  if ! grep -q 'text="Wait"' "$artifacts_dir/preflight.xml"; then
    printf 'System UI ANR не содержит ожидаемой кнопки Wait.\n' >&2
    exit 1
  fi

  wait_bounds=$(grep -o 'text="Wait"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
    "$artifacts_dir/preflight.xml" | head -n 1)
  bounds_pattern='bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"'
  if [[ "$wait_bounds" =~ $bounds_pattern ]]; then
    wait_x=$(( (BASH_REMATCH[1] + BASH_REMATCH[3]) / 2 ))
    wait_y=$(( (BASH_REMATCH[2] + BASH_REMATCH[4]) / 2 ))
  else
    printf 'Не удалось определить координаты кнопки Wait.\n' >&2
    exit 1
  fi

  adb -s "$adb_target" shell input tap "$wait_x" "$wait_y"
  sleep 10
fi

printf 'Эмулятор готов: %s\n' "$adb_target"
