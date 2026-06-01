# ADR 0004: Dependency Injection

## Статус

Принято.

## Контекст

Проект будет работать с несколькими источниками данных:

- Memories API;
- Nextcloud Login Flow/WebDAV/OCS;
- локальный кеш;
- Android MediaStore;
- будущий sync engine через WorkManager.

Нужен DI-подход, который не мешает первому tracer bullet, но выдержит рост проекта.

## Критерии выбора

- Хорошая интеграция с Android lifecycle.
- Удобная работа с `ViewModel`.
- Понятное тестирование и замена зависимостей.
- Низкий риск для open-source contributor'ов.
- Хорошая документация и совместимость с современным Android tooling.
- Минимум runtime-магии в критичных местах.
- Похожесть на привычную модель `Microsoft.Extensions.DependencyInjection`: explicit composition root, регистрации зависимостей, constructor injection, минимум framework-аннотаций в доменных классах.

## Альтернативы

### Manual DI

Плюсы:

- Никакой магии.
- Максимально прозрачно для обучения.
- Нет kapt/ksp/codegen-слоя.

Минусы:

- Быстро появляется собственный service locator/container.
- Сложнее lifecycle scopes.
- При росте проекта много ручной проводки.

Вывод: хороший вариант для совсем маленького прототипа, но слабый кандидат для долгоживущего клиента с auth, cache, media sources и sync.

### Koin

Плюсы:

- Очень низкий порог входа.
- Меньше аннотаций и codegen.
- Приятен для быстрого прототипирования.
- Kotlin-first ощущение API.
- Ближе к `Microsoft.Extensions.DependencyInjection`: зависимости явно регистрируются в module DSL, приложение явно запускает container, классы можно держать почти полностью свободными от DI-аннотаций.
- Есть поддержка Android `ViewModel`, Compose и Kotlin Multiplatform.
- У современных версий есть `verify()` и compiler plugin/annotations для усиления проверок графа зависимостей.

Минусы:

- Больше проверок уезжает в runtime, если не включать и дисциплинированно не использовать дополнительные проверки.
- Менее стандартный выбор для Android-проекта, ориентированного на официальные Jetpack-практики.
- Lifecycle-интеграция есть, но Hilt ближе к Android framework classes и официальным гайдам.

Вывод: сильный кандидат, если важнее developer experience, явная композиция приложения и сходство с привычным .NET DI.

### Hilt

Плюсы:

- Официально рекомендуемая DI-библиотека для Android.
- Основан на Dagger: compile-time graph validation, хорошая производительность, меньше runtime-сюрпризов.
- Стандартные Android scopes: `SingletonComponent`, `ActivityRetainedComponent`, `ViewModelComponent`, `ServiceComponent`.
- Хорошо ложится на Compose + `ViewModel`: root `ComponentActivity` как entry point, зависимости приходят в `@HiltViewModel` через constructor injection.
- Хороший выбор для будущего WorkManager/sync слоя.
- Понимаемый выбор для внешних Android contributors.

Минусы:

- Больше ceremony: annotations, modules, generated code.
- Ошибки DI иногда выглядят тяжело для новичка.
- Добавляет build-time/codegen сложность.
- Менее "чистый Kotlin DSL", чем Koin.

Вывод: лучший кандидат, если проект целится в стандартную Android-архитектуру и долгую поддержку.

## Решение

Использовать Koin.

Главная причина: Koin лучше совпадает с developer experience автора проекта, которому близка модель `Microsoft.Extensions.DependencyInjection`.

Для этого проекта важны не только "каноничные Android best practices", но и скорость осознанного входа в Android-разработку, читаемость composition root и удовольствие от поддержки кода. Koin дает более явную и привычную модель регистрации зависимостей, при этом остается зрелым Android/Kotlin DI-решением с поддержкой Compose, `ViewModel` и тестовой проверки конфигурации.

Hilt остается сильной альтернативой, если позже окажется, что проекту важнее максимальное следование official Android path или compile-time graph validation через Dagger.

## Что не делаем сразу

- Не добавляем DI "ради DI" во все классы.
- Не создаем большие Koin module-файлы заранее.
- Не добавляем отдельные Gradle modules только ради архитектурной красоты.
- Не используем global service locator как замену нормальному constructor injection.

Первое подключение Koin должно обслуживать конкретный tracer bullet:

```text
Auth client -> Memories client -> Repository -> ViewModel
```

## Источники

- Android Developers: Dependency injection with Hilt  
  https://developer.android.com/training/dependency-injection/hilt-android
- Android Developers: Manual dependency injection  
  https://developer.android.com/training/dependency-injection/manual
- Koin: Introduction  
  https://insert-koin.io/docs/intro/
- Koin: Android ViewModel  
  https://insert-koin.io/docs/reference/koin-android/viewmodel/
- Koin: Verifying your Koin configuration  
  https://insert-koin.io/docs/reference/koin-test/verify/

## Открытые вопросы

- Подключать ли Koin Annotations/KSP сразу или начать с Koin DSL?
- Подключать Koin сразу перед auth tracer bullet или начать с manual constructor wiring на один экран?
- Какой минимальный тестовый пример добавить вместе с первым Koin module?
