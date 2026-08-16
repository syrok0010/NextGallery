# Headless Android-контур

Контур собирает NextGallery и запускает один Android 16 (API 36) x86_64
эмулятор в Docker с аппаратным ускорением KVM. Android SDK, JDK 21 и system
image находятся внутри Docker-образа; на хост передается только `/dev/kvm`.
Гостю выделено 2 ГБ RAM, четыре CPU и экран 720x1280; контейнер ограничен
4 ГБ RAM, включая память QEMU и SwiftShader. Vulkan отключен, чтобы снизить
графический overhead headless-запуска.

## Постоянный dev-эмулятор

Из корня репозитория:

```bash
./dev/android/run-dev.sh
```

Скрипт поднимает `emulator-dev` и ожидает полной загрузки Android. Его userdata
хранится в Docker volume `nextgallery-android_avd-data`, поэтому установленные
APK, данные приложений и изменения в системе сохраняются после остановки и
повторного запуска. Это основной режим для интерактивной работы агента.

Команды ADB выполняются через SDK внутри контейнера (по умолчанию в
`emulator-dev`):

```bash
./dev/android/adb.sh devices
./dev/android/adb.sh install -r app/build/outputs/apk/automation/app-automation.apk
./dev/android/adb.sh shell am start \
  -n com.syrok0010.nextgallery.automation/com.syrok0010.nextgallery.MainActivity
./dev/android/adb.sh shell input tap 360 640
./dev/android/adb.sh exec-out screencap -p > build/android-dev/screen.png
./dev/android/adb.sh logcat -d
```

Таким способом агент может произвольно устанавливать и запускать сборки,
выполнять shell-команды, вводить текст и касания, читать UI hierarchy, logcat и
делать снимки экрана. Это не привязано к одному заранее заданному тесту.

## Воспроизводимый smoke

```bash
./dev/android/run-smoke.sh
```

Скрипт:

1. собирает Docker-образ;
2. запускает JVM-тесты и собирает automation APK;
3. останавливает dev-режим и поднимает чистый headless-эмулятор с `-wipe-data`;
4. устанавливает и запускает приложение;
5. сохраняет `screen.png`, `logcat.txt` и `window.xml` в
   `build/android-smoke/`.

На холодном boot Android 16 с программной графикой System UI иногда показывает
одноразовый ANR до запуска приложения. Preflight распознает только этот точный
диалог и нажимает `Wait`; любой ANR после запуска NextGallery завершает smoke с
ошибкой.

Первое выполнение загружает Android SDK и system image и поэтому занимает
несколько гигабайт диска и заметно дольше повторных запусков.

`run-dev.sh` и `run-smoke.sh` автоматически останавливают противоположный
режим, потому что оба используют один адрес ADB. После smoke можно снова вызвать
`run-dev.sh`: состояние постоянного dev-эмулятора останется в его volume.
Для разовой команды в smoke-эмуляторе используйте:

```bash
./dev/android/adb.sh --smoke shell getprop sys.boot_completed
```

## ADB

ADB эмулятора опубликован только на WireGuard-адресе хоста:

```bash
adb connect 10.9.0.1:5555
```

Порт доступен узлам, имеющим маршрут к `10.9.0.0/24`. Эмулятор запускается с
`-skip-adb-auth`, поэтому этот порт нельзя публиковать на внешнем интерфейсе или
пробрасывать из WireGuard-сети в интернет.

## Повторные команды

Сборка и unit-тесты без запуска эмулятора:

```bash
docker compose -f dev/android/compose.yaml run --rm builder \
  ./gradlew --no-daemon --console=plain \
  :app:testAutomationUnitTest :app:assembleAutomation
```

Состояние и логи эмулятора:

```bash
docker compose -f dev/android/compose.yaml --profile dev ps
docker compose -f dev/android/compose.yaml --profile dev logs emulator-dev
docker compose -f dev/android/compose.yaml --profile smoke logs emulator-smoke
```

Остановка контейнеров без удаления постоянных volumes:

```bash
docker compose -f dev/android/compose.yaml down
```

Gradle cache хранится в Docker volume `nextgallery-android_gradle-cache`.
Эмуляторы не имеют автозапуска и после `down` не остаются запущенными.

Чтобы намеренно полностью сбросить dev-эмулятор, сначала остановите контейнер,
а затем явно удалите только его volume:

```bash
docker compose -f dev/android/compose.yaml --profile dev stop emulator-dev
docker compose -f dev/android/compose.yaml --profile dev rm -f emulator-dev
docker volume rm nextgallery-android_avd-data
```

Следующий `run-dev.sh` создаст чистое userdata. Эта операция необратимо удаляет
установленные в dev-эмулятор приложения и их данные.
