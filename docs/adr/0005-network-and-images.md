# ADR 0005: Network и загрузка изображений

## Статус

Принято.

## Контекст

Memories сочетает обычные JSON endpoints, бинарный `image/multipreview` и authenticated image streams. Локальные изображения читаются через MediaStore `content://`, а remote requests требуют текущую сохранённую сессию.

## Критерии выбора

- Общие правила URL и Basic Auth не дублируются.
- JSON и нестандартные binary endpoints используют подходящий уровень abstraction.
- Compose не переносит credentials через дерево UI.
- Local bytes предпочтительнее сети.
- Одновременные thumbnail misses объединяются.

## Альтернативы

### Только Retrofit

Удобен для JSON, но custom binary framing multipreview и image loading требуют неудобных adapters.

### Только raw OkHttp

Даёт полный контроль, но добавляет ручной mapping для обычных JSON API.

### Прямые URL и credentials в composable

Просто для одного экрана, но размазывает auth policy и session data по UI.

## Решение

- Retrofit + kotlinx.serialization используются для JSON API.
- Raw OkHttp используется для Memories binary endpoints; Login Flow и JSON API используют Retrofit.
- `NextcloudTransport` централизует origin normalization, Basic Auth и request creation.
- Coil 3 загружает изображения в Compose.
- `MediaImageRequestFactory` наблюдает единственный `SessionStore` и строит local, remote и fallback plans без передачи credentials через screen parameters.
- Local assets читаются по `content://`; cache key включает URI, `DATE_MODIFIED` и purpose.
- Remote thumbnails используют `ThumbnailRequest`: `Keyer` задаёт memory identity, `Fetcher` проверяет `ThumbnailFileStore`, затем обращается к общему `ThumbnailBatchLoader`.
- Batch loader дедуплицирует запросы, группирует одинаковые account scope/size в окно 20 мс, ограничивает batch 20 элементами и выполняет не более четырёх batches одновременно.

Версии библиотек определяет version catalog.

## Последствия

- UI знает media item и назначение изображения, но не transport URL или credentials.
- Remote preview bytes кешируются отдельно от Room; UI state их не удерживает.
- Local thumbnails не копируются в `ThumbnailFileStore`.
- В проекте остаются два network style: typed Retrofit и raw OkHttp.
- Single-account не отменяет account scope в cache keys: scope защищает от stale bytes при смене подключения.

## Открытые вопросы

- Нужен ли fallback на одиночный preview для элементов, пропущенных multipreview.
- Какая eviction policy нужна файловому thumbnail cache.
- Когда понадобится WebDAV fallback.
