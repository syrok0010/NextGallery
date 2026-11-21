# ADR 0007: Lazy loading timeline через virtual slots

## Статус

Принято.

## Контекст

Memories разделяет timeline index (`/days`) и metadata выбранных дней (`/days/{ids}`). Grid должен быстро получить форму большого архива, а details и thumbnails — загружаться по viewport.

## Критерии выбора

- Index появляется без загрузки всех day details.
- Уже загруженные дни не запрашиваются повторно без причины.
- Ошибка hydration не сбрасывает показанный timeline.
- Metadata loading не управляет lifecycle thumbnail requests.
- Решение сохраняет scroll anchor и работает с unified local/remote projection.

## Альтернативы

### Append-only список

Проще, но не представляет размер архива и хуже поддерживает быстрое перемещение по timeline.

### Paging 3

Даёт готовую paging infrastructure, но Memories использует day index/details, а не page token; adapter добавил бы больше состояния, чем убрал.

## Решение

- `TimelineSnapshot` содержит упорядоченные virtual `MediaSlot` (общий тип слота для `MediaCollectionScreen`, ADR 0001). До загрузки metadata слот остаётся placeholder; материализованный элемент получает ключ по persistent `MediaId`.
- Чистая функция `TimelineSnapshot.daysForSlots()` (`feature/timeline/TimelineViewportDays.kt`) принимает видимый диапазон слотов, расширяет prefetch window и возвращает уникальные `dayId`. `PhotosScreen` передаёт их в `MediaLibraryCoordinator.requestDays()`, который делегирует загрузку отсутствующих дней в `TimelineWorkflow` и публикует обновлённую проекцию в `TimelineProjectionStore` (ADR 0002).
- `TimelineWorkflow` при ручном refresh начинает новое поколение, отменяет прежнюю hydration и явно сбрасывает список failed days.
- Thumbnail lifecycle принадлежит Coil и композиции видимых tiles. Общий fetcher независимо объединяет cache misses в multipreview batches (ADR 0005).
- Warm-start metadata и правила offline materialization определены ADR 0010.

## Последствия

- Grid может показать структуру remote archive сразу после получения index.
- Placeholder имеет позиционный ключ, материализованный тайл — identity key (`MediaId`).
- Ошибка загрузки дня отображается отдельно от уже доступной медиатеки.
- Большой день по-прежнему загружается одним server response.
- Scrollbar navigation и viewer prefetch (`ViewerMediaIndex`, ADR 0001) используют ту же slot coordinate system.

## Открытые вопросы

- Нужна ли отдельная стратегия для дней с тысячами элементов.
