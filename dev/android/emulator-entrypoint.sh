#!/usr/bin/env bash
set -euo pipefail

avd_dir=/home/android/.android/avd/nextgallery-api36.avd
avd_config="$avd_dir/config.ini"
gpu_mode=${EMULATOR_GPU_MODE:-swiftshader}
screen_size=${EMULATOR_SCREEN_SIZE:-1080x2340}
screen_density=${EMULATOR_SCREEN_DENSITY:-450}

if [[ ! "$screen_size" =~ ^[0-9]+x[0-9]+$ ]]; then
  printf 'Некорректный EMULATOR_SCREEN_SIZE: %s\n' "$screen_size" >&2
  exit 1
fi

if [[ ! "$screen_density" =~ ^[0-9]+$ ]]; then
  printf 'Некорректный EMULATOR_SCREEN_DENSITY: %s\n' "$screen_density" >&2
  exit 1
fi

sed -i \
  -e "s/^hw.lcd.width=.*/hw.lcd.width=${screen_size%x*}/" \
  -e "s/^hw.lcd.height=.*/hw.lcd.height=${screen_size#*x}/" \
  -e "s/^hw.lcd.density=.*/hw.lcd.density=$screen_density/" \
  -e 's/^hw.gpu.enabled=.*/hw.gpu.enabled=yes/' \
  -e "s/^hw.gpu.mode=.*/hw.gpu.mode=$gpu_mode/" \
  "$avd_config"

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
  -gpu "$gpu_mode"
  -accel on
  -skin "$screen_size"
  -memory 2048
  -cores 4
  -camera-back none
  -camera-front none
)

if [[ "${EMULATOR_DISABLE_VULKAN:-0}" == 1 ]]; then
  emulator_args+=( -feature -Vulkan )
fi

if [[ "${EMULATOR_WIPE_DATA:-0}" == 1 ]]; then
  emulator_args+=(
    -wipe-data
    -no-snapshot
  )
fi

exec emulator "${emulator_args[@]}"
