#!/usr/bin/env bash
set -euo pipefail

avd_dir=/home/android/.android/avd/nextgallery-api36.avd
rm -f -- "$avd_dir"/*.lock

adb start-server
socat TCP-LISTEN:5555,reuseaddr,fork TCP:127.0.0.1:5557 &

emulator_args=(
  @nextgallery-api36
  -ports 5556,5557
  -no-window
  -no-audio
  -no-boot-anim
  -skip-adb-auth
  -no-metrics
  -gpu swiftshader
  -feature -Vulkan
  -accel on
  -skin 720x1280
  -memory 2048
  -cores 4
  -camera-back none
  -camera-front none
)

if [[ "${EMULATOR_WIPE_DATA:-0}" == 1 ]]; then
  emulator_args+=(
    -wipe-data
    -no-snapshot
  )
fi

exec emulator "${emulator_args[@]}"
