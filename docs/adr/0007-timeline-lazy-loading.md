# ADR 0007: Lazy loading timeline через virtual slots

## Статус

Принято.

## Контекст

Memories API разделяет timeline на два уровня:

```text
GET /apps/memories/api/days       -> индекс дней: dayid, count
GET /apps/memories/api/days/{ids} -> metadata элементов выбранных дней
```

Галерея должна быстро показывать структуру сетки при скролле, а metadata и thumbnails могут догружаться позже. Текущая реализация грубо загружала первые N дней сразу, из-за чего начальная загрузка зависела от количества элементов в первых днях и не использовала `/days` как полноценный индекс.

## Критерии выбора

- Сетка должна появляться быстро после загрузки `/days`.
- Повторно загружать уже загруженные дни нельзя без необходимости.
- Ошибка дозагрузки не должна ломать уже показанную timeline.
- Решение должно оставаться понятным без Paging 3 и Room, пока cache/offline policy не выбрана.
- Архитектура должна оставить путь к thumbnail batching и metadata cache.

## Альтернативы

### Append-only загрузка дней

Загружать несколько дней, добавлять реальные `MediaItem` в конец списка и при скролле догружать следующие.

Плюсы:

- Проще состояние.
- Меньше placeholder-логики.

Минусы:

- Сетка растет кусками и хуже похожа на привычную галерею.
- Пользователь не видит структуру архива до загрузки details.
- Scroll position хуже отражает размер timeline.

Вывод: слишком упрощает UX для галереи.

### Paging 3

Использовать Jetpack Paging как источник данных для grid.

Плюсы:

- Готовая инфраструктура paging/loading/error.
- Хорошая интеграция с Compose.

Минусы:

- Memories API не page-number/page-token, а day-index + day-details.
- Понадобится адаптерный слой, который будет сложнее текущего домена.
- Рано тащить Paging до решения cache/offline policy.

Вывод: отложить до появления реальной потребности.

### Virtual slots по `/days`

После `/days` построить `TimelineSlot` для каждого ожидаемого элемента. Пока metadata нет, slot рисуется как placeholder. При загрузке `/days/{ids}` slot получает `MediaItem`.

Плюсы:

- Grid появляется почти сразу после индекса дней.
- Metadata loading отделен от thumbnail loading.
- Ошибка дозагрузки не сбрасывает уже показанную сетку.
- Есть естественная единица будущего cache: day details.

Минусы:

- State сложнее, чем простой `List<MediaItem>`.
- Для очень больших архивов создается много slot-объектов.
- Если один день содержит тысячи элементов, `/days/{id}` все равно возвращает большой ответ.
- Detail screen может открывать только уже загруженный `MediaItem`.

## Решение

Использовать virtual slots:

```text
/days -> TimelineDay(dayId, count) -> TimelineSlot(dayId, indexInDay, mediaItem = null)
/days/{ids} -> MediaItem -> заполнение slots выбранных дней
```

`MainViewModel` держит:

- `TimelineSnapshot`;
- `timelineLoadingDayIds`;
- `timelineFailedDayIds`;
- `timelineLoadMoreError`.

UI через `LazyGridState` сообщает видимый диапазон slot indexes. ViewModel расширяет диапазон prefetch window, выбирает еще не загруженные и не загружаемые day ids, затем грузит небольшой batch.

## Последствия

- `/days` становится индексом timeline.
- Начальная загрузка больше не загружает первые N дней сразу.
- Grid показывает placeholders до прихода metadata.
- Placeholder имеет позиционный ключ `dayId/indexInDay`; после гидратации tile адресуется по
  persistent `MediaId`.
- Повторная загрузка day details блокируется через `loadedDayIds` и `timelineLoadingDayIds`.
- При ошибке дозагрузки main timeline остается на экране, ошибка показывается отдельно.

## Открытые вопросы

- Нужен ли лимит/особая стратегия для дней с тысячами элементов.
- Когда подключать metadata cache.

## Дополнение: граница загрузки thumbnail

Viewport-controller загружает только day details. Thumbnail-запрос запускает Coil при композиции
tile в `LazyGrid`; общий Fetcher объединяет близкие по времени cache miss в
`image/multipreview`. Поэтому состав видимых элементов остаётся ответственностью LazyGrid, а
batching транспортного endpoint не требует отдельной карты thumbnail-состояния во ViewModel.
