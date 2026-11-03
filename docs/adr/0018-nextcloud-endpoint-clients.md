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
- существующие cancellation и HTTP-контракты проверяются прежними JVM-тестами.

## Альтернативы

### Один `NextcloudClient`

Он дал бы единую точку создания Retrofit/OkHttp, но смешал бы Login Flow,
Memories JSON, binary preview и Photos DAV. Такой класс быстро стал бы владельцем
чужой прикладной политики.

### Оставить endpoint-интерфейсы в feature-срезах

Это не меняет поведение, но сохраняет дублирование transport wiring и не даёт
явной внутренней библиотеки для повторных endpoint-вызовов.

### Отдельный Gradle-модуль сразу

Модуль усилил бы сборочную изоляцию, но сейчас не отделяет Android runtime и DI
контракт от приложения измеримым образом. Сначала фиксируется package boundary;
вынесение модуля остаётся отдельным решением.

## Решение

В `core/network` выделены три области:

- `auth.NextcloudAuthClient` — Login Flow v2 и OCS current-user;
- `memories.MemoriesApiClient` и `MemoriesVideoClient` — Memories JSON, binary
  multipreview и video manifest/config endpoint'ы;
- `dav.PhotosDavClient` — Photos/WebDAV `MKCOL`, `PROPFIND`, `COPY`, `PUT` и
  `HEAD` с нейтральным `DavResponse`.

`NextcloudTransport` остаётся общим primitive для origin, auth headers, Retrofit и
OkHttp. Клиенты не выполняют retry, не читают Room и не публикуют состояния. Они
возвращают DTO, HTTP-коды и заголовки; feature-код по-прежнему решает, что означает
409, 412, отсутствие preview, ошибка кэша или неуспешный follow-up.

## Последствия

- endpoint wiring и DTO имеют одно место владения и проверяются через существующие
  auth, album, timeline и upload fixtures;
- feature-срезы зависят от узких клиентов, но не от Retrofit-интерфейсов и raw DAV
  request construction;
- `core/network` знает сетевой контракт Memories и Nextcloud, но не знает
  `MediaId`, альбомные исходы, кэш или `OperationStatus`;
- пока сохраняется один Android Gradle project; compiler boundary остаётся
  `verifyFeatureBoundaries`.

## Открытые вопросы

- Нужен ли отдельный Gradle-модуль после стабилизации public entry points и тестовых
  fixtures.
- Какие будущие OCS или WebDAV области оправдают отдельный клиент, а не расширение
  существующего протокольного клиента.
