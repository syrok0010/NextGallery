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

Даёт Kotlin DSL, явную регистрацию и готовую Compose/ViewModel integration с проверкой явных регистраций Compiler Plugin; runtime-проверки сохраняются для динамических параметров и ручных lookup.

## Решение

Использовать Koin 4.2.2 и Compiler Plugin 1.2.3. `appModule` объединяет bootstrap, authenticated и worker modules через `includes()`. Bootstrap содержит только небольшой pre-login граф. Authenticated scope владеет большим пользовательским графом: Room, upload journal, локальной проекцией, thumbnail-файлами, кэшами, сетевыми клиентами и image/video adapters. Простые определения используют Plugin DSL, платформенные адаптеры — `create(::provider)` с именованными зависимостями. Ручные фабрики остаются для runtime-параметров ViewModel, source credentials и поздних callbacks, предотвращающих циклы.

`allowOverride(false)` запрещает пересечение регистраций разных модулей; замены в изолированных тестовых контейнерах включаются явно. Несколько `OperationStatusSource` имеют типовые qualifiers и ссылаются на исходные singleton-экземпляры; UI собирает их через текущий scope.

Один авторизованный scope принадлежит `AuthenticatedGraph` на уровне процесса (ADR 0006). Compose использует `UnboundKoinScope`, поэтому уход с экрана не закрывает очередь и наблюдателей. WorkerFactory Koin обращается к тому же владельцу, а `UploadWorker` получает queue/transport/client конструктором. Default WorkManagerInitializer удалён, WorkManager настраивается после Koin.

Graph явно отменяет принадлежащий ему CoroutineScope; scoped Coil loader и Room databases закрываются через `onClose` вместе с авторизованным графом. Bootstrap не предоставляет пользовательские storage-зависимости и image loader. В тестах сначала освобождаются ViewModel/композиция и авторизованный граф, затем bootstrap. Убийство процесса не требует выполнения cleanup.

Compile safety и strict safety включены. Плагин проверяет обязательные зависимости и qualifiers provider-параметров, но не доказывает полноту `getAll()` и корректность произвольных ручных `get(named(...))`; это проверяют production composition-тесты. Kotlin 2.4.21 новее upstream-verified 2.4.20 для плагина 1.2.3: предупреждение сохранено, ABI и положительные/отрицательные компиляционные пробы проверены до подключения.

Preview использует отдельный контейнер с fakes, debug/automation-сборки — локальный Koin logger; строковое представление credentials скрывает содержимое, включая source-логи scope. IDE plugin может облегчить навигацию по графу, но не является обязательной частью сборки.

Koin injection в Compose допустим на границе slice или leaf adapter. Промежуточные composable не должны переносить инфраструктурные зависимости только ради нижнего элемента.

## Последствия

- Доменные классы не зависят от DI annotations.
- Большинство отсутствующих зависимостей обнаруживает компилятор; динамические границы проверяются на Android с production definitions.
- Глобальный service locator не используется как замена constructor injection.
- Переход на Hilt возможен, но потребует переписать composition root и ViewModel wiring.
