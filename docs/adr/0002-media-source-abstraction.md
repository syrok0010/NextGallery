# ADR 0002: Граница источников и identity медиа

## Статус

Пересмотрено после реализации unified timeline.

## Контекст

NextGallery объединяет Memories metadata и Android MediaStore в один timeline. Общий интерфейс `MediaSource`, задуманный до реализации, оказался слабым seam: источники имеют разный lifecycle, pagination и persistence. Общей должна быть опубликованная доменная модель, а не форма чтения источника.

## Критерии выбора

- Memories DTO и MediaStore rows не попадают в UI.
- Каждый source сохраняет собственный ingestion и lifecycle.
- Объединение источников выполняется в одном месте.
- UI identity не зависит от Memories `fileId` или MediaStore URI.
- Ошибочная эвристика не должна необратимо объединять разные медиа.

## Альтернативы

### Общий `MediaSource`

Упрощает список источников, но вынуждает скрывать несовместимые модели загрузки за широким или условным интерфейсом.

### UI объединяет source-specific списки

Сохраняет adapters простыми, но размазывает дедупликацию, metadata priority и identity по UI.

## Решение

- `MemoriesRepository` и `LocalMediaSource` самостоятельно читают, идентифицируют и сохраняют свои проекции.
- `UnifiedTimelineProjection` объединяет уже идентифицированные local/remote элементы в памяти и не обращается к Room.
- UI получает `MediaItem` с устойчивым `MediaId` и `MediaAssetRef`, описывающим доступные bytes.
- Memories metadata задают каноническое имя, дату и положение объединённого объекта.
- Локальная копия предпочтительна для thumbnail/original; ошибка local URI переключает загрузку на cloud copy без смены `MediaId`.

## Identity

Точные source identifiers и приблизительные aliases сохраняются в Room как отображение на `MediaId`:

- local source: MediaStore URI;
- remote source: Memories `fileId`;
- `AUID = MD5(epoch + size)`;
- `BUID = MD5(basename + "iuid=" + ImageUniqueID)`;
- fallback BUID: `MD5(basename + "size=" + size)`.

Совпадение AUID или BUID объединяет копии, только если найденные aliases ведут к одному `MediaId`. При конфликте копия остаётся отдельной, а конфликт сохраняется для диагностики. При появлении remote copy уже опубликованный local `MediaId` сохраняется.

## Последствия

- Источник можно менять без переписывания UI, пока он публикует доменные элементы.
- Identity восстанавливается до публикации элемента; session-only IDs не используются.
- Эвристика AUID/BUID остаётся приблизительной и в будущем уступает явной sync-связи или content hash.
- Source ingestion сложнее общего интерфейса, но его различия остаются локальными.

## Открытые вопросы

- Как явная upload/download связь и content hash будут разрешать существующие приблизительные aliases?
- Как моделировать live photos и RAW stacks?
