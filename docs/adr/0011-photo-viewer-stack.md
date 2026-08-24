# ADR 0011: Стек просмотра фото

## Статус

Принято.

## Контекст

Viewer должен показывать full-resolution still images, поддерживать zoom/subsampling и best-effort Ultra HDR, листать живую sequence и возвращать текущую surface в соответствующий tile. Собственный image gesture engine не является ценностью продукта.

## Критерии выбора

- Compose-first UI.
- Full-resolution original без обязательного полного bitmap в памяти.
- Pan/zoom/subsampling и gesture coordination.
- Live pager identity по `MediaId`.
- Symmetric grid/viewer transition и predictive back.
- Переход от static video placeholder к playback не должен менять pager/transition lifecycle.

## Альтернативы

### Ручной Compose viewer

Даёт полный контроль, но требует собственного zoom, pan, fling, bounds, subsampling и memory-pressure management.

### Только Coil AsyncImage

Решает загрузку, но не viewer gestures и subsampling.

### Telephoto + Compose APIs

Закрывает image engine через Telephoto, pager через Compose Foundation и оставляет приложению только transition/session coordination.

## Решение

- Telephoto `zoomable-image-coil3` отображает still originals и управляет zoom/subsampling.
- Coil 3 загружает local `content://` или authenticated Memories `/stream/{fileId}`.
- Compose `HorizontalPager` использует stable key `MediaId`.
- `ViewerSequence` принимает live timeline updates, удерживает текущий объект по `MediaId` и временно сохраняет orphan до перехода или закрытия.
- Viewer сам сообщает viewport prefetch range существующему timeline loader; originals соседей явно не prefetch.
- `ViewerTransitionCoordinator` связывает tile bounds, reveal и current media.
- Swipe-down и predictive back используют одну return-to-grid модель; viewer chrome не является transition surface.
- Текущее локальное видео в активной detail-page воспроизводится через Media3/ExoPlayer; cloud-only video до remote playback остаётся preview.
- `VideoPlaybackSession` отделяет typed playback state/effects от Media3: сессия создаётся только для активной локальной video-page, при уходе получает `Leave` и освобождает player.
- Воспроизведение запускается только явным действием. Position не сохраняется при смене pager page или возвращении к ней; новая сессия начинается с нуля.
- Управление (play/pause, seek, duration, mute, fullscreen, loading/error/retry) остаётся Compose UI поверх `PlayerSurface`.
- Fullscreen временно переводит activity в landscape и восстанавливает исходную ориентацию при выходе/уничтожении surface.
- В первом приближении используются только platform decoders Media3; transcoding и remote source ladder вынесены в отдельные задачи.

Версии библиотек определяет version catalog.

## Последствия

- Проект не поддерживает собственный image engine.
- Gesture conflicts проверяются automation tests и на реальном устройстве.
- Ultra HDR остаётся best-effort и зависит от gain map, Android и display.
- WebDAV fallback, remote video playback, transcoding и prefetch originals остаются отдельными решениями.

## Открытые вопросы

- Как Memories API отдаёт remote original и какие server-side variants доступны для transcoding/HLS.
- Какой client-side filmstrip projection даст scrubbing без изменений серверного API.
