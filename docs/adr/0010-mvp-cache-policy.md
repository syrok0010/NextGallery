# ADR 0010: Warm-start cache policy

## Статус

Принято и расширено для unified timeline. Политика выхода изменена: выход удаляет все данные приложения, включая локальный индекс (решение в #53).

## Контекст

Приложение должно быстро показать доступную часть медиатеки до завершения сети и MediaStore reconciliation, но пока не обещает полный offline archive или sync engine.

## Критерии выбора

- Cached local и remote projections публикуются независимо.
- MediaStore и Nextcloud/Memories остаются sources of truth.
- Cache loss не уничтожает пользовательские оригиналы.
- Credentials хранятся отдельно.
- Выход удаляет все данные приложения; данные двух аккаунтов не сосуществуют.
- Local thumbnails не дублируются в собственном файловом cache.
- Room schema можно разрушительно сбрасывать на текущем dev-этапе.

## Альтернативы

### Memory-only

Не даёт warm start после перезапуска.

### JSON snapshot

Прост для одного состояния, но неудобен для day details, local batches, identity joins и thumbnail index.

### Room + отдельные thumbnail files

Даёт запросы и lifecycle по типам данных, не помещая binary bytes в database.

## Решение

Использовать одну `NextGalleryDatabase` с отдельными DAO/lifecycle для:

- Memories timeline index и materialized day metadata;
- MediaStore projection;
- persistent media identity и conflicts;
- remote thumbnail index.

Remote thumbnail bytes хранятся в `ThumbnailFileStore`. Local images читаются через MediaStore `content://`, а Coil cache key учитывает URI и `DATE_MODIFIED`.

На старте cached local projection и cached Memories metadata публикуются без ожидания refresh. MediaStore reconciliation и Memories refresh независимо обновляют `UnifiedTimelineProjection`.

Offline remote timeline включает только materialized objects с достаточными metadata. Index-only slots скрываются; отсутствие cached thumbnail оставляет объект в timeline как placeholder.

Не кешируются как отдельная продуктовая гарантия:

- detail previews и originals;
- video transcodes и live-photo streams;
- albums, people, tags и places;
- полный remote archive.

Выход очищает все данные приложения: credentials, Memories metadata, remote thumbnails, identity bindings, MediaStore projection и local `MediaId`. После выхода приложение в состоянии свежей установки. Раньше local index переживал выход; от этого отказались: сохранять его ради следующего входа незачем, а частичная очистка оставляла гонки с поздними записями.

Single-account граница описана в product vision: cache обслуживает одно подключение, привязывать его записи к аккаунту не нужно.

## Последствия

- Повторный запуск быстро показывает последнюю материализованную часть медиатеки.
- Offline timeline может быть неполным и не является обещанием offline mode.
- Room служит durable projection, но migrations пока не гарантируются.
- Remote thumbnail cache требует отдельной eviction policy.
- Возврат media permission в той же сессии позволяет повторно связать local copies с прежними `MediaId`.
- После выхода local `MediaId` создаются заново.

## Открытые вопросы

- Когда ввести Room migrations вместо destructive reset.
- Какая eviction policy нужна remote thumbnails.
- Какие данные должны получить явную offline pin/download policy.
- Механизм очистки при выходе: `ActivityManager.clearApplicationUserData()` (завершает процесс) или ручная очистка с перезапуском Activity (#53).
