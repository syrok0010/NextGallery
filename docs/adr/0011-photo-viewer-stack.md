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

### Media3 для обычного видео

Media3/ExoPlayer даёт platform decoding, seek и события playback без собственного
codec engine; Compose controls позволяют сохранить язык viewer. Отдельный video
screen нарушил бы общий pager/return transition, а готовый внешний player UI
навязал бы самостоятельную навигацию и chrome. Bundled software decoders увеличили
бы размер и стоимость сопровождения ради поддержки форматов вне первого среза.

## Решение

- Telephoto `zoomable-image-coil3` отображает still originals и управляет zoom/subsampling.
- Coil 3 загружает local `content://` или authenticated Memories `/stream/{fileId}`.
- Compose `HorizontalPager` использует stable key `MediaId`.
- `ViewerSequence` принимает live timeline updates, удерживает текущий объект по `MediaId` и временно сохраняет orphan до перехода или закрытия.
- Viewer сам сообщает viewport prefetch range существующему timeline loader; originals соседей явно не prefetch.
- `ViewerTransitionCoordinator` связывает tile bounds, reveal и current media.
- Swipe-down и predictive back используют одну return-to-grid модель; viewer chrome не является transition surface.
- Текущее видео в активной detail-page воспроизводится через Media3/ExoPlayer из локальной копии либо Memories original.
- `VideoPlaybackSession` отделяет typed playback state/effects от Media3: сессия создаётся только для активной video-page, при уходе получает `Leave` и освобождает player.
- Воспроизведение запускается только явным действием. Position не сохраняется при смене pager page или возвращении к ней; новая сессия начинается с нуля.
- Управление (play/pause, seek, duration, mute, fullscreen, loading/error/retry) остаётся Compose UI поверх `ContentFrame` с сохранением пропорций видео.
- Fullscreen остаётся в текущей page: временно разрешает landscape, скрывает viewer chrome и system bars; Back сначала выходит из fullscreen. Исходная ориентация и видимость system bars восстанавливаются при выходе/уничтожении surface. Activity обрабатывает смену ориентации без пересоздания, чтобы не потерять playback session.
- При уходе приложения в фон playback ставится на паузу, включая отмену play intent во время загрузки. Возврат не запускает видео автоматически.
- Для video surface используется TextureView, чтобы поверхность следовала Compose-transform при возврате в tile. Это обмен эффективности SurfaceView на совместимость с существующей анимацией viewer; controls в transform не входят.
- Используются platform decoders Media3 и его HLS-модуль; bundled software decoders не подключаются.

### Источники video original

Local-first выбирает локальную копию первой. При ошибке чтения или декодирования
сессия один раз переключает тот же player на remote original, сохраняя позицию,
play/pause intent, mute и fullscreen. `MediaId` и pager sequence не меняются.
Для original явный retry начинает выбор источников заново. Несовместимый remote
original допускает ещё один переход к обнаруженному Memories HLS.

UI получает только логическую ссылку на remote file ID. `OkHttpDataSource`
использует общий `NextcloudTransport`; interceptor разрешает ссылку в Memories
`/stream/{fileid}` и берёт актуальные credentials из `SessionStore` на каждый
запрос, включая range и retry. Передача готового URL с auth headers через Compose
отклонена: она связывает UI с transport и удерживает устаревший app password.
Предварительное скачивание целого original отклонено из-за задержки запуска и
лишнего трафика; byte ranges backend позволяют обычный streaming и seek.

Redirects для видео отключены: клиент обращается к каноническому stream endpoint
и не переносит credentials на login page или другой host. Сервер, перенаправляющий
этот endpoint, сейчас даст ошибку с retry. 401/403 дают
сообщение о необходимости авторизации; сетевые/HTTP ошибки — о недоступности
облачного видео. Logout убирает authenticated viewer по общей границе приложения.

### Remote quality и HLS

Критерии: сохранить local-first по умолчанию, поддержать несовместимые originals
без изменения backend, не обещать несуществующие серверные профили и не терять
позицию или play/pause intent при переключении источника.

После явного Play discovery читает config и master playlist параллельно обычному
playback. При `vod_disable` или ошибке discovery menu скрыто. `Direct` адресует
remote original, `Original` существует только при `max.m3u8`, `Auto` выбирает
master, остальные варианты берутся из manifest. Явный quality override у
local+cloud объекта переключает на remote; локальное перекодирование отсутствует.

Альтернатива с фиксированным списком разрешений отклонена: backend может не
предоставлять выбранный профиль. HLS по умолчанию отклонён: оригинал предпочтителен,
а transcode создаёт лишнюю серверную работу. Переключение заменяет source в том же
player через loading state, сохраняя position, play intent, mute и fullscreen.
Ошибка HLS завершает автоматический ladder; retry повторяет выбранный HLS с той
же позицией и intent. Уход в фон отменяет play intent даже во время загрузки.

Последствие: доступность menu определяется также конкретным файлом и storage,
а не только конфигурацией сервера. Discovery отменяется при закрытии сессии;
credentials разрешаются заново для manifest, segments и повторных запросов.

### Локальная лента кадров

Критерии: bounded-память, независимость от обычного playback и однозначный drag.
Нажатое local video раскрывается внутри существующего filmstrip item; `MediaId`
не меняется. `VideoFilmstripProjection` задаёт 24 равномерные позиции, progressive/
degraded state и seek не чаще раза в 100 мс с обязательной финальной позицией.

Кадры получает отдельный `MediaMetadataRetriever` на IO dispatcher: один decoder
на приложение, кадр не более 160 px по длинной стороне. Работа отменяется при
сворачивании карточки или уходе со страницы, retriever закрывается; постоянного
кеша нет. Poster заполняет отсутствующие кадры, ошибка ленты имеет собственный
retry и не передаётся player. Извлечение через основной player отклонено: оно
мешало бы обычному просмотру; full-resolution кадры — из-за лишнего расхода памяти.

Раскрытие и сворачивание карточки анимируют ширину и смену poster/кадров за 250 мс.
Раскрытая карточка остаётся элементом общего LazyRow между соседними медиа.
Её левый край при раскрытии сохраняет положение, правый край отодвигает следующие
элементы. Padding и ширины предшествующих карточек на время раскрытия сохраняются.
Длина карточки — две ширины viewport независимо от duration, число кадров — 24.
Коэффициент выбран как стартовый для проверки удобства: длинные видео не увеличивают
путь прокрутки. Указатель закреплён в центре viewport общей filmstrip.

Drag и инерция прокручивают общую filmstrip. Пока центр viewport находится внутри
раскрытой карточки, её смещение задаёт silent seek активной playback session;
после прохождения границы снова выбирается соседний медиаобъект. После
остановки ленты player остаётся на выбранной позиции и ждёт явного Play, сохраняя
mute preference. TalkBack получает состояние ленты и seek action.

Ограничение: отдельное извлечение требует дополнительного platform decoder;
при его недоступности лента деградирует независимо от playback. Совместимость
проверяется на целевом устройстве.

Версии библиотек определяет version catalog.

## Последствия

- Проект не поддерживает собственный image engine.
- Gesture conflicts проверяются automation tests и на реальном устройстве.
- Ultra HDR остаётся best-effort и зависит от gain map, Android и display.
- WebDAV fallback и prefetch originals остаются отдельными решениями.

## Открытые вопросы

- Совместимость VOD-контракта с другими версиями Memories и storage backends.
- Какой client-side filmstrip projection даст scrubbing без изменений серверного API.
