# ADR 0018: Внутренняя библиотека endpoint-клиентов Nextcloud и Memories

## Статус

Принято.

## Контекст

Сетевые вызовы были распределены между вертикальными срезами: Retrofit-интерфейсы
Memories и Login Flow находились рядом с репозиториями, а raw OkHttp-вызовы DAV,
upload и multipreview повторяли сборку запросов. Из-за этого граница между
endpoint-контрактом и политикой сценария была неявной, а изменение общего auth или
URL-правила требовало искать вызовы по нескольким feature-пакетам.

При этом вертикальные срезы должны сохранять orchestration и прикладную семантику:
сопоставление identity, классификацию конфликтов COPY/412, retry, кэш и
`OperationStatus` нельзя превращать в общий Nextcloud-сервис.

## Критерии выбора

- каждый клиент группирует вызовы по устойчивой области протокола;
- методы клиента соответствуют endpoint-вызовам и возвращают сетевые DTO или raw
  HTTP-результат без решения о продуктовом результате;
- transport, Basic Auth и нормализация origin остаются общими механизмами;
- feature-срезы сохраняют mapping, orchestration, retry, кэш и UI-состояния;
- сигнатуры методов содержат только параметры endpoint, без auth-деталей;
- существующие cancellation и HTTP-контракты проверяются прежними JVM-тестами.

## Альтернативы

### Один `NextcloudClient`

Он дал бы единую точку создания Retrofit/OkHttp, но смешал бы Login Flow,
Memories JSON, binary preview и Photos DAV. Такой класс быстро стал бы владельцем
чужой прикладной политики.

### Оставить endpoint-интерфейсы в feature-срезах

Это не меняет поведение, но сохраняет дублирование transport wiring и не даёт
явной внутренней библиотеки для повторных endpoint-вызовов.

### Credentials параметром каждого метода

Так было в первой версии библиотеки. Каждый call site протаскивал
`AccountCredentials`, хотя приложение однопользовательское и сессия одна. Сигнатуры
смешивали endpoint-параметры с auth, а Login Flow принимал готовый `Authorization`
header, то есть Basic Auth собирал вызывающий срез.

### Экземпляр клиента или фабрика на аккаунт

Это было бы оправдано при нескольких одновременных аккаунтах. Для single-user
приложения это лишние сущности, и отменять операции при logout всё равно должны
срезы.

### DAV-клиент с методами-глаголами

Первая версия выставляла `mkcol`, `propfind`, `copy`, `put`, `head` и сырой
`DavResponse`. Из-за этого срезы собирали DAV URL сами и разбирали коды и заголовки
протокола. К тому же PUT и HEAD обращаются не к Photos, а к файлам пользователя.

### Отдельный Gradle-модуль сразу

Модуль усилил бы сборочную изоляцию, но сейчас не отделяет Android runtime и DI
контракт от приложения измеримым образом. Сначала фиксируется package boundary;
вынесение модуля остаётся отдельным решением.

## Решение

В `core/network` выделены три области:

- `auth.NextcloudAuthClient` — Login Flow v2 и OCS current-user;
- `memories.MemoriesApiClient` и `MemoriesVideoClient` — Memories JSON, binary
  multipreview и video manifest/config endpoint'ы;
- `dav.PhotosAlbumsDavClient` — альбомы Photos в `remote.php/dav/photos`:
  создание, проверка существования, добавление файла;
- `dav.FilesDavClient` — файлы пользователя в `remote.php/dav/files`: создание без
  перезаписи и размер.

Методы называются по операциям endpoint, а не по HTTP-глаголам. Клиент сам строит
URL из `DavFilePath`/`AlbumPath` и переводит протокольные статусы в результат
операции: 405 на MKCOL — `AlreadyExists`, 412 на PUT с `If-None-Match: *` —
`AlreadyExists`, `OC-FileId` — id созданного файла. Статус, для которого у операции
нет результата, — `DavHttpException(code)`. 409 на COPY в альбом остаётся
неоднозначным `Conflict`: Photos отвечает так и на повторный файл, и на
отсутствующий альбом, и различать их — дело среза.

Multipreview клиент разбирает сам и отдаёт `Flow<MemoriesPreviewDto>`, читая блоки
из тела ответа по мере прихода. Memories пишет ответ потоково
(`ImageController::multipreview`), поэтому срез сохраняет и отдаёт каждую миниатюру
сразу, не дожидаясь остальной пачки. Чтение блока блокирует IO-поток, поэтому
отмена срабатывает между блоками.

`MemoriesApiClient`, `PhotosAlbumsDavClient` и `FilesDavClient` — singleton-ы. DI передаёт им provider
текущих credentials (`SessionStore::requireCredentials`), а Koin внутри клиентов не
используется. Каждый HTTP-вызов читает provider один раз: URL и `Authorization`
одного запроса всегда относятся к одной сессии. Без активной сессии вызов падает
с `IllegalStateException` до отправки запроса.

`NextcloudAuthClient` работает до появления сессии. Server URL он получает из
ввода пользователя, poll endpoint вместе с token — из ответа `startLogin`. Basic
Auth для OCS current-user клиент строит сам из ответа poll.

`NextcloudTransport` остаётся общим primitive для origin, auth headers, Retrofit и
OkHttp. Клиенты не выполняют retry, не читают Room и не публикуют состояния. Они
возвращают DTO и результаты операций; feature-код решает, что существующий файл того
же размера считается загруженным, как подтвердить конфликт в альбоме, что означают
отсутствие preview, ошибка кэша или неуспешный follow-up.

## Последствия

- endpoint wiring и DTO имеют одно место владения и проверяются через существующие
  auth, album, timeline и upload fixtures;
- feature-срезы зависят от узких клиентов, но не от Retrofit-интерфейсов и raw DAV
  request construction;
- `core/network` знает сетевой контракт Memories и Nextcloud, но не знает
  `MediaId`, альбомные исходы, кэш или `OperationStatus`;
- порты срезов (`RemoteAlbumSource`, `RemoteTimelineSource`, `AlbumContentsSource`)
  по-прежнему принимают `AccountCredentials`: срезы используют их для account scope
  кэша и DAV-путей с `userId`. Значения совпадают с сессией, из которой читают
  клиенты, пока приложение однопользовательское; при появлении нескольких аккаунтов
  это решение нужно пересмотреть;
- запрос, начатый после logout, не уходит со старыми credentials, а завершается
  ошибкой; классифицировать её остаётся задачей среза;
- пока сохраняется один Android Gradle project; compiler boundary остаётся
  `verifyFeatureBoundaries`.

## Открытые вопросы

- Нужен ли отдельный Gradle-модуль после стабилизации public entry points и тестовых
  fixtures.
- Какие будущие OCS или WebDAV области оправдают отдельный клиент, а не расширение
  существующего протокольного клиента.
