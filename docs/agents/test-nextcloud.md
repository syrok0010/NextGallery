# Тестовый Nextcloud

Для интеграционных проверок используется отдельный Nextcloud/Memories на
`syrok-server`: **http://test.syrok/**. Локальный AdGuard Home разрешает
`test.syrok` в `10.9.0.1`; HTTP-запросы работают с любой машины с поднятым
WireGuard, а команды `docker compose` и `adb` выполняются на `syrok-server`
(подробнее о хосте и AVD `nextgallery-api36-dev` — в [android-development.md](android-development.md)).

## Доступ и данные

- Рабочая учётная запись — `test`, папка медиатеки — `/Photos`.
- Memories доступен по `/apps/memories/`, API — по `/apps/memories/api/`.
- Учётные данные и команды запуска/остановки/удаления ведутся в
  `/home/syrok/nextgallery-testcloud/README.md` на `syrok-server`.
- Перед загрузкой или удалением медиа проверяй адрес подключённой сессии:
  операции должны выполняться на `http://test.syrok/` под `test`, а не на личном
  Nextcloud пользователя.
- Постоянные тестовые медиа (_фикстуры_) переиспользуй между задачами:
  - стенд, `/Photos/fixtures/`: `cloud-NN.jpg`, объекты только в облаке;
  - эмулятор `nextgallery-api36-dev`, `/sdcard/Pictures/fixtures/`: `phone-N.jpg`,
    объекты только на телефоне.
- Загрузку, удаление и добавление в альбом проверяй на одноразовых копиях в папке
  задачи и после проверки удаляй их вместе со своими записями в корзине: фикстуры
  остаются одинаковыми для всех задач.

## Android и HTTP

Стенд работает по HTTP. Для подключения из Android требуется разрешение
cleartext-трафика к точному домену `test.syrok` в network security config тестового
варианта приложения. Проверяй конфигурацию выбранной сборки, DNS и маршрут с устройства:
успешный запрос с хоста не подтверждает доступность сервера из приложения.

## Скриншоты и состояния

- Русский интерфейс на английском эмуляторе:
  `adb -s <serial> shell cmd locale set-app-locales <пакет> --locales ru-RU`.
- Состояние ошибки сети: `docker compose pause app` в каталоге стенда, после
  проверки — `docker compose unpause app`. Запросы зависают, операции падают по таймауту.

## Управление стендом

Compose-проект `nextgallery-testcloud` лежит в `/home/syrok/nextgallery-testcloud`,
вне Git-репозитория NextGallery; его README и `compose.yaml` — источник актуальных
версий и настроек.

```bash
# на syrok-server:
cd /home/syrok/nextgallery-testcloud
docker compose ps

# с любой машины с WireGuard:
curl --noproxy '*' --fail --silent --show-error --max-time 15 http://test.syrok/status.php
```

Ожидается `installed: true`, `maintenance: false`, `needsDbUpgrade: false`.
`--noproxy '*'` обходит proxy-переменные окружения, которые могут не разрешать
локальный домен.

Memories индексирует новые медиа фоновыми заданиями; для немедленной индексации
после загрузки:

```bash
docker compose exec --user www-data app php occ memories:index --user=test
```

У стенда собственные БД и Docker-тома; nginx направляет `test.syrok` на
`127.0.0.1:8182` через `/etc/nginx/conf.d/nextgallery-testcloud.conf`. Скрипт
удаления из README убирает маршрут, контейнеры и все данные стенда; остановка
контейнеров сохраняет данные.

Базовая версия Memories — **11.1.4**. HLS проверяется через встроенный go-vod
(fMP4 с init-сегментом); распознавание лиц и карты отдельно не настроены.
