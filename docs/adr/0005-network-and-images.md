# ADR 0005: Network, endpoint-клиенты и загрузка изображений

## Статус

Принято.

## Контекст

Memories сочетает обычные JSON endpoints, бинарный `image/multipreview` и authenticated image streams. Локальные изображения читаются через MediaStore `content://`, а remote requests требуют текущую сохранённую сессию.

## Критерии выбора

- Общие правила URL и Basic Auth не дублируются.
- Сетевой контракт Nextcloud/Memories отделён от продуктовой семантики срезов.
- JSON и нестандартные binary endpoints используют подходящий уровень abstraction.
- Compose не переносит credentials через дерево UI.
- Local bytes предпочтительнее сети.
- Одновременные thumbnail misses объединяются.

## Альтернативы

### Только Retrofit

Удобен для JSON, но custom binary framing multipreview и image loading требуют неудобных adapters.

### Только raw OkHttp

Даёт полный контроль, но добавляет ручной mapping для обычных JSON API.

### Один универсальный `NextcloudClient`

Дал бы одну точку входа, но смешал бы Login Flow, Memories JSON, binary preview и DAV в одном классе. Такой класс быстро начинает владеть чужой прикладной политикой.

### Endpoint-вызовы внутри срезов

Каждый срез сам собирает запросы и URL и разбирает коды ответов. Правило auth или URL тогда приходится менять в нескольких местах, а граница между протоколом и продуктовой политикой остаётся неявной.

### Прямые URL и credentials в composable

Просто для одного экрана, но размазывает auth policy и session data по UI.

## Решение

- Retrofit + kotlinx.serialization используются для JSON API.
- Raw OkHttp используется для Memories binary endpoints и DAV; Login Flow и JSON API используют Retrofit.
- `NextcloudTransport` централизует origin normalization, Basic Auth и request creation.
- Coil 3 загружает изображения в Compose.
- `MediaImageRequestFactory` наблюдает единственный `SessionStore` и строит local, remote и fallback plans без передачи credentials через screen parameters.
- Local assets читаются по `content://`; cache key включает URI, `DATE_MODIFIED` и purpose.
- Remote thumbnails используют `ThumbnailRequest`: `Keyer` задаёт memory identity, `Fetcher` проверяет `ThumbnailFileStore`, затем обращается к общему `ThumbnailBatchLoader`.
- Batch loader дедуплицирует запросы, группирует одинаковые account scope/size в окно 60 мс, ограничивает batch 20 элементами и выполняет не более четырёх batches одновременно.
- Memories пишет ответ multipreview потоково, поэтому клиент отдаёт preview по мере прихода. Каждая миниатюра сохраняется в кэш и завершает ожидание своего ключа сразу, не дожидаясь остальной пачки.

Версии библиотек определяет version catalog.

### Endpoint-клиенты

- `core/network` содержит клиентов, сгруппированных по области протокола: Login Flow и OCS, Memories API, DAV альбомов Photos, DAV файлов пользователя. Метод клиента соответствует операции endpoint, а не HTTP-глаголу. Возвращает он DTO или результат операции, но не сырой ответ и не продуктовый исход.
- Протокольное значение ответа определяет клиент: 405 на `MKCOL` — альбом существует, 412 на `PUT` с `If-None-Match: *` — файл существует, `OC-FileId` — id созданного файла. Для статуса, у которого нет результата операции, клиент бросает `DavHttpException(code)`. Что ответ значит для продукта, решает срез. Пример — `409` на `COPY` в альбом: Photos отвечает так и на повторный файл, и на отсутствующий альбом, поэтому клиент возвращает неоднозначный `Conflict`, а различает случаи `MemoriesAlbumWriter` (ADR 0015).
- Клиенты не выполняют retry, не читают Room и не публикуют состояния. Mapping в доменные модели, кэш и `OperationStatus` остаются в срезах.
- Приложение однопользовательское, поэтому клиенты — singleton-ы, а DI передаёт им provider активной сессии (`SessionStore::requireCredentials`). Отдельные экземпляры на аккаунт и credentials параметром каждого метода не нужны. Каждый запрос читает provider один раз, так что URL и `Authorization` относятся к одной сессии. Без сессии запрос падает до отправки.
- Исключение — запрос до сохранения сессии, например проверка Memories при входе: credentials ещё не в `SessionStore`. Срез тогда создаёт разовый экземпляр клиента с provider этих credentials (`MemoriesApiClient(transport) { credentials }`), а `NextcloudTransport` получает из DI. URL и разбор ответа остаются в клиенте.

## Последствия

- UI знает media item и назначение изображения, но не transport URL или credentials.
- Remote preview bytes кешируются отдельно от Room; UI state их не удерживает.
- Local thumbnails не копируются в `ThumbnailFileStore`.
- В проекте остаются два network style: typed Retrofit и raw OkHttp.
- Single-account не отменяет account scope в cache keys: scope защищает от stale bytes при смене подключения.
- Запрос, начатый после logout, не уходит со старыми credentials, а завершается ошибкой; классифицирует её срез.
- Порты срезов по-прежнему принимают `AccountCredentials`: срезам нужны account scope кэша и `userId` для DAV-путей. Пока аккаунт один, это та же сессия, из которой читают клиенты; при нескольких аккаунтах решение нужно пересмотреть.

## Открытые вопросы

- Нужен ли fallback на одиночный preview для элементов, пропущенных multipreview.
- Какая eviction policy нужна файловому thumbnail cache.
- Когда понадобится WebDAV fallback.
