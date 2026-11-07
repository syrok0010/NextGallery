# Устойчивая очередь ручных загрузок

Статус: **исследование и предложение**, не принятое архитектурное или продуктовое решение. Проверено 2026-11-01.
Scope: восстановление ручных upload-заданий и их последующих действий; без реализации, изменения Room schema, worker, Issues или поведения приложения.

## Рекомендация

Сделать **Room источником истины очереди**, а WorkManager — планировщиком восстановления и отложенных попыток. Экран из PR #152 остаётся представлением очереди. Журнал должен переживать процесс независимо от того, кто сейчас передаёт байты.
Для длинной передачи по явному действию пользователя целевой adapter исполнения — **UIDT JobScheduler на API 34+**, для API 31–33 — long-running foreground Worker. Обычный Worker обрабатывает короткие, прерываемые попытки и восстановление. Это одна очередь с разными adapters исполнения, а не несколько очередей.
Первый полезный срез сохраняет журнал и восстанавливает его при следующем открытии приложения, используя существующий foreground-путь. WorkManager и UIDT добавляются следующими срезами: сохранность намерения не должна ждать полной миграции исполнения.

Предлагаемая гарантия: принятое задание, подтверждённый результат и отмена сохраняются после process death; доступные незавершённые шаги повторяются **как минимум один раз** при допустимых условиях. Нет exactly-once, гарантированного срока старта, вечного foreground или автоматического продолжения после force-stop.
Цена: собственные состояния и reconciliation, версионируемый формат последующих действий, отдельный журнал пользовательских намерений и два платформенных adapters для долгих передач. Это сложнее «один файл = один Worker», зато UI, отмена, аккаунт и альбомы не зависят от жизненного цикла WorkRequest.

## Проверенная исходная точка и границы

- Основание документа: `origin/main` **65c3b77f0829eeea6f802fe9de2514984108efdc**. [Настройки Android][build]: `minSdk=31`, `targetSdk=36`, `compileSdk=37`; Room 2.8.4, WorkManager в [каталоге зависимостей][deps] отсутствует. Target не равен версии ОС: ограничения ниже применяются по указанным условиям.
- Текущий management-контракт исследован отдельно на PR [#152](https://github.com/syrok0010/NextGallery/pull/152), `feat/upload-management`, HEAD **185e15d0ed3557b8edbeafa50a3bb74db0d23830**, base `fix/vanished-source-aliases`. Его runtime не перенесён в этот docs PR.
- [UploadQueue][queue] содержит waiting/started/batches, StateFlow результатов и process-memory `sessionGeneration`; [UploadBatch/UploadFollowUp][batch] — объекты с зависимостями и callback-поведением. [QueuedUploads][handoff] передаёт партии сервису при видимом UI; отказ старта возвращает ожидание, отказ `startForeground` обрабатывается отдельно. Эти механизмы не являются persistent recovery.
- [UploadItem/runUploadFiles][item] сериализует `Call.cancel()` и принятие ответа монитором объекта: отменённый файл не попадает в `uploaded()`, уже принятый terminal-результат отменить нельзя. На ошибке оставшиеся файлы партии сейчас становятся failed; durable retry по файлам будет новым поведением.
- [Foreground service][fgs] использует `dataSync`, `START_NOT_STICKY`, последовательный drain и callbacks после PUT; локальный coroutine `worker` — не AndroidX Worker. [UploadFile][put] делает условный PUT, HEAD и при совпадении размера PROPFIND для fileId; каждый HTTP Call регистрируется в UploadItem для отмены.
- [UploadedCopiesFollowUp][link] хранит флаг и lambda refresh; [AlbumUploadFollowUp][albumfollow] — список исходов в памяти. [AlbumAdditions][albumadd] сначала создаёт/выбирает доступный свой альбом, затем отправляет локальные MediaId в upload с follow-up. После process death восстановить эти объекты из имени Worker невозможно.
- [PR #153](https://github.com/syrok0010/NextGallery/pull/153), HEAD **5288c42df1523aab92b959a239a2371cb0928e14**, уже различает URI чтения конкретного MediaStore volume и aggregate identity URI в [link adapter][link153]. Снимок задания должен сохранить обе роли. Не откатывать это различие при будущей миграции.
- [PR #154](https://github.com/syrok0010/NextGallery/pull/154), HEAD **64c39ed812077b8fb0c621714e18c5c9f5e68e7b**, оставляет offline cloud favorites за scope. Очередь здесь не является общей очередью favorite/delete/sync-команд.

Прочитаны [CONTEXT](../../CONTEXT.md), [vision](../product/vision.md), [MVP](../product/mvp.md), ADR [0010](../adr/0010-mvp-cache-policy.md), [0012](../adr/0012-feature-slices.md), [0014](../adr/0014-timeline-upload.md), [0016][adr16], [0017](../adr/0017-link-uploaded-copies.md), [0018][adr18], исследования [Nextcloud](api-nextcloud.md) и [Memories](api-memories.md). ADR 0016/0018 отсутствуют в основании main: ссылки закреплены на PR #152; его версии ADR 0014/0017 также прочитаны, их более новый upload-контракт описан выше.
Foreground повышает приоритет процесса, но не делает участок PUT → follow-up транзакцией: формулировку ADR 0014 в main о невозможности остановки между ними нельзя использовать как гарантию durability ([Android допускает уничтожение даже UIDT-процесса][uidt-stop]).

Сверка tracker: [#128](https://github.com/syrok0010/NextGallery/issues/128) — экран и отмена, не persistence; [#131](https://github.com/syrok0010/NextGallery/issues/131) — принятый компромисс equal-size 412 и link; [#94](https://github.com/syrok0010/NextGallery/issues/94#issuecomment-6540884046) — будущий выбор при конфликте, пока пропустить с «имя занято».
[PR #139](https://github.com/syrok0010/NextGallery/pull/139) блокирует logout при текущих операциях; [#53](https://github.com/syrok0010/NextGallery/issues/53#issuecomment-6540846193) позднее задаёт очистку **всех** данных при logout и ещё исследует механизм. Это расходится с сохранением local index в ADR 0010; здесь ADR не переписывается.
[#115](https://github.com/syrok0010/NextGallery/issues/115) и identity/hash/reset policy не реализуются. Просмотрены все Issues и поиск WorkManager/queue/очередь: самостоятельной задачи на durable upload нет; #72 относится к thumbnails, #20 — старый unified timeline. Новые Issues не создаются, существующие не закрываются.

## Ограничения Android: факты и выбор исполнения

Официальная [таблица data transfer][choices] рекомендует WorkManager для прерываемой фоновой работы, UIDT — для передачи, начатой пользователем, с видимым прогрессом и нежелательным прерыванием. Большое видео в ручной очереди соответствует второму сценарию; короткий retry может соответствовать первому.
На дату проверки [стабильный WorkManager — 2.12.0][wmrelease] (minSdk 24, совместим с minSdk 31 проекта). Это кандидат для будущей реализации, не добавленная зависимость; ограничения и обработку foreground timeout надо проверять на закреплённой тогда версии.

| Вариант | Проверенный платформенный контракт | Оценка для NextGallery |
| --- | --- | --- |
| Regular Worker | Прерываемая работа; deadline исполнения порядка 10 минут, остановки и дальнейшее планирование, constraints и backoff ([define][wmdefine], [stop][wmstop]) | Восстановление, короткие попытки и follow-ups; длинный PUT может постоянно начинаться заново |
| Expedited Worker | Начиная с WM 2.7: короткая срочная работа, отдельная quota; `RUN_AS_NON_EXPEDITED_WORK_REQUEST` даёт fallback, немедленность не гарантируется ([expedited][expedited]) | Не способ обойти лимиты для всей очереди видео; в первый срез не нужен |
| Long-running Worker | `setForeground()`/ForegroundInfo, foreground service плюс JobScheduler; тип нужен при target 34+; Android 16 может исчерпать job quota ([long-running][long]) | Совместимый fallback API 31–33; на новых ОС не выбирать единственным способом долгой ручной передачи |
| UIDT JobService | API 34+, `RUN_USER_INITIATED_JOBS`, `setUserInitiated(true)`, notification, network constraint, оценка байтов; запуск из видимого приложения либо разрешённого исключения ([UIDT][uidt]) | Предпочтителен для долгой явной передачи на 34+; собственные scheduling/recovery adapters, не универсальное выполнение любой фоновой попытки |
| Прямой foreground service | Сам по себе не обеспечивает persistent задания или retry; ограничения старта и `dataSync` сохраняются ([start][fgstart], [types][fgtypes]) | Сохранить как переходный executor первого среза; постоянный собственный scheduler дублировал бы AndroidX |

**Два независимых лимита.** На ОС Android 15+ при target 35+ работа `dataSync` FGS в фоне ограничена суммарными **6 часами за 24 часа** на приложение для данного типа, не на файл или Worker. `Service.onTimeout(int,int)` даёт несколько секунд на `stopSelf()`; возврат приложения на передний план сбрасывает этот таймер ([timeout][timeout]).
Long-running WM использует FGS и не освобождается от foreground-ограничений ([long][long], [choices][choices]). У собственного переходного FGS нужен onTimeout; у выбранной версии WM проверить системную обработку timeout и перевод задания в восстановимое состояние, не ждать возможности дописать весь журнал в callback.
На **ОС Android 16 / API 36, независимо от target**, jobs, в том числе одновременно с FGS, учитываются в runtime quota JobScheduler; top-start тоже не является вечным освобождением ([behavior changes all][a16]). UIDT освобождён от обычных job quotas, но останавливается из-за сети, thermal/system health, оценки длительности и нехватки памяти ([UIDT stop][uidt-stop]).

Для проекта с target 36 также актуальны: background FGS start restrictions с API/target 31+ ([start][fgstart]); `dataSync` type и `FOREGROUND_SERVICE_DATA_SYNC` при target 34+ ([types][fgtypes]); запрет запуска dataSync FGS из BOOT_COMPLETED при target 35+ ([types][fgtypes]). Reboot recovery не должен просто стартовать прежний сервис из receiver.
На API 33+ `POST_NOTIFICATIONS` влияет на drawer: разрешение не требуется для самого запуска FGS, но foreground notification обязательна; при отказе notice остаётся в Task Manager ([permission][notifications]). Существующий handoff уже стартует и после отказа. Не превращать отказ в потерю задания; UIDT notification и её видимость проверить отдельно по его контракту.
UIDT нельзя автоматически создать из любого фонового retry: разрешённые условия старта отличаются от FGS exemptions, наличие Activity в Recents недостаточно ([scheduling conditions][uidt-conditions]). При недоступном UIDT журнал остаётся waiting; короткая попытка допустима через WM, продолжение большого видео может потребовать явного «Продолжить» в видимом UI.

| Событие | Что можно обещать пользователю |
| --- | --- |
| Убийство процесса ОС | Журнал восстанавливается; scheduler может исполнить работу позднее, без обязательного callback при смерти. Частично отправленный PUT требует проверки результата ([WM][wm], [UIDT][uidt-stop]) |
| Reboot | WM сохраняет задания между reboot; запуск зависит от условий, разблокировки и системного расписания. URI/credentials могут оказаться недоступны; foreground старт не обещается ([WM][wm], [types][fgtypes]) |
| Swipe из Recents | Удаление task не равно force-stop; Service имеет отдельный `onTaskRemoved`, поведение зависит в том числе от `FLAG_STOP_WITH_TASK`. Для OEM не обещать одинаковую непрерывность ([Service][swipe]) |
| Force-stop приложения | Нельзя обещать самовозобновление до прямого/косвенного действия пользователя, снимающего stopped state; Android 15 также отменяет PendingIntent. После открытия — reconciliation ([stopped state][force]) |
| Stop в системном Task Manager | Для обычного FGS процесс завершается без callbacks, scheduled jobs сохраняют возможность запуска; для UIDT Stop предотвращает reschedule user-visible jobs. Это разные контракты, не кнопка cancel приложения ([FGS][userstop], [UIDT][uidt-stop]) |

При recovery нельзя обходить системный Stop UIDT новым WM wake. Читать доступную причину завершения/stop reason; если пользовательскую остановку нельзя надёжно отличить от аварии, сохранять unresolved работу для явного продолжения в UI, не обещать автоматический restart. `ApplicationExitInfo.REASON_USER_REQUESTED` полезен для диагностики, но не является сохранённой отменой конкретного файла ([FGS Stop][userstop]).

## Durable модель и маленький Interface

Предлагаемый deep module `DurableUploads` находится в `feature/upload`: seam между намерением пользователя/проекцией UI и хранением, HTTP, scheduler. Его Implementation скрывает leases, reconciliation, восстановление последующих шагов и версионирование данных.
UI и album caller не должны создавать WorkRequest, читать Room DAO, восстанавливать closures или знать версию Android. Предлагаемый Interface (эскиз, не готовый код):

```kotlin
interface DurableUploads {
    val state: Flow<UploadQueueView>
    suspend fun enqueue(request: UploadRequest): EnqueueResult
    suspend fun command(command: UploadCommand): CommandResult
}
// UploadRequest: MediaId selection + serializable, versioned post-upload intents.
// UploadCommand: Cancel(File|Batch|Queue), Retry(Selection), ClearFinished.
// EnqueueResult: durable Accepted(batchId, fileIds) или типизированный Rejected.
```

`enqueue` фиксирует снимок источников и владельца до ответа Accepted; DB failure возвращает Rejected, выбор в UI сохраняется. Идентификаторы batch/file UUID устойчивы, не process-static AtomicLong. Повтор одного UI-requestId после неопределённого ответа возвращает ту же партию; новый requestId означает новое намерение.
`state` — восстанавливаемая проекция Room плюс недолговечный live progress; `command` отвечает после durable commit. Повтор отмены безопасен; ClearFinished убирает только завершённые видимые записи, не pending follow-ups. Конфликт, ошибки доступа и storage failure возвращаются типами, локализация остаётся у UI/notification adapter.
Внутренние adapters: Room journal, MediaStore reader, HTTP transport, post-upload handlers, WM/UIDT/переходный FGS runners. Они имеют реальные различия платформ/хранилища; внешний Interface не разрастается методами каждого adapter. Исполнители вызывают внутренний drain, а не притворяются пользовательским `enqueue`.

Предлагаемые сохраняемые записи (это модель, **не миграция/схема**):

| Запись | Содержание и инвариант |
| --- | --- |
| Owner | Нормализованный server origin + DAV uid, случайный durable session owner token, active/revoked; credentials отдельно |
| Batch | UUID/requestId, owner token, порядок/время, cancellation marker, тип цели, версия intents, состояние итогов/показанности |
| File job | UUID, batchId, MediaId, URI чтения и source identity key, имя/MIME, известные size/mtime/generation, зафиксированный destination; состояние, attempt, nextAttemptAt |
| Transfer receipt | accepted-created / accepted-existing-by-size, remote path/fileId/etag при наличии, observation time; отдельно uncertain outcome и причина неполноты |
| Post-upload step | fileId задания + kind/version/payload, pending/running/done/suppressed/needs-attention, attempt/result/error; уникальность логического шага |
| Execution lease | owner executor id, fencing revision, срок/heartbeat и stop reason; прежний executor не может принять результат поверх новой revision |
| Wake outbox | owner + монотонный wake generation, dueAt, причина, состояние доставки scheduler; создаётся транзакционно с работой |

Состояния файла: `pending → running → accepted` либо `waiting-retry / needs-attention / cancelled`; terminal transfer outcome хранится отдельно от post-upload outcome. `uncertain` — обязательная recovery-фаза/причина, не синоним failed или uploaded. Name conflict — отдельный результат; cancelled — намерение пользователя, не ошибка сети.
Состояние партии/очереди выводится из файлов и шагов, а cancel/показанность результата сохраняются явно. Партия не finished, пока есть runnable или требующие внимания последующие шаги; частичный успех не стирается ошибкой следующего файла. Счётчики выводятся из записей, не инкрементируются повторно при replay.

В WorkData передавать только неприватные ids/version, не список URI, credentials, closures или байты. AndroidX Data — lightweight payload с лимитом **10 × 1024 байт в сериализованном виде**, не БД ([reference][data], [исходник константы][datasource]); ограничения те же у progress Data ([progress][progress]). WorkInfo описывает исполнение WorkRequest, не полноту партии и не историю пользовательских результатов.
Прогресс выдавать в memory Flow с throttling порядка 0,5–1 с; в Room писать смены фаз, receipt, cancel и редкие checkpoints, не каждый блок байтов. После рестарта незавершённый PUT начинает целиком с 0; checkpoint не означает HTTP byte resume. `setProgress` тоже пишет в БД и не заменяет журнал ([progress][progress]).
Destination разрешается и сохраняется **до первого сетевого эффекта**; если config ещё недоступен, job остаётся pending, а Accepted относится к намерению. После начала PUT изменение `timeline_path` не перенаправляет retry в другую папку. Первый допустимый путь и ошибки отсутствующей папки сохраняют контракт PR #152/#117; авторизацию и URL builder повторно проверять по owner, не сохранять секреты в адресе.

**Хранение важнее cache policy.** В main [общая Room БД][database] допускает destructive fallback; ADR 0010 рассматривает её как восстанавливаемый cache. Нельзя поместить единственную копию пользовательских заданий под такую гарантию: missing migration действительно может удалить данные ([Room migrations][roommigration]).
Предложение: отдельная небольшая Room `UploadQueueDatabase` с обычными migrations и fail-closed ошибкой, без destructive fallback. Это осознанное предлагаемое отступление от «одна БД» ADR 0010: сохранность намерений отделена от пересоздания cache; при принятии потребуется ADR. Альтернатива — ужесточить migrations всей общей БД, но это расширяет первый срез и связывает очередь с cache migrations.
Цена отдельной БД: link в identity registry нельзя коммитить одной транзакцией с step-state. Повторяемый link + durable step receipt решают это через replay, а не через выдуманную межбазовую транзакцию. Очередь/owner token/spool исключать из cloud backup и device transfer; иначе восстановленный на другом устройстве URI не является тем же источником ([Android backup rules][backup]). Runtime-настройки сейчас не меняются.

## Два исполнителя, lease и разрыв Room → scheduler

Claim следующего due-файла/шага — одна Room-транзакция CAS: owner active, нет cancel, ожидаемая revision, нет действующего lease. Один serial drain на owner, FIFO партий; sleeping retry не должен блокировать готовые файлы следующей партии. `maxConcurrency=1` — предложенный первый default.
После каждой сетевой операции принятие receipt проверяет тот же owner/lease/revision и cancel marker транзакционно. Второй Worker не получает уже claimed job. При остановке best effort закрывает Call и освобождает lease; при смерти lease восстанавливается с проверкой прежнего executor, переводя незавершённый сетевой шаг в uncertain.
Heartbeat/expiry не доказывает, что старый HTTP больше не исполняется: при замороженном executor два запроса могут пересечься. Fencing защищает **локальные записи**, условный PUT и reconciliation — повторные сетевые эффекты. Не заявлять, что unique work или lease обеспечивают exactly-once на сервере.
Проверка «готовых файлов нет» и освобождение drain lease выполняются одной транзакцией; enqueue до неё будет замечен drain, после неё — новым wake. Это закрывает lost wake при завершении обработчика, но не заменяет восстановление scheduler.

Room и внутренняя БД WorkManager не участвуют в общей транзакции. Предлагаемый протокол:

1. В одной транзакции сохранить задание/изменение и outbox wake с generation; затем сообщить UI durable Accepted.
2. Dispatcher после commit вызывает `enqueueUniqueWork("uploads/<owner>/<generation>", KEEP, request)`; только после успешной Operation помечает эту **generation** доставленной. Повтор доставки того же wake безопасен.
3. Новое изменение получает новую generation; WorkRequest — сигнал drain, не отдельная копия файла. Два wake могут исполниться, но CAS оставит одного владельца работы. Старые законченные wake/outbox очищаются ограниченно.
4. На старте приложения и каждом runner entry reconciliation сверяет active owner, jobs, leases, outbox и WorkInfo; reschedule только действительно pending/due записи. Отложенный retry имеет durable nextAttemptAt и собственный wake.
5. Для восстановления без нового открытия заранее зарегистрировать независимый lightweight periodic WM reconciler без сетевого constraint: он только доставляет outbox, не грузит видео. Его наличие подтвердить перед первым Accepted фонового среза; недоступность регистрации видна как degraded scheduling, журнал остаётся цел.

Если процесс умер **после Room commit, до WM enqueue**, задание уже видно после следующего открытия; до этого оно может ждать reconciler. Periodic WM имеет минимум 15 минут и неточный срок выполнения, constraints/Doze/quota добавляют задержку ([periodic][wmdefine]); никакой outbox сам процесс не запускает.
Если смерть произошла **после WM enqueue, до отметки outbox**, повторяется тот же unique wake, а не пользовательское задание. Если обе БД сохранены, WorkManager сам владеет своим persistent расписанием ([WM][wm]); startup reconciliation всё равно проверяет логическую работу.
Один вечный unique drain с `KEEP` дешевле, но может потерять пробуждение между empty-check и завершением WorkRequest; `REPLACE` останавливает текущий PUT, `APPEND` наследует failed/cancelled prerequisites. Поэтому предлагаются поколения wake; `APPEND_OR_REPLACE` возможен как альтернатива цепочке, но не решает разрыв двух БД ([unique policies][wmunique]).
Для UIDT отдельный scheduler adapter соблюдает допустимость user initiation; outbox не даёт права незаметно стартовать UIDT. `setPersisted` поддерживается платформой, но не отменяет constraints и пользовательский Stop; reboot-стратегию UIDT проверять отдельно, базовое восстановление остаётся у журнала/WM ([UIDT][uidt], [JobInfo][jobinfo]).

## URI, снимок источника и spool

Текущий вход — MediaStore из таймлайна/локальной папки, не новый picker. Для API 31–32 используется storage read permission, для API 33+ — media permissions; при API 34+/target 34+ доступ может быть частичным и изменяться. Проверять реальный доступ перед каждой попыткой, не хранить granted=true как вечный факт ([MediaStore][media], [partial access][partial]). Полная поддержка Selected Photos Access остаётся #19.
Photo Picker даёт иной grant: по умолчанию до остановки приложения/перезапуска устройства, для фоновой загрузки официально предлагается `takePersistableUriPermission`; SAF позволяет взять только **предоставленный** persistable grant ([picker][picker], [SAF][saf]). Эта операция не делает произвольный MediaStore URI бессрочным и не заменяет runtime permission. Перемещение/удаление документа, отзыв доступа или исчезновение volume всё равно могут лишить байтов ([SAF][saf], [partial][partial]).
Job хранит MediaId намерения отдельно от URI чтения, identity URI, доступных size/mtime/generation и destination. При retry перечитать доступность/metadata и открыть stream; PermissionDenied/SourceMissing → needs-attention, без бесконечного network retry. Возвращённое разрешение допускает явный retry того же задания после проверки источника.
Если размер/mtime/generation изменились, не отправлять новые байты под старым намерением: needs-attention и повторный выбор. Без hash это обнаруживает лишь наблюдаемые изменения, **не доказывает неизменность содержимого**; даже одинаковые metadata могут скрывать замену, возможна правка во время чтения. Сохранять это ограничение и при 412 recovery, не расширять #115.

Spool в app-private **не cache** storage фиксирует байты для retry и уменьшает зависимость от будущего grant, но требует копирования всего видео до передачи, disk budget, handling ENOSPC, orphan cleanup и удаления после receipt/закрытия шагов/logout. Это отдельное пользовательское хранение, а не бесплатный persistable grant.
Первый срез рекомендован **без spool**: существующий MediaStore URI, проверка metadata/доступа на каждой попытке, явное needs-attention. Не обещать сохранность удалённого/отозванного оригинала. Spool добавлять лишь если продукт потребует «принято в очередь = байты уже сохранены приложением»; нужна отдельная disk/retention policy. Большие файлы всегда читать потоково.

## Владение аккаунтом и logout

Без multi-account UI всё равно нужен durable owner: `(server origin, DAV uid, случайный session token)`. Token создаётся при успешном новом Login Flow и хранится рядом с состоянием сессии; переживает process death, отзывается до очистки logout. Не использовать память `SessionStore`/сравнение credentials как единственную долгоживущую границу.
Credentials остаются в существующем secure store и загружаются только при исполнении. Каждый claim, начало HTTP и durable принятие результата проверяют active owner; локальные link writes проверяют token внутри транзакции. Старый ответ может подтвердить отправку старому серверу, но не публиковаться в новом аккаунте. Отзыв не отменяет уже отправленные байты.
Повторный вход **в тот же account** после logout получает новый token: старые задания не оживают автоматически. Перезапуск процесса без logout сохраняет token и восстанавливает задания. Смена credentials без logout требует явного согласованного lifecycle; нельзя молча перепривязать jobs по совпавшему uid или server URL.

Текущий #139 блокирует logout даже на waiting upload; durable очередь без сети может ждать бесконечно. Оставлять пользователя навсегда заблокированным нельзя считать уже принятым контрактом. **Предложение для согласования:** logout сначала требует явного отказа от pending/needs-attention работ, durable revoke подавляет новые шаги, активные Calls останавливаются, затем применяется выбранная в #53 полная очистка.
Это не принимает новый logout UX и не выбирает `clearApplicationUserData()` вместо ручной очистки: первый срез сохраняет текущую блокировку и требует отменить очередь перед выходом; результат её отмены durable. Pending follow-ups должны завершиться либо быть явно подавлены, а не держать невидимую вечную блокировку. Общая реализация #53 остаётся отдельной задачей.

## PUT, неопределённый исход и последующие шаги

Условный `PUT If-None-Match: *` не перезаписывает уже существующее представление; повтор может получить `412`. PUT идемпотентен по HTTP-смыслу желаемого состояния, но это **не** exactly-once учёт UI или доказательство равенства байтов ([RFC 9110 §9.2.2][http-idempotent], [§13.1.2][http-ifnone]). [Nextcloud DAV docs][nextcloud] подтверждают PUT/PROPFIND; специфические ответы проекта закреплены кодом и локальным исследованием API, не универсальны для всех версий сервера.

| Точка сбоя | Восстановление по предложенному контракту |
| --- | --- |
| До отправки PUT | Перечитать owner/source/cancel, начать новую попытку целиком |
| Байты отправлены, timeout/cancel/смерть до receipt | Сохранить/восстановить uncertain; проверить тот же destination. Отмена сокета не доказывает отсутствие файла на сервере |
| Сервер ответил 201, процесс умер до Room commit | Повтор/проверка того же пути: 412 + HEAD; accepted-existing-by-size только по действующему компромиссу #131, с пометкой доказательности |
| Receipt принят в Room, процесс умер до follow-up | Не повторять PUT: исполнить сохранённые pending steps, восстановить прежний результат в UI |
| Follow-up завершён удалённо, step receipt не сохранён | Повторять только шаг с проверенным повторным эффектом/проверкой membership; иначе needs-attention, без нового PUT |

Сохраняется политика #131/#94: **412 + известный равный размер** принимается как existing и допускает link; другой или неизвестный размер — name conflict, пропуск без rename/overwrite. В [коде PR #152][put] ID дополнительно берётся PROPFIND только после проверки размера; отсутствие fileId не отменяет принятый upload, но оставляет link-step unresolved.
Недоступный HEAD при recovery означает, что проверка не состоялась: waiting-retry при transient причине, needs-attention при постоянной; это не доказанный equal-size успех. Такой error detail богаче текущего `sameRemoteSize=false`; не повторять безусловный PUT поверх неизвестного состояния.
Без hash можно обещать защиту от перезаписи и сохранение наблюдённого результата; нельзя обещать точное соответствие облачных байтов исходным, особенно при замене файла тем же размером. ETag без заранее известного эталона тоже не доказательство равенства. Новый hash/reset/identity механизм не предлагается к реализации; спорные исходы видны пользователю для проверки и явного retry.

**Отмена и 201 имеют локальную точку принятия.** Транзакция cancel и транзакция accept соревнуются за один nonterminal transfer-state:

- cancel первым: сохраняет cancelled, подавляет не начатые follow-ups и запрещает retry; текущий Call best effort отменяется. При позднем 201 можно сохранить observation «файл мог/успел появиться», но не запускать album/link для cancelled job и не считать отмену удалением с сервера;
- accept первым: сохраняет receipt и набор pending steps атомарно; файл уже accepted, последующий file cancel не переписывает его в cancelled (сохраняет правило PR #152). Queue/batch cancel отменяет оставшиеся nonterminal файлы, а не откатывает принятые;
- для отдельно отменяемых ещё не начатых post-upload intents нужна отдельная явная команда/политика. Начатый удалённый album COPY нельзя гарантированно отозвать; это также uncertain/reconciliation, не автоматический DELETE.

Serializable intents первого формата: `LinkUploadedCopy(v1, localIdentityKey, mediaId)` и `AddToAlbum(v1, owner, albumIdentity/address)`. Remote fileId/path приходят из receipt; `RefreshLibrary` — повторяемая invalidation по завершённым изменениям, не сериализованная lambda. Версия неизвестна → needs-attention, а не молчаливое забывание.
Создание альбома пока остаётся **перед** enqueue, как в AlbumAdditions: очередь принимает уже разрешённую цель. Durable create-album, cloud-only album additions, удаления и «освободить место после upload» не входят в первый формат.
Успешный receipt и **все** pending steps создаются одной транзакцией; шаги имеют независимые retry/result. `finished(missed)` заменяется вычисляемым durable итогом партии: все отменены → ни одного uploaded-step, но завершённая партия и корректный счётчик missed/cancelled. Типизированные результаты не хранить как локализованные исключения/UiText.

Для link повтор использует существующий registry contract и owner guard; уже созданную связь не перепривязывает к чужому объекту ([link][link], [ADR 0017](../adr/0017-link-uploaded-copies.md)). Для альбома повторный COPY нельзя признать безопасным по одному HTTP коду: [writer][albumwriter] трактует 409 как already-present только после проверки существования альбома.
Первичный [исходник Photos][photos] проверяет наличие source fileId в альбоме и выдаёт Conflict; это проверка конкретной ревизии Photos, **не универсальная гарантия любой установленной версии**. Исследование API проекта фиксирует такой же 201 → повтор 409 на прежнем стенде; в этой задаче стенд не запускался.
Для автоматического replay AddToAlbum требуется сопоставить **тот же album identity и source fileId**, а не только имя/наличие коллекции: album могли удалить и создать заново, исходный path могли заменить. Проверять текущие membership/идентичность перед признанием 409 успехом; если серверный adapter не может подтвердить их, step → needs-attention и явная проверка/повтор. Усиление writer — отдельный срез, не приписываемая ему сегодня гарантия.
Автоматический compensating DELETE недопустим: файл мог существовать раньше, иметь другие членства или быть изменён. Отмена не является разрешением удалить оригинал, membership либо чужие данные; действующие deletion contracts ADR 0016 сохраняются.

## Retry и отображение

Предлагаемый default: сеть `CONNECTED`, одна передача, без обязательных Wi-Fi/charging. Это **предложение**, пользователь эти предпочтения не выбирал. Network constraint не гарантирует доступность сервера и может остановить исполнение после старта ([constraints][wmconstraints]).
Transient I/O, HTTP 429 и пригодные для повтора 5xx → per-file exponential backoff с jitter; постоянные ошибки/неподдерживаемый ответ не ретраить слепо. 401/403 → needs-attention/auth-or-access, SecurityException/source missing → source attention, 412 → указанная conflict/equal-size политика. Ошибка одного файла не обнуляет успехи и не проваливает все оставшиеся.
Сохранять attempts/nextAttemptAt/error category; предложенный автоматический бюджет — 5 неудачных сетевых попыток на файл, затем явный retry. Scheduler stop из-за constraints/quota не расходует бюджет как пользовательская ошибка. Числа требуют продуктового согласования; retry шага альбома не повторяет upload.
Уважать валидный `Retry-After` (delay-seconds либо HTTP-date) для 429/503, не раньше него; это нижняя граница, не обещание времени запуска ([RFC 6585 §4][http429], [RFC 9110 §10.2.3][http-retry]). Локальный nextAttemptAt задаёт per-file срок; WM backoff относится ко всему WorkRequest и сам по себе не моделирует очередь ([backoff][wmbackoff]).
Фиксировать connect/read/write и конечный attempt deadline с учётом размера: текущий [transport][transport] использует 20/30/30 с, это не договорённость о полном deadline большого видео. Числа проверить на реальной сети при реализации. Отменять активный HTTP при coroutine stop, хранить uncertain при возможном удалённом эффекте; после stop результат Worker может игнорироваться ([stop][wmstop]).
Кнопки Cancel в приложении/уведомлении сначала вызывают durable command и лишь затем best effort отменяют scheduler/Call. Не подключать UI напрямую к `cancelWorkById`/`createCancelPendingIntent`: WorkInfo cancellation не заменяет cancellation marker. System stop/constraint/quota/REPLACE не записывают userCancelled; сохраняется отдельный stop reason.

UI PR #152 мигрирует на `state`: `pending`, `running`, `waiting-retry` с причиной/сроком, `needs-attention`, `cancelled`, `finished` с деталями uploaded/existing/conflict/step errors. Текущий экран, вход из notification/плашки, previews и три области отмены сохраняются; UUID заменяют process-local keys.
Результаты восстанавливаются после рестарта, просмотр/очистка истории не отменяет задания. История без обещания хранения навсегда; предложено сохранять до явной очистки завершённых или logout, с отдельной будущей retention policy. Не скрывать accepted upload за ошибкой album/link: «файл загружен, добавление в альбом требует повтора».
Первый срез не обещает pause/resume с байта, сохранность исчезнувшего источника, вечную историю, автосинк новых фото, offline favorite queue, неограниченный background runtime или продолжение после force-stop без взаимодействия пользователя.

## Небольшие вертикальные реализации

Это план, не созданные Issues. Каждый срез даёт отдельный проверяемый пользовательский результат; последовательность не требует переносить код sibling PR в docs-ветку.

1. **Перезапуск не теряет ручную очередь.** После интеграции актуального management-контракта: journal + owner token + сериализуемые intents, проекция экрана и существующий foreground executor. Durable enqueue/cancel/accept; восстановление на следующем открытии, повтор целого PUT через проверку uncertain. Acceptance: после kill видны прежние партии, принятые результаты, отмена и pending files; receipt до crash не требует нового PUT. Для альбомной цели intent сохраняется; если replay не подтверждён, виден needs-attention вместо потери callback/ложного успеха. Зависимости: согласие на retention/source limits и безопасное хранение; WM/UIDT не нужны.
2. **Альбомный результат восстанавливается отдельно от upload.** Step runner для link/AddToAlbum, проверка album identity/membership, retry и durable summary; unknown outcome явно отделён от failed. Acceptance: kill после COPY до step commit не дублирует членство и не повторяет PUT; исчезнувший/пересозданный альбом даёт attention; все отменённые файлы не запускают link/album. Зависимость: срез 1, проверка конкретного server contract перед разрешением automatic replay. Не включает очередь cloud-only album действий.
3. **Ожидание сети и retry переживают закрытый UI.** WM adapter, outbox generations, periodic/startup reconciliation, constraints, finite attempts; regular Workers для коротких попыток, long-running fallback API 31–33. Acceptance: crash в обоих разрывах Room↔WM, два concurrent wake и enqueue на завершении drain не теряют файл; сеть вернулась → due работа может быть запланирована без повторного выбора; UI объясняет quota/defer. Зависимости: 1; 2 нужен для automatic album replay, иначе шаг остаётся attention.
4. **Долгий ручной upload на актуальном Android.** UIDT API 34+, routing по user initiation, notification и estimated bytes; проверенный timeout/stop recovery fallback. Acceptance: API 31–33 используют foreground Worker, 34+ явная большая передача — UIDT; API 35 dataSync timeout и API 36 quota не дают crash-loop/ложную отмену, запрещённый UIDT start сохраняет waiting и понятное продолжение. Зависимость: 3; общий drain и журнал не меняются по платформе.
5. **Выход при отложенных заданиях имеет конечный UX.** Только после продуктового решения #53: явное прекращение pending intents, durable revoke, прекращение отправки и полная очистка; same-account relogin не поднимает старые jobs. Acceptance: без сети можно выйти выбранным пользователем способом; поздний ответ не пишет cloud identity в новую сессию, local index очищается согласно решению #53. Зависимость: 1 и согласование logout; не блокирует 2–4 при сохранении текущего правила «сначала отменить очередь».

## План проверки через Interface

Тесты вызывают `enqueue/command`, наблюдают `state`, переоткрывают production Room journal и используют управляемые HTTP/source/scheduler adapters; проверяют пользовательский workflow, не дублируют DAO implementation. На платформе тот же Interface доступен UI/notification. Здесь этот план **не исполнялся**.

| Сценарий/точка инъекции | Ожидаемая проверка |
| --- | --- |
| Kill после job commit до wake; после WM commit до outbox ack | Accepted восстанавливается; reconciliation доставляет сигнал без новой партии; задокументирована задержка до startup/periodic |
| Kill до PUT, во время bytes, после server 201 до receipt | Pending/uncertain восстановлены, повтор условный; другой размер конфликт, equal-size принят с ограничением доказательности |
| Kill после receipt до link; после link до step commit | Нет нового PUT, link replay не меняет MediaId, durable итог не теряет успех |
| Два runners, истёкший lease, поздний ответ старой revision | CAS/fencing не принимают два результата; возможное пересечение HTTP не выдаётся за exactly-once |
| User cancel файла/партии/очереди + рестарт | Marker и suppression сохранены; новые файлы после CancelQueue не отменяются задним числом; полностью отменённая waiting-партия завершена без сервиса |
| Cancel ↔ 201 в обоих порядках Room commit | Cancel wins → нет album/link; accept wins → прежний receipt и pending steps сохранены; нет compensating DELETE |
| Scheduler stop, network loss, quota/timeout против user cancel | Первые оставляют retry/uncertain и stop reason; только durable command создаёт cancelled; Call закрывается |
| Отзыв MediaStore permission/picker grant, исчезновение volume, изменённые bytes | Нет бесконечного network retry/молчаливой загрузки нового источника; attention и явная перепроверка после возврата доступа |
| Restart без logout; logout/relogin того же uid; другой server | Owner сохраняется только в первом случае; старый token не возобновляет jobs и не записывает link/результат в новую сессию |
| Album COPY success → kill; повтор 409; album recreated/path replaced | Подтверждение точного membership либо attention; uploaded не теряется и не повторяется; нет предположения «любой 409 = успех» |
| Reboot, swipe, force-stop, FGS/UIDT Task Manager Stop | Проверить раздельно API 31/33/34/35/36: durable state, допустимость старта и возобновление после явного открытия; не требовать старта в force-stopped state |
| Network 429/503 Retry-After, перманентный auth/permission error | Не раньше server delay; бюджет по файлу, остальные продолжают; auth/permission не превращаются в бесконечный retry |
| Quota exhausted / FGS onTimeout / UIDT start refused | Нет tight reschedule-loop, ложного finished или userCancelled; уведомление/экран показывают ожидание и путь продолжения |
| Journal migration failure, ENOSPC, cache reset, backup restore | Нельзя ответить Accepted без записи; queue не уничтожается cache fallback; восстановленный backup не начинает чужие/устаревшие задания |

## Оставшиеся продуктовые решения

1. Принять ли первый контракт без spool: очередь сохраняет **намерение**, а доступ к исходным байтам проверяется заново; иначе нужны disk budget и копирование перед Accepted?
2. Принять ли proposed defaults: любая доступная сеть, без charging requirement, одна передача, 5 автоматических неудачных попыток и затем ручной retry? Wi-Fi-only и лимиты мобильной сети пока не согласованы.
3. Как завершать persistent pending очередь при logout: сохранить блокировку до отмены либо встроить явный отказ от работ в logout? Общую полную очистку пользователь уже выбрал в #53; новый UX её достижения здесь не принят.

Рекомендация отделена от уже принятых #131/#94: новый выбор идентичности, rename, hashes, reset policy и offline favorite mutations не требуются для рассмотрения этого документа. После согласования оформить затронутое ADR и брать вертикальные срезы по одному.

## Источники и пределы проверки

Официальные страницы Android/AndroidX, RFC и Nextcloud открыты по HTTP 2026-11-01; API-условия сверены с их текстом. Код проверен чтением закреплённых Git objects, tracker — live через `gh`, без переноса runtime. Photos source — указанная ревизия upstream, не версия тестового сервера.
Проверки этого docs PR: `git diff --check`, существование локальных doc-links, Git SHA/пути code-links и официальные URL. Сборки, JVM/instrumentation tests, прототипы, устройства, эмуляторы, стенд и реальные process-death/network сценарии **не запускались**. Текст не является доказательством реализации или end-to-end гарантии.

[build]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/build.gradle.kts#L10
[deps]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/gradle/libs.versions.toml
[queue]: https://github.com/syrok0010/NextGallery/blob/185e15d0ed3557b8edbeafa50a3bb74db0d23830/app/src/main/java/com/syrok0010/nextgallery/feature/upload/UploadQueue.kt
[batch]: https://github.com/syrok0010/NextGallery/blob/185e15d0ed3557b8edbeafa50a3bb74db0d23830/app/src/main/java/com/syrok0010/nextgallery/feature/upload/UploadBatch.kt
[item]: https://github.com/syrok0010/NextGallery/blob/185e15d0ed3557b8edbeafa50a3bb74db0d23830/app/src/main/java/com/syrok0010/nextgallery/feature/upload/UploadItem.kt
[handoff]: https://github.com/syrok0010/NextGallery/blob/185e15d0ed3557b8edbeafa50a3bb74db0d23830/app/src/main/java/com/syrok0010/nextgallery/feature/upload/QueuedUploads.kt
[fgs]: https://github.com/syrok0010/NextGallery/blob/185e15d0ed3557b8edbeafa50a3bb74db0d23830/app/src/main/java/com/syrok0010/nextgallery/feature/upload/UploadForegroundService.kt
[put]: https://github.com/syrok0010/NextGallery/blob/185e15d0ed3557b8edbeafa50a3bb74db0d23830/app/src/main/java/com/syrok0010/nextgallery/feature/upload/UploadFile.kt
[link]: https://github.com/syrok0010/NextGallery/blob/185e15d0ed3557b8edbeafa50a3bb74db0d23830/app/src/main/java/com/syrok0010/nextgallery/feature/upload/UploadedCopiesFollowUp.kt
[albumfollow]: https://github.com/syrok0010/NextGallery/blob/185e15d0ed3557b8edbeafa50a3bb74db0d23830/app/src/main/java/com/syrok0010/nextgallery/feature/albums/AlbumUploadFollowUp.kt
[albumadd]: https://github.com/syrok0010/NextGallery/blob/185e15d0ed3557b8edbeafa50a3bb74db0d23830/app/src/main/java/com/syrok0010/nextgallery/feature/albums/AlbumAdditions.kt
[albumwriter]: https://github.com/syrok0010/NextGallery/blob/185e15d0ed3557b8edbeafa50a3bb74db0d23830/app/src/main/java/com/syrok0010/nextgallery/feature/albums/MemoriesAlbumWriter.kt
[link153]: https://github.com/syrok0010/NextGallery/blob/5288c42df1523aab92b959a239a2371cb0928e14/app/src/main/java/com/syrok0010/nextgallery/feature/upload/UploadedCopiesFollowUp.kt
[database]: https://github.com/syrok0010/NextGallery/blob/65c3b77f0829eeea6f802fe9de2514984108efdc/app/src/main/java/com/syrok0010/nextgallery/core/database/NextGalleryDatabase.kt#L40
[transport]: https://github.com/syrok0010/NextGallery/blob/185e15d0ed3557b8edbeafa50a3bb74db0d23830/app/src/main/java/com/syrok0010/nextgallery/core/network/NextcloudTransport.kt
[adr16]: https://github.com/syrok0010/NextGallery/blob/185e15d0ed3557b8edbeafa50a3bb74db0d23830/docs/adr/0016-delete-selection.md
[adr18]: https://github.com/syrok0010/NextGallery/blob/185e15d0ed3557b8edbeafa50a3bb74db0d23830/docs/adr/0018-operation-status.md
[photos]: https://github.com/nextcloud/photos/blob/bd23dd102b5c34ac0b17130712349a1ec3fdd07d/lib/Sabre/Album/AlbumRoot.php#L44
[choices]: https://developer.android.com/develop/background-work/background-tasks/data-transfer-options#use-uidt
[wmrelease]: https://developer.android.com/jetpack/androidx/releases/work#2.12.0
[wm]: https://developer.android.com/develop/background-work/background-tasks/persistent
[wmdefine]: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
[expedited]: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#expedited
[wmstop]: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work#stop-worker
[wmconstraints]: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#work-constraints
[wmbackoff]: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#retries_backoff
[wmunique]: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work#conflict-resolution
[long]: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
[uidt]: https://developer.android.com/develop/background-work/background-tasks/uidt
[uidt-stop]: https://developer.android.com/develop/background-work/background-tasks/uidt#stopping-uidt
[uidt-conditions]: https://developer.android.com/develop/background-work/background-tasks/uidt#conditions-that-allow-scheduling
[jobinfo]: https://developer.android.com/reference/android/app/job/JobInfo.Builder#setPersisted(boolean)
[timeout]: https://developer.android.com/develop/background-work/services/fgs/timeout#timeout-behavior
[a16]: https://developer.android.com/about/versions/16/behavior-changes-all#job-quota-opt
[fgstart]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
[fgtypes]: https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync
[notifications]: https://developer.android.com/develop/ui/compose/notifications/notification-permission#exemptions
[force]: https://developer.android.com/about/versions/15/behavior-changes-all#enhanced-stop-states
[swipe]: https://developer.android.com/reference/android/app/Service#onTaskRemoved(android.content.Intent)
[userstop]: https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping
[data]: https://developer.android.com/reference/androidx/work/Data#MAX_DATA_BYTES()
[datasource]: https://github.com/androidx/androidx/blob/72cb5f07433509bb34b86b554d101bbf5fff379a/work/work-runtime/src/main/java/androidx/work/Data_.kt#L564
[progress]: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/observe
[roommigration]: https://developer.android.com/training/data-storage/room/migrating-db-versions#handle-missing-migrations
[backup]: https://developer.android.com/identity/data/autobackup#IncludingFiles
[media]: https://developer.android.com/training/data-storage/shared/media#request-permissions
[partial]: https://developer.android.com/about/versions/14/changes/partial-photo-video-access#permission-state
[picker]: https://developer.android.com/training/data-storage/shared/photo-picker#persist-media-file-access
[saf]: https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions
[http-idempotent]: https://www.rfc-editor.org/rfc/rfc9110.html#section-9.2.2
[http-ifnone]: https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.2
[http-retry]: https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3
[http429]: https://www.rfc-editor.org/rfc/rfc6585.html#section-4
[nextcloud]: https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/basic.html
