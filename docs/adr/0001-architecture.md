# ADR 0001: Базовая архитектура приложения

## Статус

Принято.

## Контекст

Проект начинается как пустой Android Compose проект. Цель автора - изучить современную Android-разработку и при этом заложить основу для реального open-source клиента Nextcloud/Memories.

Автор уже знаком с WPF, Angular, React и реактивным программированием. Поэтому архитектура должна использовать знакомые идеи однонаправленного потока данных, явного состояния и изолированных side effects.

## Решение

Использовать стандартную современную Android-архитектуру:

```text
UI layer
  Compose screens
  ViewModel / screen state holders
  immutable UI state
  user events

Domain layer
  use cases там, где они реально упрощают сценарий
  domain models

Data layer
  repositories
  remote/local data sources
  DTO mapping
  persistence/cache
```

Базовый поток:

```text
Composable -> ViewModel event -> Repository -> DataSource -> DTO
DTO -> mapper -> domain model -> UI state -> Composable
```

## Технические предпочтения

- Kotlin.
- Jetpack Compose.
- Coroutines + Flow.
- ViewModel.
- Dependency Injection через Koin; решение зафиксировано в ADR 0004.
- Room для локального кеша, когда появится необходимость.
- WorkManager для фоновой синхронизации, но не в MVP-1.

## Почему так

- Это совпадает с официальными Android best practices.
- Это хорошо ложится на опыт React/Angular/WPF: state, events, reducers/mappers, side effects.
- Это позволяет начать с простого tracer bullet, не закрывая путь к sync engine.
- Это не привязывает UI к конкретному серверному API.

## Последствия

Плюсы:

- UI можно строить поверх стабильной доменной модели.
- Memories API можно заменить или дополнить WebDAV fallback.
- Local MediaStore можно добавить как еще один source.
- Состояния загрузки, ошибок и пустых экранов будут явными.

Минусы:

- На старте будет больше файлов, чем в минимальном демо.
- Нужно дисциплинированно не превращать use cases в пустые прокладки.
- Нужно сразу договориться о границах между DTO, domain и UI models.

## Открытые вопросы

- Нужен ли отдельный domain module в Gradle или достаточно package-level границ на старте?
- Какой минимальный набор тестов добавить вместе с первым repository?
