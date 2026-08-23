# Исследование: Nextcloud API

## Роль Nextcloud API

Nextcloud API нужен как фундаментальный слой:

- авторизация;
- получение app password;
- основа для будущих WebDAV-операций, если Memories API не покрывает нужный сценарий.

Сейчас фотодоменная модель, timeline, thumbnails и originals работают через Memories API. WebDAV fallback не реализован и добавляется только для конкретного пользовательского пробела.

## Login Flow v2

Nextcloud Login Flow v2 должен быть стартовым способом логина.

Ожидаемый результат flow:

- server URL;
- login name;
- app password.

Документация:

- https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html

## WebDAV

WebDAV полезен для:

- доступа к файлам по пути;
- чтения `etag`, `fileid`, mime type, size, mtime;
- скачивания оригиналов;
- будущих операций upload/delete/move;
- fallback-режима без Memories.

Документация:

- https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/basic.html

## OCS и capabilities

OCS/capabilities нужны для определения возможностей сервера и установленных приложений.

Потенциальные вопросы:

- можно ли надежно определить наличие Memories app без запроса к Memories `/api/describe`;
- какие версии Nextcloud поддерживать;
- какие server capabilities влияют на preview/download.

## Безопасность учётных данных

App password хранится через `KeystoreCredentialsStore`: JSON payload шифруется AES/GCM ключом Android Keystore, а callers зависят от интерфейса `CredentialsStore`. Решение и ограничения описаны в ADR 0006.

## Архитектурный вывод

Текущие границы интеграции:

```text
NextcloudLoginRepository -> Login Flow v2
NextcloudTransport       -> URL normalization и Basic Auth requests
MemoriesRepository      -> фотодоменный remote API
```

UI и доменный слой не должны знать про WebDAV XML, OCS envelopes или особенности Login Flow.

## Открытые вопросы

- Какие версии Nextcloud целевые для MVP?
- Как лучше тестировать интеграцию: локальный dev server, личный production-сервер или оба?
