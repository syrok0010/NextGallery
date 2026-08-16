#!/usr/bin/env bash
set -euo pipefail

adb_target=${ANDROID_ADB_TARGET:?ANDROID_ADB_TARGET не задан}

adb connect "$adb_target" >/dev/null
exec adb -s "$adb_target" "$@"
