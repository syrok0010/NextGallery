# ADR 0013: Общий индекс медиатеки и проекции коллекций

## Статус

Принято в работе.

## Контекст

Альбомам нужны canonical `MediaItem` и сведения о локальных и облачных копиях. Ранее `AlbumContentsViewModel` читал их через `TimelineScreenState`, из-за чего albums зависели от состояния и модели конкретного экрана timeline. При этом индексация должна продолжаться независимо от того, открыт ли PhotosScreen, и в будущем запускаться из WorkManager или foreground adapter.

## Решение

Индекс медиатеки публикуется отдельным `MediaLibraryIndex`. `MediaLibraryIndexer` запускается из application scope, ограничивает работу текущей session и обновляет индекс независимо от Compose UI. Album contents читает canonical items из индекса.

Timeline получает отдельную `TimelineProjectionStore`: timeline snapshot и lazy hydration остаются projection-specific состоянием и не входят в интерфейс canonical index. `TimelineWorkflow` загружает удалённую структуру и диапазоны, `LocalMediaIndexer` поддерживает локальные metadata, а `MediaLibraryCoordinator` объединяет их через `MediaLibraryProjection` и публикует две независимые проекции. Сетка и viewer получают timeline или album projection в единой модели коллекции.

Индекс содержит metadata и identity медиаобъектов, но не байты изображений и видео. Загрузка bytes остаётся ответственностью image/video loaders.

## Последствия

- Albums больше не зависят от `TimelineScreenState`.
- Индекс можно обновлять без наличия PhotosScreen в композиции.
- WorkManager или foreground service смогут запускать тот же indexer как adapter.
- Lazy hydration timeline принадлежит `TimelineWorkflow`; общий индекс не знает о слотах, viewport и диапазонах timeline.
- Наличие metadata в индексе не является offline mode: bytes могут потребовать сеть.

## Открытые вопросы

- Когда remote album membership станет частью persistent canonical index, а не отдельной projection.
- Какой scheduler использовать для фоновой incremental refresh после появления продуктовой политики синхронизации.
