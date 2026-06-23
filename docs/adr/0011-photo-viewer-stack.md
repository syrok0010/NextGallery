# ADR 0011: Стек просмотра фото

## Статус

Принято.

## Контекст

MVP-1 должен довести detail viewer до базово хорошего уровня: открывать фото в полном разрешении, поддерживать Ultra HDR still images best-effort, закрываться свайпом вниз, иметь переход из сетки и листаться влево-вправо.

Текущий detail route показывает одиночный `MediaItem` через `1600x1600` detail preview и не поддерживает full-resolution original, pager, zoom/subsampling, HDR-aware window mode или gesture dismiss.

Просмотрщик фото легко превращается в отдельный сложный движок: pinch zoom, pan, double tap, bounds, fling, nested gestures с pager, memory pressure на больших изображениях, subsampling, placeholders и HDR. Для MVP это не область, где проекту полезно писать собственную реализацию с нуля.

## Критерии выбора

- Использовать готовые решения там, где они закрывают зрелую инфраструктуру просмотра изображений.
- Не писать собственный image gesture/subsampling engine.
- Сохранить Compose-first UI stack.
- Поддержать full-resolution original без удержания большого количества оригиналов в памяти.
- Сохранить возможность показывать detail preview как быстрый placeholder.
- Сделать горизонтальное листание и переход из сетки совместимыми с текущей Navigation3/Compose архитектурой.
- HDR поддерживать как best-effort Ultra HDR still images, без обещания HDR-видео в MVP.

## Альтернативы

### Ручной Compose viewer

Плюсы:

- Полный контроль над gesture arbitration, анимациями и состоянием.
- Нет новой third-party зависимости.

Минусы:

- Нужно самостоятельно реализовать zoom/pan/fling/bounds/double tap.
- Нужно решать subsampling больших изображений и memory pressure.
- Высокий риск получить хрупкое поведение при сочетании zoom, horizontal pager и swipe-down dismiss.
- HDR и full-resolution original усложняют декодирование и lifecycle.

Вывод: не подходит для MVP, потому что это большой объем недифференцирующей инфраструктуры.

### Только Coil AsyncImage

Плюсы:

- Уже используется в проекте.
- Простая интеграция с authenticated image requests.

Минусы:

- Не дает полноценный media viewer UX: zoom/pan/subsampling/gesture coordination остаются на проекте.
- Большие originals могут создать проблемы памяти без отдельного решения.

Вывод: оставить Coil как image loading backend, но не использовать простой `AsyncImage` как основу viewer.

### Telephoto + official Compose APIs

Плюсы:

- Telephoto `zoomable-image-coil3` дает готовый Compose image viewer поверх Coil 3 с pan/zoom gestures, subsampling больших изображений, nested scrolling, state restoration и заявленной поддержкой HDR images.
- `HorizontalPager` из Compose Foundation дает официальный lazy pager для листания влево-вправо.
- `SharedTransitionLayout` / `Modifier.sharedElement` дают официальный путь для перехода grid -> viewer.
- Собственный код остается thin glue layer: authenticated original request, placeholder, route state, dismiss coordination, HDR window mode.

Минусы:

- Появляется новая third-party зависимость.
- Gesture arbitration между zoom, horizontal pager и swipe-down dismiss все равно нужно проверить на устройстве.
- HDR поведение зависит от Android version, устройства и того, сохраняет ли original файл Ultra HDR gain map.

Вывод: выбрать этот путь.

## Решение

Для MVP viewer использовать:

- Telephoto `me.saket.telephoto:zoomable-image-coil3` как основу full-resolution image viewer.
- Coil 3 как image loading backend для authenticated original/stream request.
- Compose Foundation `HorizontalPager` для листания влево-вправо.
- Compose shared element transitions для перехода grid -> viewer.
- Route просмотра оставить адресованным по `fileId`; порядок pager брать из текущего timeline snapshot как viewer sequence.
- При приближении к краям загруженной sequence использовать существующую lazy loading механику timeline metadata.
- Видео включать в viewer sequence, но в MVP показывать как static preview/first-frame placeholder без проигрывателя.
- Не делать явный prefetch originals для соседних страниц; full-resolution original грузится только для текущей страницы viewer.
- Для still images original source брать из Memories `/apps/memories/api/stream/{fileId}` и строить authenticated request из `MediaItem.assetRef`, а не хранить transport URL в domain модели.
- WebDAV/download fallback для original source не добавлять в этот срез.
- Swipe-down dismiss не затемняет фон: пользователь двигает viewer surface пальцем, а после отпускания поверхность уменьшается и возвращается shared transition'ом в соответствующий tile сетки.
- Если swipe-down не проходит threshold по смещению или velocity, viewer surface возвращается в полноэкранное положение.
- Перед return transition timeline grid должен прокрутиться к текущему media item, чтобы viewer возвращался в видимый target tile.
- Переход grid -> viewer и viewer -> grid должен быть симметричным shared transition по ключу текущего media item.
- Внутри viewer surface thumbnail/detail preview может быть заменен full-resolution original после загрузки.
- Predictive back gesture в viewer должен использовать тот же return-to-grid target, что и swipe-down dismiss. Для кастомной Compose-анимации использовать AndroidX `PredictiveBackHandler`, который отдает progress gesture.
- Viewer chrome не участвует в shared transition: при swipe-down dismiss или predictive back progress скрывается, при отмене жеста возвращается.
- Небольшой собственный слой только для:
  - построения authenticated original request из source asset reference;
  - preview placeholder до загрузки original;
  - swipe-down dismiss;
  - predictive back progress для return-to-grid;
  - прокрутки timeline grid к текущему item перед return transition;
  - переключения HDR window mode при отображении Ultra HDR still image;
  - координации загрузки соседних элементов.

## Последствия

- Проект не тратит MVP-время на собственный image engine.
- Viewer получает более надежный фундамент для больших originals и zoom.
- Новая зависимость должна быть ограничена `ui/detail` slice и Gradle catalog.
- Нужно явно тестировать gesture conflicts: zoomed image pan vs horizontal pager vs swipe-down dismiss.
- Timeline grid scroll state придется сделать управляемым для return transition к target tile.
- HDR остается best-effort: без gain map, без Android 14+ или без HDR-capable output viewer показывает SDR.

## Открытые вопросы
