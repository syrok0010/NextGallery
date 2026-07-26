# ADR 0005: Network и загрузка изображений

## Статус

Принято.

## Контекст

Первый MVP должен проверить Login Flow v2, Memories timeline и просмотр thumbnails/detail preview. API Memories в основном JSON, но часть важных endpoints нестандартна: `image/multipreview` возвращает бинарный поток, а будущие download/upload/WebDAV-сценарии потребуют более низкоуровневого контроля.

## Решение

- JSON serialization: `kotlinx.serialization`.
- JSON HTTP API: Retrofit + OkHttp.
- Special endpoints: raw OkHttp, когда Retrofit начинает мешать.
- Images: Coil 3 + OkHttp network backend.

## Почему так

Retrofit дает читаемые typed interfaces для обычных JSON endpoints:

```text
config
days
days/{ids}
image/info/{id}
Login Flow v2
```

OkHttp остается общим транспортом для auth headers, timeouts, logging и будущих special cases:

```text
image/multipreview
stream/download
WebDAV
```

Coil 3 выбран как Compose-friendly image loader. Каждый tile сразу создаёт стабильный
`ThumbnailRequest`. Custom Coil Fetcher проверяет файловый cache, а одновременные cache miss
объединяет в batch-запросы `image/multipreview`.

Политика auth headers, нормализация server URL и правила сборки request должны жить в одном transport adapter module, чтобы JSON API, binary endpoints и image loading не дублировали transport rules по разным caller'ам.

## Последствия

Плюсы:

- Typed JSON API без ручного boilerplate для каждого запроса.
- Контроль над нестандартными Memories endpoints остается доступным через OkHttp.
- Один HTTP transport можно переиспользовать для auth, logging и будущих cache/interceptors.

Минусы:

- В проекте будут два стиля network-кода: Retrofit interfaces и raw OkHttp clients.
- Нужно дисциплинированно держать общую auth/header/error-handling политику в одном месте.
- Coil 3 на момент выбора используется осознанно как modern stack, несмотря на то что latest artifact сейчас `3.5.0-beta01`.

## Дополнения

- `image/multipreview` реализован как raw OkHttp client, потому что endpoint возвращает custom binary stream, а не JSON.
- `NextcloudTransport` централизует normalized base/origin URL, auth headers и request policy для Retrofit JSON API, raw binary fetch и Coil image requests.
- `ThumbnailPreview.bytes` является только транспортным значением между multipreview parser и
  файловым cache. UI state не удерживает compressed bytes.
- UI state не хранит карту thumbnail-ключей. Tile синхронно строит `ThumbnailRequest` из
  credentials, `fileId`, размера и `etag`; поэтому модель Coil существует уже при первой
  композиции tile.
- `ThumbnailRequest` разделяет идентичность и доступ: `ThumbnailKey` содержит account scope,
  `fileId`, размер и `etag`, а credentials используются только Fetcher для cache miss.
- Custom Coil `Keyer<ThumbnailRequest>` управляет идентичностью decoded memory cache.
  `Fetcher<ThumbnailRequest>` сначала читает файловый cache, затем передаёт miss общему
  `ThumbnailBatchLoader`.
- `ThumbnailBatchLoader` дедуплицирует одновременные запросы, собирает их в течение короткого
  окна 12 мс и ограничивает один вызов `image/multipreview` 12 элементами.
- Coil memory cache ограничен 15% доступной приложению памяти. Файловый cache остаётся отдельным
  cache-first слоем для batch endpoint.

## Не решено здесь

- Secure storage для app password.
- HTTP cache policy для thumbnails/originals.
- Ограничение размера и eviction policy файлового thumbnail cache.
- Политика retry и fallback одиночного preview для partial/failed multipreview.
- WebDAV fallback.
