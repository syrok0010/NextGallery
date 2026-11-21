# Тестовый Nextcloud

Для интеграционных проверок NextGallery используй отдельный Nextcloud/Memories
на **`syrok-server`** (`10.9.0.1`, в T3 Code — `Homelab` / linked environment
`syrok0010.keenetic.pro`): **http://test.syrok/**. Локальный AdGuard Home
разрешает `test.syrok` в `10.9.0.1`; доступ по HTTP работает и с других машин
при поднятом WireGuard (через тот же DNS и nginx).

## Доступ и данные

- Рабочая учётная запись — `test`, папка медиатеки — `/Photos`.
- Memories доступен по `/apps/memories/`, API — по `/apps/memories/api/`.
- Учётные данные и команды запуска, остановки и удаления ведутся в
  `/home/syrok/nextgallery-testcloud/README.md` на `syrok-server`.
- Перед загрузкой или удалением медиа проверяй адрес подключённой сессии:
  операции должны выполняться на `http://test.syrok/` под тестовой учётной записью.
  Личный Nextcloud содержит пользовательские данные и для этих проверок не подходит.
- Стенд и эмулятор `nextgallery-api36-dev` на `syrok-server` (см.
  [android-development.md](android-development.md)) — рабочие среды агента.
  Постоянные тестовые медиа (_фикстуры_) переиспользуй между задачами:
  - стенд, `/Photos/fixtures/`: `cloud-NN.jpg`, объекты только в облаке;
  - эмулятор, `/sdcard/Pictures/fixtures/`: `phone-N.jpg`, объекты только на телефоне.
- Загрузку, удаление и добавление в альбом проверяй на одноразовых копиях в папке
  задачи и после проверки удаляй их вместе со своими записями в корзине: фикстуры
  остаются одинаковыми для всех задач.

## Android и HTTP

Стенд работает по HTTP. Для подключения из Android требуется разрешение
cleartext-трафика к точному домену `test.syrok` в network security config тестового
варианта приложения. Проверяй конфигурацию выбранной сборки, DNS и маршрут с устройства:
успешный запрос с хоста не подтверждает доступность сервера из приложения.

Выбор устройства и защита данных телефона описаны в
[android-development.md](android-development.md) и [testing.md](testing.md).

## Скриншоты и состояния

Команды `adb` и `docker compose` выполняются на `syrok-server` (с других машин —
через linked environment `syrok0010.keenetic.pro` или SSH на `syrok-server`).

- Русский интерфейс на английском эмуляторе:
  `adb -s <serial> shell cmd locale set-app-locales <пакет> --locales ru-RU`
  (где `<serial>` — актуальный serial `nextgallery-api36-dev` из `adb devices`).
- Состояние ошибки сети: `docker compose pause app` в каталоге стенда, после
  проверки — `docker compose unpause app`. Запросы зависают, операции падают по таймауту.

## Управление на `syrok-server`

Compose-проект `nextgallery-testcloud` находится на `syrok-server` в
`/home/syrok/nextgallery-testcloud`, вне Git-репозитория NextGallery. Сам clone
проекта не разворачивает этот стенд. Его README и `compose.yaml` — источник
актуальных версий, настроек и команд управления.

HTTP-проверку можно выполнить с любой машины с WireGuard; `docker compose` — на
`syrok-server`:

```bash
# на syrok-server:
cd /home/syrok/nextgallery-testcloud
docker compose ps

# на syrok-server или другой машине с WireGuard:
curl --noproxy '*' --fail --silent --show-error --max-time 15 http://test.syrok/status.php
```

Ожидается `installed: true`, `maintenance: false`, `needsDbUpgrade: false`.
`--noproxy '*'` обходит proxy-переменные окружения: прокси может не разрешать
локальный домен, хотя прямой доступ работает.

Memories индексирует новые медиа фоновыми заданиями. Для немедленной проверки
после загрузки запусти на `syrok-server` из каталога стенда:

```bash
docker compose exec --user www-data app php occ memories:index --user=test
```

У стенда собственные БД и Docker-тома; nginx направляет `test.syrok` на
`127.0.0.1:8182` через `/etc/nginx/conf.d/nextgallery-testcloud.conf`.
Скрипт удаления из README убирает этот маршрут, контейнеры и все данные стенда.
Остановка контейнеров сохраняет данные.

Базовая версия Memories — **11.1.4**. HLS проверяется через встроенный go-vod
(fMP4 с init-сегментом); распознавание лиц и карты отдельно не настроены.
