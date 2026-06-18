# ADR 0010: MVP-1 warm-start cache policy

## Статус

Принято.

## Контекст

MVP-1 строится как remote-first клиент для Nextcloud/Memories. Пользователь должен быстро видеть familiar timeline после запуска приложения, но проект пока не обещает полноценный offline mode и не строит sync engine.

Текущая реализация уже загружает:

- Memories timeline index через `/apps/memories/api/days`;
- day details metadata через `/apps/memories/api/days/{ids}`;
- thumbnail bytes через `/apps/memories/api/image/multipreview`.

Без локального cache после перезапуска приложение снова ждет сеть даже для уже просмотренной части timeline. При этом в проекте действует dev-допущение: приложение установлено только на одном устройстве автора, миграции и backward compatibility локальной схемы пока не требуются, destructive reset локального cache допустим.

## Критерии выбора

- Ускорить warm start timeline после перезапуска.
- Не обещать offline mode, download queue или sync status.
- Сохранить путь к будущему sync/local media без миграции с временного JSON cache.
- Не хранить credentials вместе с cache.
- Не проектировать multi-account cache namespace в MVP-1.
- Разрешить destructive reset cache schema на MVP/dev этапе.
- Не усложнять UI скрытыми TTL-правилами.

## Альтернативы

### Memory-only cache

Плюсы:

- Почти нет storage-кода.
- Нет схемы и invalidation.

Минусы:

- Не решает warm start после перезапуска.
- Не выполняет MVP-пункт про базовый локальный cache metadata и thumbnails.

Вывод: недостаточно.

### JSON-файл для metadata

Плюсы:

- Быстро реализовать.
- Хорошо подходит для snapshot-style cache.
- Не требует Room schema.

Минусы:

- Потом придется переносить данные в DB для sync/local media.
- Сложнее делать выборки по `dayId`, `fileId`, loaded days и thumbnail index.
- Менее полезный задел для будущего timeline cache.

Вывод: приемлемо для прототипа, но слабее как фундамент.

### Room для metadata и thumbnail index

Плюсы:

- Хороший задел на будущий sync/local media.
- Удобные выборки по day/file/cache key.
- Timeline cache получает явный storage seam.
- Можно хранить metadata отдельно от binary thumbnail bytes.

Минусы:

- Больше кода на старте: entities, DAO, database, mappers.
- Схема появляется раньше.
- Нужно явно договориться, что migration guarantees пока нет.

Вывод: выбрать Room, но разрешить destructive reset schema на MVP/dev этапе.

## Решение

Использовать **warm-start cache**, не offline mode.

Кешируем в MVP-1:

- timeline index из `/apps/memories/api/days`;
- day details metadata из `/apps/memories/api/days/{ids}`;
- thumbnail bytes из `/apps/memories/api/image/multipreview`.

Не кешируем в MVP-1:

- detail previews `1600x1600`;
- originals/download/stream;
- video transcode/livephoto;
- albums/people/tags/places.

Storage:

- metadata/index хранить в Room;
- thumbnail bytes хранить отдельными файлами в cache directory;
- в Room хранить thumbnail index: `fileId`, width, height, mime type, cache key/path и `cachedAt`;
- credentials остаются в secure credentials store, не в cache DB.
- cache проектировать для текущего единственного аккаунта; multi-account не планируется для MVP-1.

Policy:

- на старте, если есть cached timeline для текущего аккаунта, показать его сразу;
- параллельно запустить network refresh `/config + /days`;
- cached loaded days показывать сразу;
- если пользователь скроллит к дню, которого нет в cache, грузить day details из сети и сохранять;
- если день уже есть в cache, не перезагружать его автоматически в MVP-1;
- manual refresh обновляет timeline index из сети;
- если при refresh у дня изменился `count`, cached day details для этого дня можно удалить или пометить stale;
- logout удаляет credentials и timeline cache/thumbnail files для аккаунта;
- schema changes могут делать destructive reset cache DB, пока действует dev-допущение;
- TTL в MVP-1 не вводить.

## Последствия

- Приложение сможет быстро показывать последнюю известную timeline после перезапуска.
- Cache loss допустим: source of truth остается Nextcloud/Memories.
- Room появляется как долговечный storage seam раньше sync engine, но без migration guarantees.
- Thumbnail bytes не раздувают Room DB.
- Offline mode остается отдельным будущим решением.

## Открытые вопросы

- Когда заменить destructive reset на реальные Room migrations.
- Какой eviction policy выбрать для thumbnail files после MVP-1.
- Нужно ли кешировать detail previews, когда detail screen станет богаче.
- Если multi-account появится после MVP-1, понадобится отдельное решение о per-account cache namespace.
