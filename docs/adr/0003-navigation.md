# ADR 0003: Навигация

## Статус

Предложено.

## Контекст

Проект стартует на Jetpack Compose. Автор хочет изучать современную Android-разработку и открыт к bleeding edge, если это упрощает жизнь.

Navigation3 уже существует как современный вариант навигации для Compose. По состоянию на 2026-06-01 стабильная ветка Navigation3 есть в AndroidX.

Release notes:

- https://developer.android.com/jetpack/androidx/releases/navigation3

## Решение

Для первого реального UI рассмотреть Navigation3 как предпочтительный вариант, но перед подключением зафиксировать конкретную версию в Gradle и проверить:

- стабильность API;
- качество документации;
- поддержку typed routes/back stack;
- совместимость с текущей версией Compose;
- удобство deep links для будущих share/detail сценариев.

Если Navigation3 создаст лишнее трение на самом первом tracer bullet, допустимо временно начать с простой собственной навигации через sealed routes/screen state и вернуться к Navigation3 отдельным PR.

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

## Открытые вопросы

- Как Navigation3 лучше хранить состояние detail/timeline scroll position?
- Как совместить navigation state с process death restore?
- Нужны ли deep links на media item в MVP-1?
