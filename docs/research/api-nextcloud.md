# Исследование: Nextcloud API

## Роль Nextcloud API

Nextcloud API нужен как фундаментальный слой:

- авторизация;
- получение app password;
- базовая проверка сервера;
- fallback-доступ к файлам;
- загрузка оригиналов;
- WebDAV-операции, если Memories API не покрывает нужный сценарий.

Для фотодоменной модели NextGallery предпочтительно использовать Memories API, если он доступен и стабилен для нужного сценария. Nextcloud/WebDAV остается обязательной базой и fallback.

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

## Безопасность учетных данных

App password нельзя хранить в обычных preferences.

Кандидаты:

- Android Keystore;
- EncryptedSharedPreferences или современная замена из AndroidX Security, если она остается рекомендуемой;
- собственный небольшой `CredentialsStore` за интерфейсом, чтобы позже заменить реализацию.

## Архитектурный вывод

Nextcloud-интеграцию нужно изолировать:

```text
NextcloudAuthClient
NextcloudCapabilitiesClient
NextcloudWebDavClient
```

UI и доменный слой не должны знать про WebDAV XML, OCS envelopes или особенности Login Flow.

## Открытые вопросы

- Какие версии Nextcloud целевые для MVP?
- Нужен ли fallback без Memories уже в MVP-1 или только после первого tracer bullet?
- Как лучше тестировать интеграцию: локальный dev server, личный production-сервер или оба?
