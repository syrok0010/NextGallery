# Headless Android-контур

Контур собирает NextGallery и запускает один Android 16 (API 36) x86_64
эмулятор в Docker с аппаратным ускорением KVM. Android SDK, JDK 21 и system
image находятся внутри Docker-образа; на хост передается только `/dev/kvm`.
Гостю выделено 2 ГБ RAM, четыре CPU и экран 720x1280; контейнер ограничен
4 ГБ RAM, включая память QEMU и SwiftShader. Vulkan отключен, чтобы снизить
графический overhead headless-запуска.

## Первый запуск

Из корня репозитория:

```bash
./dev/android/run-smoke.sh
```

Скрипт:

1. собирает Docker-образ;
2. запускает JVM-тесты и собирает automation APK;
3. поднимает headless-эмулятор;
4. устанавливает и запускает приложение;
5. сохраняет `screen.png`, `logcat.txt` и `window.xml` в
   `build/android-smoke/`.

На холодном boot Android 16 с программной графикой System UI иногда показывает
одноразовый ANR до запуска приложения. Preflight распознает только этот точный
диалог и нажимает `Wait`; любой ANR после запуска NextGallery завершает smoke с
ошибкой.

Первое выполнение загружает Android SDK и system image и поэтому занимает
несколько гигабайт диска и заметно дольше повторных запусков.

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
docker compose -f dev/android/compose.yaml ps
docker compose -f dev/android/compose.yaml logs emulator
```

Остановка:

```bash
docker compose -f dev/android/compose.yaml down
```

Gradle cache хранится в Docker volume `nextgallery-android_gradle-cache`.
Эмулятор не имеет автозапуска и после `down` не остается запущенным.
