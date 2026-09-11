# Локальный Android SDK и AVD

На `syrok-server` используется локальный Android SDK под пользователем `syrok`.
Android CLI обслуживает SDK, T3 Code управляет AVD и интерактивным экраном,
Gradle Wrapper собирает приложение и запускает тесты. Android Studio не нужна.

Существующие Docker-файлы и `run-dev.sh`, `run-smoke.sh`, `adb.sh` в этом каталоге
относятся к прежнему контуру. Для локальной среды используйте команды ниже.

## SDK

Установите официальный Android CLI:
https://developer.android.com/tools/agents/android-cli/download.
Задайте окружение в shell и в окружении серверного процесса T3:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$HOME/.local/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

Для текущего `compileSdk = 37` установите платформу 37.0. Образ AVD Android 36
используется независимо от платформы компиляции:

```bash
android --sdk="$ANDROID_HOME" sdk install \
  cmdline-tools/latest platform-tools emulator \
  platforms/android-37.0 build-tools/36.0.0 \
  system-images/android-36/google_apis/x86_64
emulator -accel-check
```

Нужны JDK 21 и доступ пользователя к `/dev/kvm`. После изменения `compileSdk`
сверяйте пакет платформы с `app/build.gradle.kts`. После установки SDK
перезапустите сервер T3: он запоминает пути при старте. Перезапуск сервиса может
завершить запущенные внутри него сессии агентов; сначала сохраните результаты.

## Устройства

Создайте только отсутствующие AVD, проверив `emulator -list-avds`:

```bash
avdmanager create avd --name nextgallery-api36-dev \
  --package 'system-images;android-36;google_apis;x86_64'
avdmanager create avd --name nextgallery-api36-smoke \
  --package 'system-images;android-36;google_apis;x86_64'
```

На вопрос о custom hardware profile ответьте `no`. AVD хранятся в
`~/.android/avd`. Dev сохраняет данные между запусками; smoke имеет отдельное
userdata для чистых проверок. Оба используют один установленный системный образ.

В `config.ini` выключенного AVD задайте 3072 МБ RAM (`hw.ramSize`), четыре CPU
(`hw.cpu.ncore`), экран 1080×2340 (`hw.lcd.width`, `hw.lcd.height`), 450 dpi
(`hw.lcd.density`), `hw.gpu.enabled=yes`, `hw.gpu.mode=swiftshader`.
Экран даёт 384×832 dp. На Emulator 37.1.11 режим `-gpu auto`, используемый T3,
проверен без display: выбран Google SwiftShader, CPU ускоряется через KVM.
На этой машине запускайте один AVD за раз и собирайте APK до запуска VM,
чтобы снизить пиковое потребление памяти.

## Работа через T3 Code

Откройте `https://t3.syrok/`, панель Device и `nextgallery-api36-dev`.
Android-видео требует HTTPS с доверенным сертификатом или localhost;
`http://t3.syrok/` не предоставляет WebCodecs. На новом клиентском устройстве
установите доверие к локальному корневому сертификату. Закрытый ключ CA
остаётся на сервере.

Включите Agent device access и начните новую сессию агента для получения
окружения CLI. При доступных `device_*` и `agent-device` используйте их для
интерактивной проверки. Закрытие вкладки не выключает AVD: используйте кнопку
питания. Предупреждение `xcrun` относится к поиску iOS на Linux; само по себе
оно не означает отказ Android.

ADB остаётся на localhost, удалённое управление идёт через соединение T3.
Для обычного терминала сначала определите serial именно нужного AVD:

```bash
adb devices
adb -s emulator-5554 emu avd name
```

`emulator-5554` — пример: порт может меняться. В последующих командах задайте
`ANDROID_SERIAL` по проверенному serial. Это исключает выбор другого AVD или
физического телефона.

## Сборка и runtime-проверка

```bash
./gradlew --no-daemon --max-workers=2 --console=plain \
  :app:testAutomationUnitTest :app:assembleAutomation \
  :app:assembleAutomationAndroidTest :app:lintAutomation
```

Затем запустите dev-AVD через T3. Для ручной установки из терминала:

```bash
export ANDROID_SERIAL=emulator-5554 # serial после проверки имени AVD
adb install -r app/build/outputs/apk/automation/app-automation.apk
adb shell am start -n \
  com.syrok0010.nextgallery.automation/com.syrok0010.nextgallery.MainActivity
```

Для чистой финальной проверки выключите dev и любой работающий smoke-AVD,
затем в отдельном терминале запустите:

```bash
emulator -avd nextgallery-api36-smoke -no-window -no-audio -gpu auto \
  -wipe-data -no-snapshot
```

Проверьте имя и serial тестового устройства. Дождитесь значения `1` от
`adb shell getprop sys.boot_completed`, установите и запустите automation APK
командами выше. Воспроизведите изменённый сценарий. Сохраните screenshot,
UI hierarchy и logcat в `build/android-smoke/`; проверьте отсутствие ANR и
падений приложения. Затем выполните:

```bash
./gradlew --no-daemon --max-workers=2 --console=plain \
  :app:connectedAutomationAndroidTest
adb emu kill
```

`ANDROID_SERIAL` должен указывать на smoke-AVD весь прогон. Отчёты тестов —
в `app/build/reports/androidTests/`. Сброс dev userdata не входит в smoke.
Правила физического телефона описаны в `docs/agents/testing.md`.

## Обслуживание

```bash
android sdk list --all
android sdk update emulator
android sdk update platform-tools
avdmanager list avd
```

Обновляйте пакеты при выключенных AVD, затем проверяйте загрузку и приложение.
Новую версию Android проверяйте отдельным AVD. Старые Docker volumes
`nextgallery-android_avd-data` и `nextgallery-android_gradle-cache` не используются
локальным SDK; их удаление требует отдельного решения о потере старых данных.
