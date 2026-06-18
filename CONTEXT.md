# Контекст проекта

## Термины

### Warm-start cache

Локальный кеш, который позволяет после перезапуска быстро показать последнее известное состояние remote-first timeline до завершения сетевого refresh.

Warm-start cache может показать уже сохраненные metadata и thumbnails без сети, но не обещает полноценный offline mode: нет download queue, sync status, pin/offline controls, background refresh и гарантий доступности всего архива.

### Offline mode

Режим, в котором приложение явно обещает доступность выбранных данных без сети и управляет состояниями синхронизации, ошибками, очередями загрузки, eviction policy и пользовательскими признаками доступности offline.

### Timeline index

Список дней remote timeline из Memories `/apps/memories/api/days`: `dayId`, количество элементов за день и, если сервер вернул, preloaded detail для части элементов.

Timeline index задает форму virtual timeline и позволяет быстро построить placeholders до загрузки metadata.

### Day details metadata

Metadata элементов выбранных дней из Memories `/apps/memories/api/days/{ids}`: `fileId`, `dayId`, размеры, mime type, display name, flags и preview URLs.

Day details metadata заполняет virtual slots реальными `MediaItem`, но не включает binary thumbnail bytes или originals.

### Thumbnail bytes

Бинарные preview-изображения для grid tiles, полученные через Memories `/apps/memories/api/image/multipreview`.

В MVP-1 thumbnail bytes кешируются только как часть warm-start cache для сетки, без обещания полноценной offline-доступности архива.

### Full-resolution viewer image

Изображение, которое просмотрщик фото показывает как конечное качество для выбранного элемента.

Для remote media это должен быть оригинал файла, а не серверный detail preview. Detail preview может использоваться как быстрый промежуточный слой, но не считается полноценным результатом открытия фото.

В MVP full-resolution originals загружаются только для текущей страницы viewer. Явный prefetch originals для соседних страниц не выполняется; соседние страницы могут использовать preview/placeholder до фактического открытия.

Для Memories remote source original image в MVP загружается через `/apps/memories/api/stream/{fileId}`. WebDAV/download fallback для original source в этот срез не входит.

### HDR viewer support

Поддержка HDR в просмотрщике фото для MVP означает best-effort отображение Ultra HDR still images с gain map, когда оригинальный файл и устройство это поддерживают.

Если файл не содержит gain map или версия Android/устройство не поддерживает Ultra HDR output, просмотрщик показывает SDR-версию без обещания отдельного HDR-режима. HDR-видео не входит в этот термин для MVP.

### Viewer sequence

Упорядоченная последовательность media items, по которой пользователь листает фото в просмотрщике.

В MVP viewer sequence берется из текущего remote timeline: `TimelineSnapshot.slots.mapNotNull { mediaItem }`. Route просмотра остается адресованным по `fileId`, а позиция внутри viewer sequence вычисляется из текущего timeline snapshot.

Если при листании пользователь приближается к краю загруженных metadata, просмотрщик может использовать существующую lazy loading механику timeline, а не отдельный album/download pipeline.

### Video viewer placeholder

Представление видео внутри viewer sequence без видеопроигрывателя.

В MVP видео участвуют в горизонтальном листании наравне с фото, но страница видео показывает статичный preview/первый кадр и признак видео. Playback, transcode UX, audio controls и HDR-видео не входят в этот срез.

### Viewer surface

Визуальная поверхность текущего фото или video placeholder внутри просмотрщика.

При swipe-down dismiss пользователь двигает viewer surface пальцем без затемнения фона. После отпускания поверхность должна уменьшиться и анимироваться обратно в соответствующий tile сетки.

Перед return transition сетка должна быть прокручена к месту текущего media item, чтобы target tile был в ожидаемой позиции.

Если swipe-down не проходит threshold по смещению или velocity, viewer surface возвращается в полноэкранное положение и просмотрщик остается открытым.

Переход между timeline grid и viewer surface должен быть симметричным: tile открывается в viewer surface по ключу текущего media item, а при закрытии текущая surface возвращается в tile текущей страницы pager. Внутри viewer surface thumbnail/detail preview может быть заменен full-resolution original после загрузки.

Predictive back gesture в viewer должен вести к тому же return-to-grid результату, что и swipe-down dismiss: viewer surface интерактивно уменьшается и возвращается в tile текущей страницы pager, а сетка предварительно прокручивается к этому media item.

Viewer chrome не является shared element. При swipe-down dismiss или predictive back progress chrome скрывается, в transition участвует только viewer surface. Если жест отменен, chrome возвращается вместе со snap-back viewer surface.

### Timeline cache

Локальный warm-start cache для remote timeline.

В MVP-1 metadata/index часть timeline cache хранится в Room, а thumbnail bytes хранятся отдельными файлами в cache directory. Room хранит индекс thumbnail-файлов: `fileId`, размеры, mime type, cache key/path и `cachedAt`.

Timeline cache можно разрушительно сбрасывать при изменениях схемы, пока действует dev-допущение одного устройства без migration guarantees.

### Single-account app

На текущем этапе NextGallery работает как приложение для одного подключенного Nextcloud/Memories аккаунта.

Multi-account не планируется для MVP-1 и ближайших cache decisions. Cache и session state можно проектировать вокруг текущего единственного аккаунта, без account switcher и без одновременного хранения нескольких активных account namespaces.
