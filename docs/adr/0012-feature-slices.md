# ADR 0012: Feature-срезы и чистый media module

## Статус

Принято. Конкретизирует ADR 0001 и заменяет прежнее распределение timeline orchestration из ADR 0009.

## Контекст

Общие media/identity типы находились рядом с Memories adapter. Timeline controller изменял состояние через обратный host в ViewModel, а host сам загружал, объединял и публиковал данные. Viewer получал TimelineSnapshot. Эта структура затрудняла проверку настоящего workflow и создавала встречные зависимости при попытке выделить modules.

## Критерии выбора

- Общий язык media не зависит от Android, Room, Retrofit или Compose.
- Один владелец управляет timeline mutations и состояниями источников.
- Feature-specific transport factories и persistence policy принадлежат своим срезам.
- Viewer не зависит от формата загрузки timeline.
- Изменения сохраняют существующие identity, календарное время и viewer transition semantics.

## Альтернативы

1. Только переместить packages: дешевле, но оставляет прежние скрытые contracts и двусторонний host.
2. Сразу создать Android Gradle module для каждого feature и слоя: усиливает изоляцию, но требует преждевременно публиковать Room/DI contracts и усложняет сборку без измеренного выигрыша.
3. Выделить чистый JVM media module, а Android features сначала организовать как срезы с проверяемыми imports и содержательными interfaces.

## Решение

Выбран третий вариант.

`core:media` содержит MediaId, identity engine, ссылки на копии и общую модель отображения. LocalMediaProjection и RemoteMediaProjection проверяют source-only inputs: результат LocalFirst нельзя подать обратно как исходную копию. Дата и remote file ID выводятся из canonical day и asset reference, а не изменяются независимо. Reconciliation result — намеренно доступный contract для Room adapter, а не набор public helpers ради compilation.

В Android-приложении `feature/auth`, `feature/timeline`, `feature/viewer`, `feature/images` владеют поведением своих сценариев. `app/ui` соединяет timeline и viewer; ViewerTimelineBridge адаптирует timeline slots в sequence, которую viewer понимает без TimelineSnapshot. ViewerTimelineIndex хранит обратное соответствие MediaId → slot и prefetch range; viewer сообщает о текущем media через callback и не знает эту политику. Общие UI primitives находятся в `core/ui`.

TimelineWorkflow получает remote-source interface, local flow factory и scope сессии. Только он меняет snapshot и day loading states. ViewModel наблюдает session, создаёт workflow в дочернем scope и переводит независимые source statuses в UiText. Viewport policy стала чистой функцией без обратного host. Refresh начинает новое поколение, отменяет прежнюю hydration и явно сбрасывает failed days.

LoginAttempt выражает взаимоисключающие фазы. Credentials сохраняются вне Main, до публикации SignedIn; ошибка позволяет начать новый Login Flow. Credentials не входят в authenticated screen state.

Общий transport предоставляет HTTP primitives; конкретные Auth/Memories factories принадлежат features. RemoteImageRepository/RemoteImageCache владеют thumbnail loading и bytes. Timeline persistence оставляет у себя metadata и согласованную invalidation при удалении дней. Room schema остаётся общей: её разделение не требуется для разделения feature policy.

## Последствия

- Media contracts и identity tests можно собирать без Android plugin.
- Android features пока не имеют compiler-enforced internal isolation между собой: `verifyFeatureBoundaries` проверяет явные imports core/feature/app и независимость viewer от timeline. Это guardrail, не полный Kotlin dependency analyzer.
- Workflow tests заменяют fake-host orchestration и используют управляемые источники и virtual time.
- Best-effort cache errors не становятся отменой: CancellationException пробрасывается, остальные failures диагностируются без payload и credentials.
- Canonical timeline seconds обозначают календарную координату Memories, а не обязательно UTC capture instant. Raw DATE_TAKEN сохраняется для AUID.
- Время сборки и performance от package extraction не обещаются; для них нужны отдельные замеры.

## Открытые вопросы

- Когда viewer/playback и timeline оправдают отдельные Android Gradle modules; сначала нужны стабильные публичные entry points и schema/DI composition.
- Полный контракт logout и очистки всех cloud operations остаётся в #53 с прежним продуктовым приоритетом. Session scope ограничивает timeline work, но не заменяет обсуждение image-cache lifecycle и cleanup failure.
- Performance очереди thumbnails и полной projection исследуется в #71/#72; этот рефакторинг не требует внедрять incremental index без измерений.
