# ADR 0001: Целевая архитектура, вертикальные срезы и границы модулей

## Статус

Принято.

## Контекст

NextGallery объединяет несколько развивающихся пользовательских сценариев: авторизацию, индексацию медиатеки, timeline, альбомы, viewer, ручную загрузку и удаление. Нужна архитектура с локальностью изменений, понятными границами владения и изоляцией Android/API деталей, не превращающаяся в набор формальных слоёв и однооперационных классов. Общие экраны коллекций (таймлайн и альбомы), viewer и статус операций не должны обрастать прямыми зависимостями на конкретные фичи.

## Критерии выбора

- Однонаправленный поток состояния и событий UI.
- Пользовательский сценарий можно понять и изменить end-to-end внутри одного среза.
- Общий язык медиа и доменные правила тестируются без Compose, Android runtime и сети.
- Интеграции с сервером, MediaStore и persistence не проникают в UI.
- Абстракции и Gradle modules вводятся ради самостоятельной политики или lifecycle, а не ради симметрии структуры.
- Viewer, общая сетка коллекций и статус операций не зависят от внутренних реализаций конкретных фич.

## Альтернативы

### Группировка в первую очередь по техническим слоям

Единообразно раскладывает файлы по `screens`, `viewmodels`, `repositories` и `usecases`, но распределяет один пользовательский сценарий между удалёнными частями проекта.

### Формальная Clean Architecture для каждой операции

Даёт одинаковую цепочку зависимостей, но создаёт shallow interfaces и use cases, которые только делегируют вызов и не скрывают решения.

### Один общий UI coordinator

Упрощает начальную сборку приложения, но связывает состояния и lifecycle независимых сценариев.

### Отдельный Android Gradle module для каждого feature с самого начала

Усиливает изоляцию на уровне компилятора, но требует преждевременно публиковать Room/DI contracts и усложняет сборку без измеренного выигрыша. Выбран чистый JVM-модуль `:core:media` плюс проверяемые import-границы между срезами в `:app`.

### Фиче-специфичные колбэки и прямые зависимости в общей сетке и статусе операций

Расширяют общие компоненты при каждом новом сценарии и связывают `app/ui` напрямую с конкретными классами фич.

## Решение

### Стиль и направление зависимостей

Использовать современный Android-стек с declarative UI и reactive state: Kotlin, Jetpack Compose, Coroutines/Flow, lifecycle-aware state holders, dependency injection и локальное persistence.

Организовывать прикладной код вертикальными срезами по пользовательским сценариям. Срез end-to-end владеет своим UI, состоянием, orchestration, прикладными правилами и feature-specific адаптерами. Внутреннее деление среза на `ui`, `domain` или `data` допустимо, но не обязательно.

Внутри приложения сохранять направленность зависимостей:

```text
UI -> state holder -> feature policy -> feature adapter -> platform or server
```

Use case вводится, когда скрывает политику или координирует несколько зависимостей; отдельный Gradle module — когда даёт измеримую независимость сборки, тестирования или владения.

### Модули, срезы и проверка границ

- **`:core:media`** — чистый JVM Gradle module без зависимостей на Android, Room, Retrofit и Compose. Содержит `MediaId`, `MediaItem`, identity engine (`reconcileMediaIdentities`), ссылки на копии (`MediaAssetRef`) и общую модель отображения. `LocalMediaProjection` и `RemoteMediaProjection` проверяют source-only inputs: результат `LocalFirst` нельзя подать обратно как исходную копию.
- **`core/*` в `:app`** (`core/session`, `core/network`, `core/database`, `core/operation`, `core/ui`) — остаётся небольшим и содержит только устойчивый язык и общие механизмы, которыми владеют несколько срезов. Общая Room schema (`NextGalleryDatabase`) обслуживает кэш-проекции (ADR 0010); журнал загрузок (`upload-journal.db`) принадлежит `feature/upload` (ADR 0019).
- **`feature/*` в `:app`** (`feature/auth`, `feature/library`, `feature/timeline`, `feature/collection`, `feature/viewer`, `feature/albums`, `feature/upload`, `feature/deletion`, `feature/images`) — владеют поведением своих сценариев.
- **`app/*` в `:app`** (`app/ui`, `app/library`, `di`) — composition root, корневая навигация, `MediaLibraryCoordinator` / `TimelineProjectionStore` (ADR 0002) и связывание экранов со срезами.

Задача Gradle **`verifyFeatureBoundaries`** (включена в `check` и CI) проверяет инварианты зависимостей по импортам:

- `core.*` не зависит от `feature.*` и `app.*`;
- `feature.*` не зависит от `app.*`;
- `feature.viewer` не зависит от `feature.timeline`;
- `feature.upload` не зависит от `feature.albums`.

### Межсценарные контракты

- **Общая сетка и viewer:** таймлайн и содержимое альбома используют общую сетку `MediaCollectionScreen` (`feature/collection`), принимающую упорядоченные `MediaSlot`. `ViewerSequenceController` и `ViewerMediaIndex` (`ViewerMediaBridge.kt`) адаптируют `MediaSlot` в `ViewerSequence` и prefetch range, чтобы `feature/viewer` не знал о `TimelineSnapshot` и политике догрузки.
- **Действия выбора (`SelectionAction`):** `MediaCollectionScreen` и viewer ленты принимают список абстрактных действий вместо именованных колбэков под загрузку, альбомы и удаление; конкретные действия и диалоги подтверждения собирает экран-владелец (`PhotosScreen`).
- **Статус долгих операций (`OperationStatusSource`):** долгие операции (`upload`, `albums`, `deletion`) публикуют снимки состояния через общий интерфейс `core/operation/OperationStatusSource` и биндятся в Koin; `app/ui` (`OperationStatus`) собирает все источники через `getKoin().getAll<OperationStatusSource>()` без прямой зависимости на классы фич.

## Последствия

- Изменения сценария в основном остаются внутри его среза от UI до integration boundary.
- `:core:media` и identity-тесты собираются и выполняются на JVM без Android plugin.
- `verifyFeatureBoundaries` является import-guardrail, а не полноценным compiler-enforced boundary между всеми feature-пакетами.
- Best-effort ошибки кэша не превращаются в отмену корутин: `CancellationException` всегда пробрасывается, остальные failures диагностируются без payload и credentials.
- Локальное дублирование предпочтительнее преждевременного общего слоя; код переносится в `core` после появления устойчивой общей ответственности.

## Открытые вопросы

- Какие срезы первыми оправдают отдельные Android Gradle modules временем сборки, изоляцией тестов или самостоятельным lifecycle?
