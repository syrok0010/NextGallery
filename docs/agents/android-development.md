# Android-разработка

Основной хост Android-эмулятора и тестового стенда — **`syrok-server`** (один и тот
же хост в инфраструктуре: VPN IP `10.9.0.1`, в T3 Code на других машинах — SSH device
host **`Homelab`** и linked environment **`syrok0010.keenetic.pro`**).

## Эмулятор и три пакета

По умолчанию используй единственный AVD **`nextgallery-api36-dev`** на `syrok-server`:
на самом сервере через локальный device host, на `syrok-arch` и других машинах —
через SSH device host **`Homelab`** (локальные AVD других машин по умолчанию не
поднимай).

Один AVD закрывает все сценарии без перенастройки входа благодаря разделению по
`applicationId`:

- **`com.syrok0010.nextgallery.preview` (NextGallery Test)** — постоянная
  залогиненная установка на `http://test.syrok/` под учётной записью `test`.
  Фиксированный `versionCode = 1` и общий ключ `tools/ci/preview.p12` позволяют
  ставить сборку с любой ветки поверх без потери сессии и БД — используй её для
  обычных ручных проверок и скриншотов.
- **`com.syrok0010.nextgallery` (`debug`)** — одноразовая песочница на этом же
  эмуляторе для проверок с нуля (Login Flow, проверка версии Memories, logout через
  `clearApplicationUserData()`, быстрый `installDebug` без R8). На эмуляторе её
  безопасно очищать и переустанавливать; **на реальном телефоне пользователя
  данные этого пакета трогать запрещено** (см. [testing.md](testing.md)).
- **`com.syrok0010.nextgallery.automation` (`automation`)** — отдельный пакет для
  `connectedAutomationAndroidTest`; Gradle сам устанавливает и удаляет его после
  прогона, не затрагивая `preview` и `debug`.

## Инструменты и работа с других машин

- Всё, что делает пользователь — запуск приложения, нажатия, ввод, разрешения,
  скриншоты, запись, — идёт через T3 Code `device_*` (`device_list` → `device_open` →
  `agent-device` и `device_screenshot`), чтобы пользователь видел это в Device panel.
  Этот путь одинаково работает и на `syrok-server`, и с других машин через `Homelab`.
- `adb` остаётся для того, чего `agent-device` не умеет: установка APK, логи,
  файлы и MediaStore (`push`, `MEDIA_SCANNER_SCAN_FILE`, `content query`), язык
  приложения. Эти команды выполняются **на `syrok-server`** (локальный `adb` другой
  машины эмулятор на `syrok-server` не видит): при работе с другой машины выполняй
  их через linked environment `syrok0010.keenetic.pro` или SSH на `syrok-server`.
  Актуальный serial бери из `adb devices` на `syrok-server`, не захардкоживай.

Реальный телефон используй только по явному указанию пользователя с выбранным
ADB serial. Команды тестирования и защита данных телефона — в [testing.md](testing.md).
