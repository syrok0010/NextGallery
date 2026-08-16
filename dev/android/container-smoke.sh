#!/usr/bin/env bash
set -euo pipefail

adb_target=${ANDROID_ADB_TARGET:-emulator:5555}
artifacts_dir=/workspace/build/android-smoke
apk=/workspace/app/build/outputs/apk/automation/app-automation.apk

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

if [[ ! -f "$apk" ]]; then
  printf 'Automation APK не найден: %s\n' "$apk" >&2
  exit 1
fi

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

  # Экран эмулятора зафиксирован как 720x1280; центр кнопки Wait — ниже
  # центра системного ANR-диалога.
  adb -s "$adb_target" shell input tap 360 827
  sleep 10
fi

adb -s "$adb_target" install -r "$apk"
adb -s "$adb_target" shell am force-stop com.syrok0010.nextgallery.automation
adb -s "$adb_target" shell am start \
  -n com.syrok0010.nextgallery.automation/com.syrok0010.nextgallery.MainActivity
sleep 10

dump_ui /data/local/tmp/nextgallery-window.xml "$artifacts_dir/window.xml"

if grep -Eqi "isn.t responding|not responding" "$artifacts_dir/window.xml"; then
  printf 'Android показал системный ANR после запуска приложения.\n' >&2
  exit 1
fi

if ! grep -q 'package="com.syrok0010.nextgallery.automation"' "$artifacts_dir/window.xml"; then
  printf 'Экран NextGallery Automation не найден в UI hierarchy.\n' >&2
  exit 1
fi

adb -s "$adb_target" exec-out screencap -p >"$artifacts_dir/screen.png"
adb -s "$adb_target" logcat -d -t 1000 >"$artifacts_dir/logcat.txt"

printf 'Smoke-проверка завершена. Артефакты: %s\n' "$artifacts_dir"
