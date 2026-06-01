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

Coil 3 выбран как Compose-friendly image loader. Для MVP thumbnails грузятся через обычный Memories preview endpoint с Basic Auth headers.

## Последствия

Плюсы:

- Typed JSON API без ручного boilerplate для каждого запроса.
- Контроль над нестандартными Memories endpoints остается доступным через OkHttp.
- Один HTTP transport можно переиспользовать для auth, logging и будущих cache/interceptors.

Минусы:

- В проекте будут два стиля network-кода: Retrofit interfaces и raw OkHttp clients.
- Нужно дисциплинированно держать общую auth/header/error-handling политику в одном месте.
- Coil 3 на момент выбора используется осознанно как modern stack, несмотря на то что latest artifact сейчас `3.5.0-beta01`.

## Не решено здесь

- Secure storage для app password.
- HTTP cache policy для thumbnails/originals.
- Реализация `image/multipreview`.
- WebDAV fallback.
