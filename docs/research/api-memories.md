# Исследование: Memories API

## Гипотеза

Memories API должен быть основным API для фотодоменной модели, потому что он уже оперирует timeline, днями, preview, EXIF, альбомами, тегами, people/places и видео как фотопродукт, а не как файловое дерево.

Это легче для native-клиента, чем строить фотомодель вручную поверх WebDAV. API считается внутренним контрактом Memories, поэтому DTO и transport rules изолируются внутри data layer.

## Что найдено в локальном clone Memories

Локальный clone: `/home/syrok/WebstormProjects/memories`.

Основные маршруты объявлены в:

```text
appinfo/routes.php
```

Клиентские URL helper'ы объявлены в:

```text
src/services/API.ts
```

Типы фотомодели для Web UI объявлены в:

```text
src/typings/data.d.ts
```

В репозитории также есть Android-клиент Memories:

```text
android/app/src/main/java/gallery/memories
```

Это важно изучить отдельно: он может подсказать модель локальных фото, кеша и интеграции с уже существующим web/native слоем Memories.

## Что показал browser capture главной страницы

Raw capture из Chrome DevTools содержит cookies, request token, приватные URL и имена личных файлов, поэтому его нельзя коммитить. В документацию переносится только обезличенный вывод.

При открытии главной Memories браузерный клиент делает минимальный полезный flow:

```text
GET  /apps/memories/api/config
GET  /apps/memories/api/days
GET  /apps/memories/api/days/{id1,id2}
POST /apps/memories/api/image/multipreview
```

Наблюдения:

- `/config` возвращает версию Memories и включенные возможности: albums, system tags, recognize, preview generator, timeline path, viewer/folder/album settings.
- `GET /days` возвращает список дней с `dayid` и `count`; для первых дней ответ может сразу содержать `detail` с медиаэлементами.
- `GET /days/{id1,id2}` поддерживает загрузку нескольких дней одним запросом через comma-separated id в path.
- `POST /days` используется браузерным клиентом для bulk-загрузки списка day ids через JSON body.
- Элемент timeline содержит достаточный минимум для grid без отдельного WebDAV-запроса: `fileid`, `dayid`, `w`, `h`, `etag`, `basename`, `epoch`, `mimetype`, `auid`, а для видео также `isvideo` и `video_duration`.
- Boolean-like поля могут приходить не как JSON boolean, а как `0/1`; например, `isfavorite: 1`. DTO/mapping слой должен быть tolerant к таким значениям.
- `POST /image/multipreview` принимает список `{ fileid, x, y, a, reqid }` и подходит для батчевой загрузки thumbnails.

Вывод для MVP-1: первый timeline можно строить напрямую на Memories API:

```text
config -> days -> lazy day details -> multipreview -> grid/detail
```

WebDAV для первого timeline не нужен, но остается fallback и будущий слой для download/upload/file operations.

## Live-проверка MVP-1 baseline

2026-06-29 текущая реализация была проверена на живом Android-устройстве и личном Nextcloud/Memories сервере через app password из Login Flow v2.

Проверенный flow:

```text
login -> Memories API -> days -> lazy day details -> thumbnails -> grid -> viewer
```

Наблюдения:

- Удаленный timeline загружается и отображается в grid.
- Lazy loading day details работает на стабильном архиве без добавления или удаления фото во время проверки.
- Thumbnail loading работает для просмотренных участков timeline.
- Viewer открывает элементы из grid и работает корректно на проверенном устройстве.
- Проверка выполнялась на хорошем интернете; degraded network сценарии еще не проверены.
- Проверка изменяющегося архива еще не выполнялась: не проверены добавление/удаление фото на сервере, изменение `count` у дня и поведение stale cache.

Вывод для MVP-1: при текущих фактах WebDAV fallback не нужен для основного remote-first сценария. Memories API закрывает login-adjacent проверку доступности, timeline, thumbnails и viewer-доступ к media достаточно для продолжения MVP-1 без WebDAV fallback.

## Ключевые endpoint'ы для MVP-1

API base path:

```text
/apps/memories/api
```

Обнаруженные endpoint'ы:

```text
GET  /apps/memories/api/describe
GET  /apps/memories/api/config
GET  /apps/memories/api/days
POST /apps/memories/api/days
GET  /apps/memories/api/days/{id}
GET  /apps/memories/api/image/preview/{id}
POST /apps/memories/api/image/multipreview
GET  /apps/memories/api/image/info/{id}
GET  /apps/memories/api/image/decodable/{id}
GET  /apps/memories/api/stream/{fileid}
GET  /apps/memories/api/video/transcode/{client}/{fileid}/{profile}
GET  /apps/memories/api/video/livephoto/{fileid}
GET  /apps/memories/api/clusters/{backend}
GET  /apps/memories/api/clusters/{backend}/preview
```

Для MVP-1 наиболее важны:

- `/apps/memories/api/describe` - проверка API, версия, base URL, login flow URL.
- `/apps/memories/api/config` - настройки Memories и доступные возможности.
- `/apps/memories/api/days` - структура timeline по дням.
- `/apps/memories/api/days/{id}` - фото/видео конкретного дня.
- `/apps/memories/api/image/preview/{id}` - одиночный thumbnail.
- `/apps/memories/api/image/multipreview` - батчевая загрузка thumbnails.
- `/apps/memories/api/image/info/{id}` - detail metadata.
- `/apps/memories/api/stream/{fileid}` - оригинал/поток файла, если применимо.

## Binary protocol `/image/multipreview`

Источник: локальный clone Memories, `lib/Controller/ImageController.php` и `src/components/frame/XImgWorker.ts`.

Request:

```json
{
  "files": [
    { "fileid": 42, "x": 512, "y": 512, "a": "1", "reqid": 1 }
  ]
}
```

Response имеет `Content-Type: application/octet-stream` и состоит из последовательности блоков:

```text
1 byte    length of JSON header
N bytes   JSON header: {"reqid":1,"len":12345,"type":"image/jpeg"}
len bytes image bytes
```

Сервер может пропустить отдельный файл без error-блока, если preview недоступен или generation не удалась. Клиент должен сопоставлять ответ по `reqid`, а для отсутствующих элементов использовать fallback на одиночный `/image/preview/{id}`.

## Модель данных Memories

Из `src/typings/data.d.ts` видны важные поля `IPhoto`:

- `fileid`;
- `etag`;
- `basename`;
- `mimetype`;
- `flag`;
- `dayid`;
- `w`, `h`;
- `liveid`;
- `shared_by`;
- `isvideo`;
- `video_duration`;
- `isfavorite`;
- `datetaken`;
- `stackraw`;
- `imageInfo`.

NextGallery использует собственную DTO и преобразует её в доменную модель:

```text
RemoteMediaDto -> MediaItem
```

## Native hints из Memories Android

В Memories Android есть локальная Room-сущность `Photo` с полями:

- `local_id`;
- `auid`;
- `buid`;
- `mtime`;
- `date_taken`;
- `dayid`;
- `basename`;
- `bucket_id`;
- `bucket_name`;
- `has_remote`;
- `flag`.

Это подтверждает, что связка local/remote, `dayid`, `has_remote`, AUID/BUID и bucket-понятия важны для будущей синхронизации.

## Риски

- Memories API может быть внутренним API приложения, а не стабильным публичным контрактом.
- Endpoint'ы могут меняться между версиями Memories.
- Проверка с app password на живом сервере прошла успешно для базового MVP-1 flow, но degraded network и изменяющийся архив еще не проверены.
- Нужно проверить версионирование API: есть ли compatibility guarantees.

## Текущее применение

Memories API является основным фотодоменным remote source.

WebDAV fallback не входит в MVP-1, пока нет конкретного пробела Memories API в проверяемом пользовательском сценарии.

Текущая граница источников:

```text
MemoriesRepository -> identified remote projection
LocalMediaSource   -> identified local projection
UnifiedTimelineProjection -> published timeline
```

## Video original: контракт `/stream`

Проверено по checkout Memories `abea4165`: `appinfo/routes.php`,
`lib/Controller/DownloadController.php::one` и `src/services/API.ts::STREAM_FILE`.

- `GET /apps/memories/api/stream/{fileid}` читает исходный файл через user filesystem
  и проверяет право скачивания. `PublicPage` допускает также shared-контекст, но
  не превращает произвольный file ID в публичную ссылку.
- Original поддерживает одиночный `Range: bytes=start-end`, `Accept-Ranges: bytes`
  и `Content-Range`/206 для части файла; ответ на полный файл может быть 200.
- `Content-Type` берётся из файла, `Content-Length` соответствует диапазону.
  `Content-Disposition: attachment` не мешает чтению bytes через Media3.
- Native playback использует существующие app-password headers NextcloudTransport,
  не браузерный request token в URL. Instrumentation fixture проверяет этот
  HTTP-контракт отдельно от доступности личного сервера.

## Video HLS: контракт Memories VOD

Проверено по checkout `abea4165`: `VideoController.php::transcode`,
`src/services/API.ts::VIDEO_TRANSCODE`, `src/components/viewer/PsVideo.ts` и
`go-vod/transcoder/manager.go::ServeIndex`.

- `/config.vod_disable` запрещает VOD. При выключенном VOD native-клиент не
  запрашивает manifest и скрывает quality menu.
- `GET /apps/memories/api/video/transcode/{client}/{fileid}/index.m3u8` возвращает
  master playlist. `client` имеет минимум 8 символов и остаётся одним для текущей
  playback session; native-клиент использует случайный UUID.
- Master содержит `EXT-X-STREAM-INF` с `BANDWIDTH`, `RESOLUTION`, `FRAME-RATE`
  и относительные `{quality}.m3u8`, включая query string. Playlist и сегменты
  проходят через тот же authenticated transport; query сохраняется.
- `Direct` означает исходный `/stream`, `Original` — только обнаруженный
  `max.m3u8`, `Auto` — master playlist. Resolution label равен меньшей стороне
  `RESOLUTION`, в том числе для portrait. Фиксированные профили клиент не создаёт.
- Сервер отклоняет external storage и временные файлы; `vod_disable=false` ещё
  не гарантирует доступность transcode для конкретного файла. Ошибка discovery
  оставляет original без quality menu, ошибка выбранного HLS даёт явный retry.
- При смене процесса transcoder возможен HTTP 409. Native-клиент не создаёт
  бесконечный цикл source fallback; после исчерпания попыток Media3 нужен retry.
- URI master/variants/segments остаются логическими ссылками до запроса.
  Клиент принимает только соседние variants текущего VOD path и отключает
  redirects, чтобы не переносить app credentials на произвольные manifest URL.
