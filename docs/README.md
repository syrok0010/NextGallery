# Документация проекта

Документы пока пишутся по-русски, потому что на текущем этапе это рабочие материалы автора проекта. Если проект станет публично активным, можно будет добавить английские версии ключевых документов.

## Продукт

- [Видение](product/vision.md) - что строим, для кого и какие принципы держим.
- [MVP](product/mvp.md) - первый полезный вертикальный сценарий и границы следующих этапов.

## Исследования

- [Nextcloud API](research/api-nextcloud.md) - авторизация, WebDAV, capabilities и fallback-роль Nextcloud.
- [Memories API](research/api-memories.md) - найденные endpoint'ы, модель данных и риски использования Memories как основного фотодоменного API.

## ADR

- [ADR 0001: Базовая архитектура приложения](adr/0001-architecture.md)
- [ADR 0002: Абстракция источников медиа](adr/0002-media-source-abstraction.md)
- [ADR 0003: Навигация](adr/0003-navigation.md)
- [ADR 0004: Dependency Injection](adr/0004-dependency-injection.md)
- [ADR 0005: Network и загрузка изображений](adr/0005-network-and-images.md)
- [ADR 0006: Secure storage для Nextcloud app password](adr/0006-secure-credentials-storage.md)
- [ADR 0007: Lazy loading timeline через virtual slots](adr/0007-timeline-lazy-loading.md)
- [ADR 0008: UI по вертикальным slice](adr/0008-ui-vertical-slices.md)
- [ADR 0009: Типизированный UI session state](adr/0009-typed-ui-session-state.md)

## Ближайшие решения

- Проверить Memories API на личном Nextcloud через app password.
- Решить, нужен ли WebDAV fallback уже в MVP-1.
- После этого начинать tracer bullet: `Login -> describe API -> days -> grid -> detail`.
