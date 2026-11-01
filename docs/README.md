# Документация NextGallery

Документы пока пишутся по-русски, потому что на текущем этапе это рабочие материалы автора проекта. Если проект станет публично активным, можно будет добавить английские версии ключевых документов.

Словарь доменных терминов находится в [CONTEXT.md](../CONTEXT.md), пользовательское описание — в [README.md](../README.md).

## Исследования

- [Nextcloud API](research/api-nextcloud.md) - авторизация, WebDAV, capabilities и fallback-роль Nextcloud.
- [Memories API](research/api-memories.md) - найденные endpoint'ы, модель данных и риски использования Memories как основного фотодоменного API.
- [Холодные замеры HLS для ленты кадров](research/filmstrip-cold-hls.md) - сравнение original и HLS-транскодирования при извлечении кадров.

## ADR

- [ADR 0001: Целевая архитектура, вертикальные срезы и границы модулей](adr/0001-architecture.md)
- [ADR 0002: Медиатека: источники, индекс и identity медиа](adr/0002-media-source-abstraction.md)
- [ADR 0003: Навигация](adr/0003-navigation.md)
- [ADR 0004: Dependency Injection](adr/0004-dependency-injection.md)
- [ADR 0005: Сеть, endpoint-клиенты, DAV XML и загрузка изображений](adr/0005-network-and-images.md)
- [ADR 0006: Сессия, вход и хранение credentials](adr/0006-secure-credentials-storage.md)
- [ADR 0007: Lazy loading timeline через virtual slots](adr/0007-timeline-lazy-loading.md)
- [ADR 0010: Warm-start cache policy](adr/0010-mvp-cache-policy.md)
- [ADR 0011: Стек просмотра фото и видео](adr/0011-photo-viewer-stack.md)
- [ADR 0014: Ручная загрузка в облако: размещение и передача](adr/0014-timeline-upload.md)
- [ADR 0019: Журнал ручных загрузок и выполнение через WorkManager](adr/0019-durable-manual-uploads.md)
- [ADR 0021: Английский и русский языки приложения](adr/0021-app-languages.md)
- [ADR 0022: Облачное избранное](adr/0022-cloud-favorites.md)
