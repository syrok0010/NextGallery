# ADR 0005: Сеть, endpoint-клиенты, DAV XML и загрузка изображений

## Статус

Принято.

## Контекст

Memories сочетает обычные JSON endpoints, бинарный `image/multipreview` и authenticated image streams, а запись файлов, альбомов и удаление идут через Nextcloud WebDAV (включая XML-запросы и ответы `PROPFIND`/`SEARCH`/`multistatus`). Локальные изображения читаются через MediaStore `content://`, а remote requests требуют текущую сохранённую сессию.

## Критерии выбора

- Общие правила URL и Basic Auth не дублируются.
- Сетевой контракт Nextcloud/Memories отделён от продуктовой семантики срезов.
- JSON, бинарные потоки и WebDAV XML используют подходящий уровень abstraction.
- Один и тот же код разбора WebDAV XML работает и на Android, и в JVM-тестах.
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

### Разбор WebDAV XML вручную через DOM

Имена тегов и пространства имён дублируются строками в запросе и разборе, а в JVM-тестах работает Xerces, тогда как на Android — другая реализация DOM.

### `dav4jvm` (DAVx⁵)

Полноценный WebDAV-клиент, но начиная с 3.0 работает только через Ktor и потребовал бы второй HTTP-стек рядом с OkHttp. Мажорные версии с несовместимым API выходят часто, сборки публикуются только через JitPack, а свойства Nextcloud вроде `oc:fileid` всё равно описываются вручную. Приложению нужны точечные операции, а не синхронизация коллекций (файловый клиент — не-цель в `vision.md`).

### Прямые URL и credentials в composable

Просто для одного экрана, но размазывает auth policy и session data по UI.

## Решение

### Транспорт и изображения

- Retrofit + `kotlinx.serialization` используются для Login Flow, OCS и Memories JSON API.
- Raw OkHttp используется для Memories binary endpoints и WebDAV.
- `NextcloudTransport` централизует origin normalization, Basic Auth и request creation.
- Coil 3 загружает изображения в Compose.
- `MediaImageRequestFactory` наблюдает единственный `SessionStore` и строит local, remote и fallback plans без передачи credentials через screen parameters.
- Local assets читаются по `content://`; cache key включает URI, `DATE_MODIFIED` и purpose.
- Для локальной плитки сначала используется системный `loadThumbnail`. Если его разрешения не хватает для `Crop`, still image читается штатным `ImageDecoder` с масштабированием под заполнение и центральной обрезкой до размера плитки (вписывание длинного скриншота целиком давало лишь несколько пикселей по короткой стороне, а увеличение общего thumbnail-бокса расходовало бы память на невидимые области). Видео сохраняет системный thumbnail; обрезанный под узкую ячейку bitmap не переиспользуется для квадратной плитки.
- Remote thumbnails используют `ThumbnailRequest` (`Keyer` → `ThumbnailFileStore` → общий `ThumbnailBatchLoader`), который дедуплицирует запросы и группирует одинаковые размеры в батчи для `image/multipreview`.
- Memories пишет ответ multipreview потоково, поэтому клиент отдаёт и кэширует каждую миниатюру по мере прихода, не дожидаясь остальной пачки.

### Endpoint-клиенты

- `core/network` содержит клиентов, сгруппированных по области протокола: Login Flow и OCS (`auth`, `capabilities`), Memories API (`memories`), DAV альбомов Photos и файлов пользователя (`dav`). Метод клиента соответствует операции endpoint, а не HTTP-глаголу. Возвращает он DTO или результат операции, но не сырой ответ и не продуктовый исход.
- Протокольное значение ответа определяет клиент: `405` на `MKCOL` — альбом существует, `412` на `PUT` с `If-None-Match: *` — файл существует, `OC-FileId` — id созданного файла. Для статуса, у которого нет результата операции, клиент бросает `DavHttpException(code)`. Что ответ значит для продукта, решает срез. Пример — `409` на `COPY` в альбом: Photos отвечает так и на повторный файл, и на отсутствующий альбом, поэтому клиент возвращает неоднозначный `Conflict`, а различает случаи `MemoriesAlbumWriter` (ADR 0015).
- Клиенты не выполняют retry, не читают Room и не публикуют состояния. Mapping в доменные модели, кэш и `OperationStatus` остаются в срезах.
- Приложение однопользовательское, поэтому клиенты — singleton-ы, а DI передаёт им provider активной сессии (`SessionStore::requireCredentials`). Отдельные экземпляры на аккаунт и credentials параметром каждого метода не нужны. Каждый запрос читает provider один раз, так что URL и `Authorization` относятся к одной сессии. Без сессии запрос падает до отправки.
- Исключение — запрос до сохранения сессии, например проверка Memories при входе: credentials ещё не в `SessionStore`. Срез тогда создаёт разовый экземпляр клиента с provider этих credentials (`MemoriesApiClient(transport) { credentials }`), а `NextcloudTransport` получает из DI. URL и разбор ответа остаются в клиенте.

### WebDAV XML (`xmlutil`)

- Тела WebDAV описаны `@Serializable`-моделями **`xmlutil`** поверх `kotlinx.serialization` (`core/network/dav/DavXml.kt`): из одной модели собирается запрос `PROPFIND`/`SEARCH` и разбирается `multistatus`.
- Объявлены только нужные элементы, остальные (`href`, чужие свойства) пропускаются. Значения читаются строками, потому что `propstat` со статусом `404` перечисляет запрошенные свойства пустыми элементами.
- Разбирает XML парсер `xmlutil` на чистом Kotlin, поэтому на Android и в JVM-тестах выполняется один и тот же код. Внешних сущностей парсер не загружает, а ответ с `DOCTYPE` клиент отклоняет явно.
- Версия `xmlutil` закреплена в version catalog на `0.91.3`: ветка `1.0.x` собрана Kotlin 2.4, и компилятор Kotlin 2.2 её не читает. После обновления Kotlin до 2.4 нужно перейти на `1.0`, где удалены API, помеченные устаревшими в `0.91`.

Версии остальных библиотек определяет version catalog.

## Последствия

- UI знает media item и назначение изображения, но не transport URL или credentials.
- Remote preview bytes кешируются отдельно от Room; UI state их не удерживает.
- Local thumbnails не копируются в `ThumbnailFileStore`.
- В проекте остаются два network style: typed Retrofit и raw OkHttp.
- Ключи кэша превью не содержат аккаунта: аккаунт не меняется, а выход стирает кэш вместе с остальными данными приложения и завершает процесс (ADR 0010).
- Порты срезов по-прежнему принимают `AccountCredentials`: срезам нужны адрес сервера и `userId` для DAV-путей. Пока аккаунт один, это та же сессия, из которой читают клиенты; при нескольких аккаунтах решение нужно пересмотреть.

## Открытые вопросы

- Нужен ли fallback на одиночный preview для элементов, пропущенных multipreview.
- Какая eviction policy нужна файловому thumbnail cache.
- Когда понадобится WebDAV fallback.
