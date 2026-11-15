# Android-разработка

По умолчанию используй эмулятор на `syrok-server` через T3 Code: локальный device
host на сервере, SSH device host `syrok-server` на `syrok-arch`. Всё, что делает
пользователь — запуск, нажатия, ввод, разрешения, скриншоты, запись, — идёт через
`device_*` и `agent-device`, чтобы пользователь видел это в Device panel. `adb`
остаётся для того, чего `agent-device` не умеет: сборка и установка, логи, файлы
и MediaStore (`push`, `MEDIA_SCANNER_SCAN_FILE`, `content query`), язык приложения.

Реальный телефон используй только по явному указанию пользователя с выбранным
ADB serial. Команды тестирования и защита данных телефона — в [testing.md](testing.md).
