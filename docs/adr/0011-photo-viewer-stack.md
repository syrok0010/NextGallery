# ADR 0011: Стек просмотра фото и видео

## Статус

Принято.

## Контекст

Viewer должен показывать full-resolution still images с zoom/subsampling и best-effort Ultra HDR, воспроизводить локальные и облачные видео с выбором качества HLS и лентой кадров для перемотки, листать живую последовательность медиаобъектов и возвращать текущую поверхность в соответствующий тайл сетки. Собственный image/codec engine не является ценностью продукта.

## Критерии выбора

- Compose-first UI без отдельного video screen, ломающего общий pager и return-to-grid transition.
- Full-resolution original без обязательного полного bitmap в памяти.
- Live pager identity по `MediaId` и symmetric grid/viewer transition.
- Local-first воспроизведение и поддержка несовместимых remote originals без изменения backend и без обязательного холодного transcode.
- Authenticated streaming через общий `SessionStore`, без утечки credentials и предварительного скачивания видео.
- Извлечение кадров для filmstrip не мешает основному playback и не зависит от выбранного качества воспроизведения.

## Альтернативы

### Ручной Compose viewer или только Coil `AsyncImage`

Ручной viewer требует собственного zoom/pan/subsampling/memory management; один `AsyncImage` не закрывает жесты и subsampling.

### Отдельный экран видео, готовый внешний player UI или bundled software decoders

Отдельный экран разрывает общий pager и анимацию возврата в тайл; готовый player UI навязывает чужую навигацию и chrome; bundled software decoders увеличивают размер APK и стоимость сопровождения.

### `SurfaceView` вместо `TextureView`

Эффективнее по отрисовке, но не следует Compose-transform при анимации возврата видео в тайл сетки.

### HLS по умолчанию для playback или для ленты кадров

Создаёт лишнюю серверную нагрузку transcode. Для ленты кадров холодные замеры показали, что запуск ffmpeg ради миниатюр многократно замедляет готовность ленты ([filmstrip-cold-hls.md](../research/filmstrip-cold-hls.md)).

### Предварительное скачивание видео или `MediaMetadataRetriever` с прямыми remote URL

Скачивание создаёт задержку старта и лишний трафик при наличии byte ranges. Прямые URL в `MediaMetadataRetriever` обходят session-aware transport и не поддерживают HLS.

### Дисковый кеш кадров filmstrip или привязка кадров к выбранному качеству playback

Дисковый кеш требует политики очистки ради редких повторных открытий видео. Привязка к качеству playback заставляла бы перестраивать ту же ленту кадров при каждом переключении качества и тратить лишний трафик.

## Решение

### Фото, pager и переход

- Telephoto `zoomable-image-coil3` отображает still originals и управляет zoom/subsampling; Coil 3 загружает local `content://` или authenticated Memories `/stream/{fileId}`.
- Compose `HorizontalPager` использует stable key `MediaId`. `ViewerSequence` принимает live-обновления коллекции и удерживает текущий объект при временном исчезновении, кроме явно удалённых объектов.
- Viewer передаёт диапазон предзагрузки в timeline через `ViewerMediaIndex` (ADR 0001); originals соседей явно не предзагружаются.
- Swipe-down и predictive back используют одну модель возврата в тайл через `ViewerTransitionCoordinator`.

### Видео: Media3, `TextureView`, источники и безопасность

- Видео воспроизводится внутри активной страницы pager через Media3/ExoPlayer с platform decoders и HLS-модулем (без bundled software decoders) поверх **`TextureView`** — осознанный обмен эффективности `SurfaceView` на совместимость с Compose-transform при возврате в тайл.
- Воспроизведение запускается только явным действием; при уходе в фон ставится на паузу с отменой play intent. Activity обрабатывает смену ориентации без пересоздания, чтобы не терять playback session при fullscreen.
- **Fallback ladder источников:** local → remote original (`/stream/{fileId}`) → Memories HLS. При ошибке локальной копии player один раз переключается на remote original с сохранением позиции и play/pause intent; при несовместимости remote original допускается переход к обнаруженному HLS.
- **Remote quality и HLS:** после явного Play discovery параллельно читает config и master playlist. `Direct` адресует `/stream`, `Original` соответствует `direct.m3u8`, `Auto` — master playlist, остальные варианты берутся из manifest (без зашитого списка разрешений). Явный выбор качества у `LocalFirst` объекта переключает на remote.
- **Безопасность `OkHttpDataSource`:** в каталоге медиа хранятся только asset identifiers, а реальные URL строятся при создании сессии. Interceptor на общем `NextcloudTransport` проверяет scheme, host, port и путь Memories API текущей сессии и подставляет свежие credentials из `SessionStore` на каждый запрос (manifest, segments, ranges). URL другого сервера отклоняется; HTTP redirects для видео отключены, чтобы не переносить credentials на login page или чужой host.

### Лента кадров (filmstrip)

- **Число кадров — логарифмическое (формула Samsung Gallery):** `N = max(3, int(7 × log10(max(1, durationMillis / 1000))))` без жёсткого верхнего предела, равномерно по всему интервалу длительности. По сравнению с фиксированным числом кадров или линейным шагом короткие ролики не делают лишних извлечений, а длинные (час и более) не раздувают ленту и память линейно, сохраняя удобную перемотку.
- **Один последовательный extractor на приложение:** кадры извлекаются отдельно от основного player (чтобы не мешать воспроизведению) и строго последовательно под общим `Mutex`, по одной ленте за раз — число одновременных hardware platform decoders на Android ограничено, а один из них уже занят playback. Работа отменяется при сворачивании карточки или уходе со страницы.
- **Локальное видео:** `MediaMetadataRetriever` с `OPTION_CLOSEST_SYNC` — миниатюрам достаточно ближайшего ключевого кадра, что избегает полного декодирования промежуточных кадров (ценой возможных повторов при редких ключевых кадрах; точный seek самого плеера от этого не зависит). Кадр масштабируется под заполнение ячейки с учётом плотности экрана и обрезается до её физических размеров без декодирования full-resolution: фиксированный лимит по длинной стороне давал у вертикального видео слишком мало пикселей по ширине и мылил crop на плотных экранах.
- **Облачное видео:** `media3-inspector-frame` (`FrameExtractor`) и `media3-inspector` поверх той же authenticated `MediaSource.Factory`. Сначала всегда читается original `/stream`; только несовместимость формата запускает discovery и одну попытку фиксированного HLS с минимальным bitrate/разрешением (`Auto` и `direct.m3u8` исключены), сетевые ошибки и таймауты transcode не запускают. Запасному HLS даётся существенно больший таймаут, чем original, из-за холодного запуска `ffmpeg` и генерации сегментов после seek. Выбор качества playback не меняет и не перезапускает извлечение кадров.
- **Кеш кадров:** полностью готовые ленты хранятся только в памяти (LRU без дискового кеша; неполные и degraded результаты не кешируются). Владелец кеша — открытый viewer выше скрываемого chrome, поэтому сворачивание карточки, смена качества, fullscreen и скрытие панели не теряют готовые кадры. Ключ — `MediaAssetRef` + `ETag`, а не playback URI: URI меняется вместе с VOD client id и качеством, хотя миниатюры те же. Показываемые bitmap не освобождаются через `recycle()`, пока UI может их использовать.

Версии библиотек определяет version catalog.

## Последствия

- Проект не поддерживает собственный image/codec engine, но зависит от platform decoders и GL-реализации устройства (особенно для `FrameExtractor` и fMP4 от go-vod); совместимость проверяется на реальном устройстве ([filmstrip-cold-hls.md](../research/filmstrip-cold-hls.md)).
- Сервер, перенаправляющий `/stream` через HTTP redirect, даст ошибку вместо пересылки credentials.
- Ultra HDR остаётся best-effort и зависит от gain map, Android и дисплея.
- Обычное открытие облачной ленты кадров читает original (объём range-запросов зависит от контейнера и GOP), зато не запускает холодный серверный transcode на каждом видео.

## Открытые вопросы

- Совместимость VOD-контракта с другими версиями Memories и storage backends.
- Совместимость `media3-inspector-frame` с codec/GL реализациями целевых устройств.
