# Android-разработка

Основной хост Android-эмулятора и тестового стенда — **`syrok-server`** (VPN IP
`10.9.0.1`; в T3 Code на других машинах — SSH device host **`Homelab`** и linked
environment **`syrok0010.keenetic.pro`**).

## Эмулятор и три пакета

По умолчанию используй единственный AVD **`nextgallery-api36-dev`** на `syrok-server`:
на самом сервере через локальный device host, на `syrok-arch` и других машинах —
через SSH device host **`Homelab`** (локальные AVD других машин по умолчанию не
поднимай).

Один AVD закрывает все сценарии благодаря разделению по `applicationId`:

- **`com.syrok0010.nextgallery.preview` (NextGallery Test)** — постоянная
  залогиненная установка на `http://test.syrok/` под `test`. Фиксированный
  `versionCode = 1` и общий публичный ключ `tools/ci/preview.p12` позволяют
  обновлять её сборками разных веток без потери сессии и БД — используй для
  обычных ручных проверок и скриншотов (получение APK — в [release.md](release.md#тестовые-apk)).
- **`com.syrok0010.nextgallery` (`debug`)** — одноразовая песочница на этом же
  эмуляторе для проверок с нуля (Login Flow, проверка версии Memories, logout через
  `clearApplicationUserData()`, быстрый `installDebug` без R8). На эмуляторе её
  безопасно очищать и переустанавливать.
- **`com.syrok0010.nextgallery.automation` (`automation`)** — изолированный пакет
  для `connectedAutomationAndroidTest`; Gradle сам устанавливает и удаляет его после
  прогона, не затрагивая `preview` и `debug`.

## Инструменты и работа с других машин

- Всё, что делает пользователь — запуск приложения, нажатия, ввод, разрешения,
  скриншоты, запись, — идёт через T3 Code `device_*` (`device_list` → `device_open` →
  `agent-device` и `device_screenshot`), чтобы пользователь видел это в Device panel.
  Работает и на `syrok-server`, и с других машин через `Homelab`.
- `adb` (установка APK, логи, MediaStore `push` / `MEDIA_SCANNER_SCAN_FILE`, язык
  приложения), `docker compose` стенда и `connectedAutomationAndroidTest` выполняются
  **на `syrok-server`**: локальный `adb` другой машины эмулятор на `syrok-server` не
  видит. С другой машины выполняй их через linked environment `syrok0010.keenetic.pro`
  или SSH на `syrok-server`; актуальный serial бери из `adb devices`.

## Реальный телефон

Реальный телефон используй только по явному указанию пользователя с выбранным
ADB serial.

> **Данные телефона:** на телефоне пользователя запрещено трогать
> `com.syrok0010.nextgallery` — очищать данные, удалять или переустанавливать
> приложение и выполнять действия, сбрасывающие его состояние. Для тестов используй
> только `com.syrok0010.nextgallery.automation` (`connectedDebugAndroidTest` на
> телефоне запрещён).
