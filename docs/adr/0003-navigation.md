# ADR 0003: Навигация

## Статус

Принято.

## Контекст

Проект стартует на Jetpack Compose. Автор хочет изучать современную Android-разработку и открыт к bleeding edge, если это упрощает жизнь.

Navigation3 уже существует как современный вариант навигации для Compose. По состоянию на 2026-06-02 последняя stable-версия Navigation3 в AndroidX release notes - `1.1.2`; `1.2.0-alpha03` не берем, потому что это alpha.

Release notes:

- https://developer.android.com/jetpack/androidx/releases/navigation3

## Решение

Использовать Navigation3 stable:

```text
androidx.navigation3:navigation3-runtime:1.1.2
androidx.navigation3:navigation3-ui:1.1.2
```

Для MVP использовать `NavKey`, `rememberNavBackStack` и `NavDisplay`. Первый route-набор:

```text
Home
Detail(fileId)
```

## Почему не classic Navigation Compose по умолчанию

Проект учебный и современный. Если Navigation3 уже дает более чистую модель back stack для Compose, есть смысл изучать сразу ее.

Но выбор должен быть инженерным, а не модным: если API мешает быстро собрать tracer bullet, стабильный простой путь важнее.

## Первые экраны

MVP-1:

```text
ConnectServerScreen
LoginProgressScreen
TimelineScreen
MediaDetailScreen
SettingsScreen
```

На текущем tracer bullet `ConnectServerScreen`, `LoginProgressScreen` и `TimelineScreen` живут внутри `Home`, потому что login/timeline зависит от auth state. `MediaDetailScreen` вынесен в отдельный route `Detail(fileId)`.

## Открытые вопросы

- Как Navigation3 лучше хранить состояние detail/timeline scroll position?
- Нужны ли deep links на media item в MVP-1?
