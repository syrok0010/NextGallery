# ADR 0002: Абстракция источников медиа

## Статус

Принято.

## Контекст

Финальная идея проекта включает удаленные фото из Nextcloud/Memories, локальные фото на устройстве и синхронизацию между ними.

Первый MVP при этом должен быть проще: remote-first timeline без полноценной локальной синхронизации.

Есть риск написать первый UI напрямую под Memories responses и потом переписывать его при добавлении MediaStore или WebDAV fallback.

## Решение

Сразу ввести доменную абстракцию источника медиа:

```text
interface MediaSource
```

На старте реализовать:

```text
MemoriesMediaSource
```

Позже добавить:

```text
LocalMediaSource
NextcloudWebDavMediaSource
```

Repository должен отдавать приложению доменные модели:

```text
MediaItem
MediaDay
MediaTimeline
MediaAsset
```

Memories DTO, WebDAV XML/props и MediaStore cursor rows не должны попадать в UI.

## Начальная доменная модель

Минимальная модель для MVP-1:

```text
MediaItem
  id
  source
  remoteFileId
  displayName
  mimeType
  width
  height
  takenAt
  duration
  isVideo
  isFavorite
  etag

MediaDay
  dayId
  date
  count
```

Поля можно расширять только после появления реального API ответа и UI-сценария.

## Почему не использовать напрямую Memories `IPhoto`

`IPhoto` уже близок к нужной модели, но это контракт конкретного server app/web UI.

Если использовать его напрямую:

- UI станет зависеть от Memories;
- сложнее добавить local media;
- сложнее добавить WebDAV fallback;
- сложнее тестировать domain logic.

## Последствия

Плюсы:

- MVP остается простым, но не тупиковым.
- Можно развивать единый timeline.
- Можно отдельно тестировать mapping.

Минусы:

- На старте нужен mapping слой.
- Есть риск преждевременно раздуть доменную модель. Правило: добавлять только поля, которые нужны текущему сценарию или уже точно есть в API response.

## Открытые вопросы

- Какой identity использовать для склейки local/remote: `fileid`, `etag`, `auid`, `buid`, hash, путь?
- Где хранить source-specific metadata?
- Как моделировать live photos и RAW stacks?
