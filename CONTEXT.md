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

### Timeline cache

Локальный warm-start cache для remote timeline.

В MVP-1 metadata/index часть timeline cache хранится в Room, а thumbnail bytes хранятся отдельными файлами в cache directory. Room хранит индекс thumbnail-файлов: `fileId`, размеры, mime type, cache key/path и `cachedAt`.

Timeline cache можно разрушительно сбрасывать при изменениях схемы, пока действует dev-допущение одного устройства без migration guarantees.

### Single-account app

На текущем этапе NextGallery работает как приложение для одного подключенного Nextcloud/Memories аккаунта.

Multi-account не планируется для MVP-1 и ближайших cache decisions. Cache и session state можно проектировать вокруг текущего единственного аккаунта, без account switcher и без одновременного хранения нескольких активных account namespaces.
