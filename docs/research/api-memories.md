# Исследование: Memories API

## Гипотеза

Memories API должен быть основным API для фотодоменной модели, потому что он уже оперирует timeline, днями, preview, EXIF, альбомами, тегами, people/places и видео как фотопродукт, а не как файловое дерево.

Это выглядит более легким и "воздушным" путем для native-клиента, чем строить всю фотомодель вручную поверх WebDAV.

## Что найдено в локальном clone Memories

Локальный clone: `/home/syrok/AndroidStudioProjects/memories`.

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

- `/api/describe` - проверка API, версия, base URL, login flow URL.
- `/api/config` - настройки Memories и доступные возможности.
- `/api/days` - структура timeline по дням.
- `/api/days/{id}` - фото/видео конкретного дня.
- `/api/image/preview/{id}` - thumbnails.
- `/api/image/info/{id}` - detail metadata.
- `/api/stream/{fileid}` - оригинал/поток файла, если применимо.

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

Для NextGallery это хороший кандидат на входную DTO-модель, но не на доменную модель. Доменная модель должна быть собственной:

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
- Нужно понять, какие заголовки/CSRF/CORS/Nextcloud conventions нужны native-клиенту.
- Нужно проверить, как API ведет себя с app password из Login Flow v2.
- Нужно проверить версионирование API: есть ли compatibility guarantees.

## Решение на сейчас

Для MVP-1 считать Memories API предпочтительным фотодоменным remote source.

При этом код должен быть устроен так, чтобы можно было добавить fallback:

```text
RemoteMediaSource
  MemoriesMediaSource
  NextcloudWebDavMediaSource
```

## Следующие исследовательские задачи

- Вызвать `/apps/memories/api/describe` на личном сервере.
- Вызвать `/apps/memories/api/days` с app password.
- Проверить форму ответа `/api/days` и `/api/days/{id}` на реальном архиве.
- Проверить preview URL и параметры размеров.
- Проверить, нужен ли `OCS-APIRequest` или другие Nextcloud headers.
- Изучить текущий Android-клиент Memories глубже: auth, cache, local media, sync hints.
