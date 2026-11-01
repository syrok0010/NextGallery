# Локальные копии и MediaId: варианты для #115

Исследование от 01.11.2026. [#115](https://github.com/syrok0010/NextGallery/issues/115)
остаётся открытой: runtime-поведение, доменная модель и решения ADR здесь не меняются.
Рекомендация ниже требует продуктового решения; это не спецификация реализации.

## Вывод

Рекомендуется **отдельный объект для каждого локального файла, максимум одна Local
copy на MediaId**, с сохранением приоритета явных upload links. При нескольких
равноправных кандидатах для облачной копии безопаснее оставить её отдельно, чем
выбрать локальную по порядку обхода. Это сочетание варианта 1 и консервативной части
варианта 3. Полная дедупликация точных копий оправдана только вместе с продуктовой
моделью набора copies и управлением ими в UI; одна замена условия в registry такой
модели не создаёт.

Причина выбора: нынешний `MediaItem` представляет максимум один локальный URI и
один remote fileId. Объединение нескольких локальных файлов скрывает адресуемые
copies от viewer и операций. Устранение этой потери доступности не требует полного
чтения медиатеки. При этом ограничение числа copies само по себе **не доказывает**,
что выбранная пара local/remote — один пользовательский объект. [S1], [S2], [S3], [S4], [S5]

## Проверенный срез и границы

Все ссылки на код закреплены за ревизиями, а не за подвижными ветками:

- **main:** `65c3b77f0829eeea6f802fe9de2514984108efdc` — база этого исследования.
- **PR #145:** `1c4c886b2939be9a38e3b2e68fef8450781dadb0`, base
  `fix/album-add-unindexed`: актуальные aliases конкретной source copy. [S6]
- **PR #147:** `91b1e7365bb392ebe99b9f463984f97ec2c9d477`, base
  `fix/stale-identity-aliases`: aliases исчезнувших copies. Включает #145. [S7]
- Связь после успешного upload из [PR #108](https://github.com/syrok0010/NextGallery/pull/108)
  уже в main. Решение [#131](https://github.com/syrok0010/NextGallery/issues/131)
  реализовано в открытом [PR #141](https://github.com/syrok0010/NextGallery/pull/141)
  и унаследовано #145/#147; в этом main его ещё нет. [S8], [S9]

Прочитаны CONTEXT, ADR 0002/0013/0017, код ingestion, registry, проекций, upload и
операций текущего стека, а также связанные тесты. **ADR 0018 об identity в main
отсутствует**; в #145/#147 этот номер занят статусами операций. Ссылка старого #115
на будущий ADR 0018 не является актуальным решением; новый ADR следует нумеровать
по состоянию репозитория при принятии решения. [S0], [S10]

Выполнен существующий `./gradlew :core:media:test --no-daemon`: 7 тестов, без ошибок
и пропусков. Остальные тесты ниже изучены как исходники, не запускались. Android
сборки, устройства и общий Nextcloud-стенд не использовались. Стендовые утверждения
из старых ADR не считаются повторно проверенными этим исследованием.

## Какие идентичности действительно установлены

1. **Source identity** — пара `(Local, contentUri)` или `(Memories, fileId)`.
   Она адресует конкретную копию в lifecycle источника. Это не content hash и не
   универсальная идентичность между аккаунтами, пересозданием MediaStore или
   изменением содержимого по прежнему URI/fileId. Номер remote fileId сам по себе
   не содержит server/account namespace; logout очищает записи Memories. [S2], [S11]
2. **Связь успешного PUT** знает исходный URI и возвращённый `OC-FileId` из одной
   операции. Это явное происхождение облачной копии из локальной, более сильное,
   чем AUID/BUID, но без последующей проверки неизменности обоих файлов. Registry
   не заменяет уже занятую облачную связь и проверяет актуальность сессии. [S8]
3. **Связь #131** тоже становится явной записью URI ↔ fileId, однако основание
   — принятое автором совпадение DAV-пути/имени и размера после `412`, не равенство
   байтов. В стеке сначала проверяется `HEAD`, затем `PROPFIND` подтверждает fileId
   и размер в одном успешном `propstat`. Замена другим файлом того же размера
   остаётся риском. Менять принятое решение #131 в рамках #115 не предлагается. [S9]
4. **AUID/BUID** — aliases для приблизительного первичного сопоставления:
   `MD5("$epoch$size")`, `MD5(basename + "iuid=" + ImageUniqueID)` либо
   `MD5(basename + "size=" + size)`. Хешируются метаданные, не файл. Совпадение
   одного alias достаточно, если найденные aliases не ведут к разным MediaId;
   совпадение сразу обоих не требуется. Формулы подтверждены реализацией Memories
   Android; серверный timeline в исследованной версии выбирает `epoch` и `size`,
   а native query отдельно добавляет BUID. [S2], [S12]
5. **Persisted mapping** считается точной привязкой при последующих `resolve`,
   даже если впервые возникло по приблизительному alias. В main и #145/#147 нет
   сохранённого признака «эта пара подтверждена PUT / принята после 412 / выведена
   из alias». Поэтому «точная запись» в коде не означает «доказаны одинаковые
   байты», а удаление alias не отменяет старую склейку. [S2], [S6], [S13]

Приоритет для будущей политики: известная source identity и принятый явный upload
link выше эвристического alias; неоднозначность aliases не должна перемещать такую
связь к другому объекту. Нужна проверка всего пути до проекции, не только Room:
`projectMediaLibrary` снова запускает reconciliation с пустыми initial maps и
`publishedMediaId` входящих элементов. [S3], [S8], [S14]

## Что сейчас происходит с дубликатами

- Для нового Local-кандидата проверка занятости source отключена. Несколько URI
  с общим alias получают один MediaId. Проверки содержимого нет, поэтому это
  относится и к точным копиям, и к разным файлам одинакового размера/времени. [S2]
- Локальный AUID берёт **raw DATE_TAKEN**, не каноническое время timeline.
  При `DATE_TAKEN = null` подставляется 0: одинакового размера достаточно даже
  при разных `date_modified` и днях. Неизвестный размер также превращается в 0.
  Замена MD5 на SHA-256 над теми же полями эту неоднозначность не устранит. [S4], [S15]
- В проекции `localByMediaId = associateBy(...)` выбирает последний локальный
  элемент для объединения с remote; для local-only `distinctBy(MediaId)` оставляет
  первый. Остальные URI не входят в опубликованный `MediaItem`. Выбор представителя
  зависит от порядка входа; наличие N copies интерфейсом не выражено. [S1], [S3]
- Несколько новых Memories fileId с общим alias ограничиваются одной Memories
  copy на объект: лишние остаются отдельными, фиксируется конфликт. Это не
  семантика «одинаковые bytes всегда один MediaId»: remote duplicates уже считаются
  разными объектами. При N Local и M remote результат не обязан быть N или M:
  Local могут свернуться в один объект, первый подходящий remote присоединиться,
  остальные remote остаться отдельно. Порядок/уже известные связи влияют на пару. [S2], [S14]
- В текущем стеке удаление получает из объекта один local URI и один fileId;
  upload пропускает объект с remote copy и выбирает один local URI для остальных.
  Скрытые Local copies не получают отдельной адресуемости. Ошибочный merge может
  скрыть нужную загрузку или направить «удалить везде» на чужую remote copy. Это
  риск модели, а не утверждение о проведённом удалении на устройстве. [S16]

## Lifecycle aliases и доступности

**main.** Aliases сохраняются на MediaId в `media_identifiers`, без владельца-copy.
Новые значения не удаляют старые. Полный local scan удаляет исчезнувшие строки
проекции, но не соответствующие source identities/aliases registry. [S4], [S13]

**#145.** Отдельная `media_source_aliases` хранит наблюдения каждой source copy.
Устаревшие aliases входящей партии удаляются до matching в той же транзакции,
включая переход к пустому набору. Alias другой copy остаётся. Exact mappings и
явные links не разделяются. Room 10 → 11 использует разрешённый в этом dev-стеке
**destructive reset**, а не перенос прежних данных. Это не миграция #115. [S6], [S17]

**#147.** Полный local scan очищает aliases отсутствующих URI; exact mappings и
links сохраняются. URI недоступных volumes из metadata cache учитываются как
существующие, как и прочитанные строки без пригодной timeline-даты. Незавершённый
scan/ошибка не подтверждают исчезновение. При denied/partial permission сам
`LocalMediaSource` публикует пустой список и не запускает scan, поэтому это не
команда удалить identities. Возврат Full access запускает новое чтение кэша/scan. [S4], [S7], [S18]

В облаке #147 очищает aliases **ранее кэшированных** fileId, отсутствующих в одном
полном snapshot: все дни загружены и число уникальных fileId каждого дня равно
счётчику. Исчезновение дня, один ответ дня или перенос D1 → ещё не загруженный D2
не доказывают удаление. Если metadata исчезнувшего файла уже вытеснены отдельным
ответом дня, aliases могут остаться: это прямо следует из сравнения с текущим
`timelineDao.fileIds()`, а не с полным inventory registry. [S7], [S18]

Следствия для всех вариантов:

- Удаление одной copy должно менять доступность этой copy, а не автоматически
  удалять остальные. При нескольких Local нужен новый представитель либо отдельные
  плитки; сейчас для уже склеенных URI выбор возможен только среди оставшихся
  элементов входной проекции. Очистка aliases не означает удаление файла. [S3], [S7]
- Permission loss, временно отсутствующий volume и доказанное удаление — разные
  события. Кэшированные URI unavailable volume могут оставаться в local projection:
  сохранённая identity не гарантирует читаемые bytes. После ошибки чтения local
  объединённый объект может использовать cloud fallback по ADR 0002. [S0], [S4], [S18]
- При возвращении той же URI после исчезновения preserved mapping восстанавливает
  прежний MediaId даже без alias. При новой URI после переиндексации такой гарантии
  нет. Version/generation участвуют в metadata cache fingerprint, но не в ключе
  identity registry; при переиспользовании URI нужна отдельная политика. [S2], [S18]
- Reconnect без logout сохраняет registry. Logout вызывает очистку Memories cache
  и `removeSource(Memories)`; после нового входа прежняя explicit link не гарантируется,
  возможны эвристическое повторное объединение или две плитки. Политику persistence
  аккаунтов нельзя незаметно менять как «исправление aliases». [S8], [S11]

## Связь с #118

[#118](https://github.com/syrok0010/NextGallery/issues/118) описывает похожий симптом,
но **не установленную причину**. В исходном наблюдении размеры PNG разные; одного
совпадения секунды недостаточно для одинакового AUID. При null DATE_TAKEN AUID
зависит от размера, а не `date_modified`. Проверять BUID, registry и проекцию всё
равно нужно, но сводить #118 к #115 нельзя. [S4], [S12], [S15]

В нынешнем main `TimelineSnapshotProjection` добавляет local-only дни, которых нет
в remote index; `TimelineSnapshotAssembler.addSourceItems` строит объединение дней.
Тест прямо проверяет, что local-only день помечается loaded, а local copy в ещё не
загруженном remote дне не делает тот день loaded. Поэтому старая гипотеза «local
день обязан быть на сервере» не описывает весь текущий путь. [S19]

Для no-EXIF файла без DATE_TAKEN fallback `date_modified/date_added` попадает в
`floorDiv(seconds, 86400)`; отдельная нормализация по зоне применяется к DATE_TAKEN,
но не к этому fallback. Это объясняет возможность соседнего календарного дня в
UTC/MSK, **не доказывает исчезновение тайла**. Для #118 нужна отдельная диагностика
на текущей ревизии: URI → raw/canonical date и dayId → MediaId/conflicts → canonical
items → slots/loaded days/grid. Закрывать #118 по результатам этого документа нельзя. [S15], [S19], [S20]

## Критерии выбора

В порядке приоритета: доступность каждой пользовательской copy для действий;
сохранение сильных links и защита от неверного удаления; предсказуемое число плиток
при N×M дубликатах; восстановление persisted данных; приемлемые I/O и память;
соответствие определению медиаобъекта в CONTEXT. Удобство компактной ленты полезно,
но не компенсирует потерю возможности выбрать исходный файл.

## Три варианта

### 1. Каждый локальный файл отдельно: max1 Local на MediaId

Новая Local copy не присоединяется к объекту, уже имеющему другую Local copy,
даже если bytes равны. Точные локальные дубликаты дают отдельные плитки, remote
duplicates сохраняют действующее ограничение max1 Memories. Явная upload link
закрепляет конкретную пару. При N Local и M remote с одинаковым alias без явных
links требуется правило выбора пары: порядок обхода прост, но может склеить не ту
пару. Рекомендуется в такой неоднозначности оставить remote отдельно; применение
такого правила должно учитывать ещё не загруженные дни/партии, а не объявлять
частичный набор единственным кандидатом. Текущая форма alias → один MediaId
не выражает несколько владельцев alias: проверка неоднозначности потребует набора
кандидатов, а не только симметричного условия `occupied`. Сохранённый недоступный
URI также нельзя автоматически объявить свободным Local-слотом; новая URI
до подтверждения должна оставаться отдельной copy. [S2], [S6], [S7]

Стоимость — metadata/registry scan, без нового чтения полных файлов; растёт число
публикуемых объектов до N+M. Текущие `LocalContent/LocalFirst` подходят для результата.
Нужно согласовать registry, повторное matching в проекции и конфликты. **Одно
изменение `occupied` не разделит persisted склейки**: known-source ветка возвращает
старый MediaId до этой проверки. [S1], [S2], [S3], [S4]

### 2. Точные копии вместе: явный набор Local copies и UX

Объединять только подтверждённо одинаковые bytes, сохраняя каждый URI отдельной
copy внутри объекта. Плитка сообщает число copies; экран copies показывает папки,
доступность и выбор представителя. «Удалить с телефона» должно явно означать одну
или все Local copies с числом файлов; upload должен определить, загружается одна
репрезентативная copy или каждая выбранная. Потеря permission/volume делает copy
недоступной, удаление одной не уничтожает остальные; возвращение восстанавливает
доступность. Это **новая продуктовая модель**, не текущее поведение «точные копии
уже объединяются»: сейчас объединяются и разные bytes. [S1], [S2], [S3], [S4], [S5], [S16]

Для нескольких remote duplicates остаётся отдельный выбор: сохранить max1 Memories
и показывать остальные отдельными объектами либо тоже ввести набор remote copies.
Второй путь затронет альбомное membership, имена/metadata, counters и область
удаления. Нельзя приравнять общий hash к разрешению удалить все файловые экземпляры.
Нужны изменение доменного контракта, persistence membership, управление copies,
выбор стабильного представителя и пересчёт существующих связей.

### 3. Усилить подтверждение или отказаться от спорных approximate merges

Вариант без I/O: сохранять разные объекты при коллизии, неполном inventory или
недостаточной уверенности; AUID/BUID могут предлагать кандидатов, но не выбирать
неоднозначную пару. При полном отказе от aliases больше старых загрузок/копий
других клиентов останутся раздельными до explicit upload link. Отказ от новых
приблизительных merges также сам не исправит persisted склейки. [S2], [S8], [S13]

Вариант с content hash: сначала дешёвый отбор кандидатов по metadata, затем полный
SHA-256 файла с известной версией/размером, сравнение одинакового алгоритма над
одинаковыми bytes. MIME/размеры изображения/дата/ImageUniqueID либо частичный хеш
могут отбраковывать кандидатов, но не доказывают равенство содержимого. Совпадение
полного криптографического хеша — сильное практическое свидетельство byte equality;
при требовании строгого равенства нужно ещё побайтовое сравнение. Это не ответ на
вопрос, считать ли изменённое EXIF, перекодирование или RAW/JPEG одним фото.

Удалённого content hash в используемом timeline DTO нет: `etag` не объявлен в
контракте хешем содержимого, а AUID заведомо метаданный. Нужны отдельное исследование
согласованного серверного checksum API/его алгоритма и доступности либо скачивание
оригинала. Хеширование local не подтверждает remote само по себе. Ошибка чтения
или отсутствие checksum означает «не установлено», а не «файлы различны». [S1], [S12], [S21]

## Стоимость и последствия выбора

| Подход | Дополнительный I/O | Память и persistence | Главная цена |
|---|---|---|---|
| 1: max1 Local | Без full-file reads; чтение существующих metadata/registry | O(N+M) объектов и source mappings; больше тайлов | Дубликаты остаются видимыми; нужна политика неоднозначных пар и старых склеек |
| 2: набор точных copies | Проверка bytes кандидатов; для remote нужен checksum/скачивание | O(C) membership для C copies плюс объекты и hashes | Новый UX и изменение всех операций над copies |
| 3: консервативный отказ | Без нового bytes I/O | Индекс alias → множество кандидатов; O(C+A) для C copies и A наблюдений | Больше раздельных local/remote плиток |
| 3: полный hash | O(B) прочитанных bytes выбранных кандидатов; сеть O(Bremote) при скачивании | O(C) hashes + O(buffer × workers) при streaming | Время, батарея, сеть, invalidation при изменении источника |

Это оценки сложности, не измерения производительности. N/M — число local/remote
copies. Отбор по размеру ограничивает B кандидатами, но массовые точные копии и
видео всё равно дороги. 100 000 SHA-256 digest занимают 3,2 MB сырых bytes, без URI,
индексов и накладных расходов Room. Нужен bounded streaming, не загрузка originals
в RAM. Уже существующий EXIF-cache экономит чтение metadata по fingerprint
URI/volume version/generation/timezone; для content hash нужны собственные правила
инвалидации и проверка изменения файла во время чтения. [S18]

## Совместимость сохранённого registry

Варианты 1/3 требуют явного восстановления ранее склеенных объектов, а вариант 2
— ещё и membership/schema. Ни #145, ни #147 не являются автоматическим исправлением
истории #115. Приоритет старой source identity сохранит ранее ошибочно общий MediaId.
Перезапуск и rescan без изменения persisted mapping проблему не гарантируют решить. [S2], [S6], [S7]

Возможны два пути, выбирать нужно до реализации:

- **Допустимый dev reset.** Заново построить identities/projections при согласованном
  сбросе локальной БД. Проще, но теряются explicit upload links, warm cache и
  стабильность MediaId; без повторной загрузки не все пары восстановятся. Нельзя
  выдавать это за бесшовную миграцию или автоматически распространять разрешение
  destructive reset из #145 на новую задачу. [S8], [S11], [S17]
- **Сохраняющая миграция/repair.** Найти MediaId с несколькими Local source keys,
  выбрать сохраняемый ID, остальным выдать стабильные новые ID и обновить потребителей.
  Remote copy оставлять с подтверждённой парой, не выбирать URI случайно. Но старый
  registry не хранит provenance links: для неоднозначной истории нужны дополнительное
  свидетельство, подтверждение пользователя или разделение с явно объявленной
  потерей уверенности. Hash не восстановит происхождение upload при недоступном
  source и не должен молча разрывать #131. [S13]

В будущем полезно отдельно сохранять происхождение/силу связи и доступность copy;
это вариант проектирования, не изменение модели этим документом. Repair должен
быть идемпотентным и атомарным, согласованным с активными upload/delete, selection,
viewer и кэшами. Отсутствующие sources нельзя считать свободным слотом для
перепривязки уже подтверждённой пары только потому, что volume временно недоступен.

## Разрыв продуктового определения MediaId

CONTEXT определяет объект как «одно пользовательское фото или видео независимо
от числа и расположения его копий», а MediaId — как его устойчивую identity.
Однако опубликованный контракт содержит один Local и один Memories asset,
remote duplicates разделяются, Local duplicates могут объединяться по metadata.
Код не задаёт эквивалентность «одно фото»: одинаковые bytes, одна съёмка, визуальная
тождественность или отдельный пользовательский файловый экземпляр. Это проверенный
разрыв между широким определением и доступными средствами модели. [S0], [S1], [S2], [S3]

Продуктовые варианты: сузить объект до отдельной Local copy с возможной remote
парой (вариант 1); сохранить широкое определение и построить набор copies (2);
либо считать MediaId устойчивой группой только подтверждённых связей и допускать
временные отдельные объекты до подтверждения (3). Исследование не выбирает новый
смысл за пользователя и не редактирует CONTEXT/ADR.

## Минимальные регрессии для будущей реализации

Сквозная граница проверки: registry → persisted reopen → canonical projection →
timeline/album keys → наборы upload/delete targets. Только теста `resolve` мало.

1. Два разных файла с одинаковыми DATE_TAKEN/size и разными BUID; отдельно точные
   копии в разных папках. Число плиток и доступных URI соответствует выбранной политике.
2. Null DATE_TAKEN, один size, разные имена и `date_modified`/дни; отдельно неизвестный
   size и одинаковый fallback BUID. Отсутствующая metadata не становится подтверждением bytes.
3. N Local × M remote с общими aliases: оба порядка ingestion, разные партии,
   partial days, cached/warm start, remote без aliases. Нет случайной пары и duplicate keys.
4. Successful PUT с несовпавшими aliases; коллидирующая чужая Local copy не уводит
   link. `412` с равным размером проходит согласованный путь #131; другой размер,
   отсутствующий/ошибочный fileId не создают link. Уже занятая remote связь не заменяется.
5. Уже сохранённый общий MediaId у нескольких Local URI: повторный resolve,
   reopen, repair/reset и повтор repair. Установить ожидаемое сохранение ID/links заранее.
6. Смена/опустошение aliases одной copy, общий alias другой; полный scan исчезновения,
   partial/error scan; D1 → D2 между запросами и local кандидат в промежутке.
7. Denied/partial → Full permission; unavailable volume → возврат; та же URI и
   новый URI/volume version после reindex. Временная недоступность не удаляет links.
8. Удаление одной Local, одной remote и «везде» при дубликатах: ровно выбранные
   source targets, оставшиеся copies доступны; результаты частичного trash различимы.
9. Reconnect без logout и logout/login на тот же/другой сервер с тем же fileId;
   поздний upload старой сессии не восстанавливает старую связь.
10. Для hash/grouping: одинаковые bytes, одинаковый размер при разных bytes,
    EXIF-only edit, изменение во время чтения, нет permission/checksum, отмена и
    bounded memory на больших видео; выбранная политика не делает UI зависимым
    от завершения полного hash scan.
11. Отдельно #118: no-EXIF/null DATE_TAKEN около UTC/MSK полуночи, local-only день
    вне remote index и remote день с placeholders. Проверять сохранность URI на
    каждом этапе, не объявлять #118 устранённой из-за успеха collision-тестов.

Имеющиеся опорные тесты: core contract/exact priority и remote cardinality [S14],
Room links/reopen [S8], raw AUID [S15], permission/day projection [S19], aliases
update/retirement [S6], [S7], HTTP fixture для #131 [S9]. Это основания для регрессий,
а не утверждение, что перечисленные будущие сценарии уже покрыты или прогнаны.

## Решение, которое ещё нужно от пользователя

**Подтвердить ли, что две Local copies должны всегда оставаться двумя выбираемыми
объектами, даже при полностью одинаковых bytes, а неоднозначная remote copy —
отдельной до явной связи?** Рекомендация — да: это устраняет скрытые локальные
файлы без нового content I/O и сохраняет нынешний формат assets.

Если ответ да, перед реализацией отдельно выбрать dev reset или repair persisted
склеек с ограничениями выше и зафиксировать сужение определения объекта. Если ответ
нет, сначала утвердить UX набора copies и семантику удаления/загрузки; один hash
без этого не решает #115. Принятый компромисс #131 сохраняется в обоих случаях.
Отдельный отказ от эвристики #131 потребовал бы нового продуктового решения.

## Первичные источники

Ссылки ниже ведут на прочитанные исходники/тесты.

[S0]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/CONTEXT.md
[S1]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/core/media/src/main/kotlin/com/syrok0010/nextgallery/core/media/MediaItem.kt#L33-L39
[S2]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/core/media/src/main/kotlin/com/syrok0010/nextgallery/core/media/MediaIdentityRegistry.kt#L98-L172
[S3]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/main/java/com/syrok0010/nextgallery/feature/library/MediaLibraryProjection.kt#L24-L54
[S4]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/main/java/com/syrok0010/nextgallery/feature/library/local/LocalMediaSource.kt#L96-L244
[S5]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/docs/adr/0013-media-library-index.md
[S6]: https://github.com/syrok0010/NextGallery/blob/1c4c886b2939be9a38e3b2e68fef8450781dadb0/app/src/main/java/com/syrok0010/nextgallery/core/database/RoomMediaIdentityRegistry.kt#L21-L144
[S7]: https://github.com/syrok0010/NextGallery/blob/91b1e7365bb392ebe99b9f463984f97ec2c9d477/app/src/main/java/com/syrok0010/nextgallery/feature/timeline/persistence/TimelineCacheRepository.kt#L73-L131
[S8]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/main/java/com/syrok0010/nextgallery/core/database/RoomMediaIdentityRegistry.kt#L133-L182
[S9]: https://github.com/syrok0010/NextGallery/blob/1c4c886b2939be9a38e3b2e68fef8450781dadb0/app/src/main/java/com/syrok0010/nextgallery/feature/upload/UploadFile.kt#L25-L119
[S10]: https://github.com/syrok0010/NextGallery/blob/1c4c886b2939be9a38e3b2e68fef8450781dadb0/docs/adr/0018-operation-status.md
[S11]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/main/java/com/syrok0010/nextgallery/feature/timeline/persistence/TimelineCacheRepository.kt#L88-L99
[S12]: https://github.com/pulsejet/memories/blob/abea41653af2d9288e26c81ec1e25eccaca5b434/android/app/src/main/java/gallery/memories/mapper/SystemImage.kt#L215-L232
[S13]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/main/java/com/syrok0010/nextgallery/core/database/DatabaseEntities.kt#L51-L85
[S14]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/core/media/src/test/kotlin/com/syrok0010/nextgallery/core/media/MediaContractTest.kt
[S15]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/test/java/com/syrok0010/nextgallery/feature/timeline/local/LocalMediaSourceTest.kt#L159-L183
[S16]: https://github.com/syrok0010/NextGallery/blob/91b1e7365bb392ebe99b9f463984f97ec2c9d477/app/src/main/java/com/syrok0010/nextgallery/feature/deletion/MediaDeletion.kt#L23-L75
[S17]: https://github.com/syrok0010/NextGallery/blob/1c4c886b2939be9a38e3b2e68fef8450781dadb0/app/src/main/java/com/syrok0010/nextgallery/core/database/NextGalleryDatabase.kt#L10-L43
[S18]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/main/java/com/syrok0010/nextgallery/feature/library/local/LocalMediaMetadataEnrichment.kt
[S19]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/test/java/com/syrok0010/nextgallery/feature/timeline/remote/UnifiedTimelineProjectionTest.kt#L185-L282
[S20]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/main/java/com/syrok0010/nextgallery/feature/library/local/CanonicalTimelineTime.kt
[S21]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/main/java/com/syrok0010/nextgallery/feature/timeline/remote/MemoriesDtos.kt#L76-L111

Дополнительные закреплённые источники для проверки выводов:

- [ADR 0002](https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/docs/adr/0002-media-source-abstraction.md),
  [ADR 0017 main](https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/docs/adr/0017-link-uploaded-copies.md),
  [ADR 0017 в #145](https://github.com/syrok0010/NextGallery/blob/1c4c886b2939be9a38e3b2e68fef8450781dadb0/docs/adr/0017-link-uploaded-copies.md).
- [Reader и volume consistency](https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/main/java/com/syrok0010/nextgallery/feature/library/local/AndroidMediaStoreReader.kt),
  [local retirement #147](https://github.com/syrok0010/NextGallery/blob/91b1e7365bb392ebe99b9f463984f97ec2c9d477/app/src/main/java/com/syrok0010/nextgallery/feature/library/local/LocalMediaProjectionRepository.kt),
  [local scan #147](https://github.com/syrok0010/NextGallery/blob/91b1e7365bb392ebe99b9f463984f97ec2c9d477/app/src/main/java/com/syrok0010/nextgallery/feature/library/local/LocalMediaSource.kt#L122-L139).
- [Room links/reopen tests](https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/androidTest/java/com/syrok0010/nextgallery/core/database/RoomMediaIdentityRegistryTest.kt),
  [aliases #145 tests](https://github.com/syrok0010/NextGallery/blob/1c4c886b2939be9a38e3b2e68fef8450781dadb0/app/src/androidTest/java/com/syrok0010/nextgallery/core/database/RoomMediaAliasTest.kt),
  [retirement #147 tests](https://github.com/syrok0010/NextGallery/blob/91b1e7365bb392ebe99b9f463984f97ec2c9d477/app/src/androidTest/java/com/syrok0010/nextgallery/core/database/VanishedSourceAliasesTest.kt),
  [#131 HTTP fixture tests](https://github.com/syrok0010/NextGallery/blob/1c4c886b2939be9a38e3b2e68fef8450781dadb0/app/src/test/java/com/syrok0010/nextgallery/feature/upload/UploadFileTest.kt).
- [Timeline projection](https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/main/java/com/syrok0010/nextgallery/app/library/TimelineSnapshotProjection.kt),
  [assembler](https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/main/java/com/syrok0010/nextgallery/feature/timeline/TimelineSnapshotAssembler.kt#L84-L128),
  [local dayId](https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/main/java/com/syrok0010/nextgallery/feature/library/local/LocalMediaProjectionItem.kt#L25-L30),
  [upload copy selection в стеке](https://github.com/syrok0010/NextGallery/blob/91b1e7365bb392ebe99b9f463984f97ec2c9d477/app/src/main/java/com/syrok0010/nextgallery/feature/upload/UploadBatch.kt#L18-L25).
- [Memories timeline fields](https://github.com/pulsejet/memories/blob/abea41653af2d9288e26c81ec1e25eccaca5b434/lib/Db/TimelineQuery.php#L22-L29),
  [native BUID](https://github.com/pulsejet/memories/blob/abea41653af2d9288e26c81ec1e25eccaca5b434/lib/Db/TimelineQueryNativeX.php#L11-L16).
