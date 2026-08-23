# ADR 0004: Dependency Injection

## Статус

Принято.

## Контекст

Auth, network, Room, MediaStore, cache и ViewModel требуют composition root с Android lifecycle integration. Автору проекта близка явная регистрация зависимостей в стиле `Microsoft.Extensions.DependencyInjection`.

## Критерии выбора

- Constructor injection для прикладных классов.
- Явный composition root.
- Интеграция с Compose и ViewModel.
- Минимум framework-аннотаций и generated code.
- Возможность заменить dependency в тесте.

## Альтернативы

### Manual DI

Максимально прозрачен, но с ростом Android lifecycle и ViewModel приводит к собственной container-проводке.

### Hilt

Даёт compile-time graph validation и стандартные Android scopes, но добавляет Dagger ceremony и code generation.

### Koin

Даёт Kotlin DSL, явную регистрацию и готовую Compose/ViewModel integration ценой преимущественно runtime-проверки графа.

## Решение

Использовать Koin DSL. `AppModule` является composition root; ViewModel и инфраструктурные зависимости создаются контейнером, прикладные классы получают зависимости через конструктор.

Koin injection в Compose допустим на границе slice или leaf adapter. Промежуточные composable не должны переносить инфраструктурные зависимости только ради нижнего элемента.

## Последствия

- Доменные классы не зависят от DI annotations.
- Ошибки регистрации могут проявиться в runtime, поэтому composition root должен оставаться небольшим и проверяемым.
- Глобальный service locator не используется как замена constructor injection.
- Переход на Hilt возможен, но потребует переписать composition root и ViewModel wiring.

## Открытые вопросы

- Нужна ли отдельная автоматическая проверка Koin graph при дальнейшем росте module?
