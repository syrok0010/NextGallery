# Durable действия после upload: #158

## Проверенный серверный контракт

01.11.2026: `http://test.syrok/`, uid `test`, Nextcloud 34.0.5,
Photos 7.0.0, Memories 10.1.0. Стенд не останавливался и не перезапускался.
[Записанные запросы](api-trace.json) содержат только одноразовые task-объекты,
без Authorization/app password.

- Альбом не отдаёт DAV resource-id/fileid/etag; эти свойства имеют 404 propstat.
- Memories `album_id` поменялся 36→37 при удалении/создании того же имени.
- COPY добавил fileId 338, а повтор дал 409. Точное membership доказано отдельным
  Depth:1: fileId 338, href `338-durable158-contract.txt`, исходный путь
  `/Photos/durable158-contract.txt` в успешном propstat.
- Существование альбома и 409 не доказывают нужного участника.

Upstream проверен отдельно по закреплённому Photos
`bd23dd102b5c34ac0b17130712349a1ec3fdd07d`: `PropFindPlugin`, `AlbumRootBase`,
`AlbumRoot`; установленный код стенда прочитан независимо. SHA256 скачанных
upstream файлов: PropFindPlugin
`71b0843079329926e90cdef13dbf7c8c32d03aba456a710550da7d4388563350`,
AlbumRootBase `faa89d445aadbc87762b1d77902830dd489da04ac68ce04edb5b6849b80ea291`.
Установленный Memories `Db/AlbumsQuery::getList` выбирает `photos_albums.album_id`
и ограничивает собственные альбомы uid; `count` зависит от индексации и здесь не
используется как membership proof.

## Принятая граница

[ADR 0020](../../adr/0020-upload-follow-up-reconciliation.md) разрешает только
проверку уже существующего membership. Durable runner не делает COPY, включая
первую попытку. Отсутствующий участник требует внимания; upload/link сохраняются.

**Blocker полного AddToAlbum:** нужен серверный атомарный insert membership с
условиями на album_id и source fileId либо доказанный DAV эквивалент. Отдельный
lookup перед name-addressed COPY оставляет гонку с replacement и не заменяет
такой контракт. Обычная отдельная cloud-only операция не изменена; новых durable
гарантий для неё нет. Это ограничение относится и к принятию результата live flow.

## Ревизии и автоматические проверки

Base: `a974666bff95f138a8badbf50562e2e28af3b1b0` (PR157).
Production implementation: `7b56bfd72205755a805798b72aad2c652178067d`.
Уточнение только live fixture: `c1b92cad47e112d532dbe2df69b3de012adc7b22` (разные реальные JPEG и проверка выбранного
имени, чтобы существующая AUID approximation не подхватила предыдущую копию).
Identity policy, hash/reset и production код этим уточнением не менялись.
[Финальные format/boundaries](final-scope-checks.log) проверены на этой ревизии.

Последовательный Gradle запуск:

```bash
./gradlew spotlessApply spotlessCheck verifyFeatureBoundaries \
  :app:testAutomationUnitTest :core:media:test assembleAutomation \
  :app:compileAutomationAndroidTestKotlin :app:assembleAutomationAndroidTest \
  --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1g -XX:MaxMetaspaceSize=512m' \
  -Pkotlin.compiler.execution.strategy=in-process
```

[Build log](verified-build.log), [JVM totals из XML](jvm-results.json): 270 app и
7 core, failures/errors/skipped = 0. Formatter выводит предупреждение о трёх
последовательных проходах; `spotlessCheck` проходит. После изменения fixture
[повторная сборка test APK](fixture-final-build.log) также проходит.

На единственном `emulator-5554`, AVD `nextgallery-api36-dev`, API36, пакет
`.automation`:

```bash
adb -s emulator-5554 shell am instrument -w \
  -e class com.syrok0010.nextgallery.feature.upload.QueuedUploadsTest,com.syrok0010.nextgallery.feature.upload.UploadsRetryUiTest,com.syrok0010.nextgallery.feature.upload.DurableUploadJournalTest,com.syrok0010.nextgallery.feature.upload.UploadQueueRuntimeTest,com.syrok0010.nextgallery.feature.upload.UploadJournalMigrationTest \
  com.syrok0010.nextgallery.automation.test/androidx.test.runner.AndroidJUnitRunner
```

[39 instrumentation](verified-instrumentation.log): 5 queue, 2 UI, 8 journal,
23 runtime, 1 migration из реальной exported schema v1 в файловый Room.
Покрыты receipt-only retry без локального URI/PUT, отдельный результат link,
legacy/unknown intent, fencing/cancel/session guards и неизменяемый receipt.
DAV JVM проверки используют production interface и MockWebServer: точный
membership, replacement, конфликт ID/href, propstat/redirect/permission guards.

Существенные red→green: [recovery пропускал шаги](red-room.log),
[stop оставлял executor после финального commit](stop-red.log). Оба теста входят
в зелёные 39; это проверки наблюдаемого journal/runtime поведения.

## Live local → upload → explicit link → album

[Fixture](../../../app/src/androidTest/java/com/syrok0010/nextgallery/feature/upload/DurableAlbumLiveEvidence.kt)
работает через production LocalMediaSource, identity registry, queue, uploader,
link и album interfaces. Файл вставлен в MediaStore, индексирован обычным local
reader; fake MediaId не назначался. Это проверка интерфейсов, не UI выбор фото.

[Flow](live-flow.log): `durable158-flow.jpg`, cloud fileId 339,
MediaId `7526c614-1e17-48db-80ef-b848c794a7b0`. Один PUT, production explicit link,
затем **отдельное явное действие существующего writer** выполняет COPY.
Targeted retry durable шага принимает точный membership без PUT/COPY.
[Manifest](live-manifest.txt) связывает jobs, настоящие MediaId и fileId.
Это подтверждает обычные upload/link/album interfaces, но не обещает, что новый
runner сам выполняет добавление: до отдельного COPY он показывает attention.

## Реальный SIGKILL и reopen

На том же production коде, с уточнённым fixture:
`durable158-kill-final2.jpg`, job `21389a48-8aa8-429a-af87-36efcbd9bebf`,
MediaId `b2049a41-3b31-4c4d-978e-6e801d54979f`, fileId 343, albumId 42.

1. Production upload/link получили receipt; fixture создал persisted Running
   album claim. Отдельный production writer выполнил серверный COPY201.
2. [До kill](final-before.json): transfer Accepted, link Done, album Running,
   revision8. `adb shell run-as ... kill -9 25710` убил именно automation process.
   [Stage log](final-kill2-stage.log) содержит ожидаемый Process crashed и **не**
   считается прошедшим JUnit тестом.
3. Reopen через собственный agent-device запустил обычные App DI/foreground
   recovery. [После reopen](final-after.json): album Done с fileId/albumId/href;
   receipt byte-identical. [Verify](final-verify.log) проверил тот же MediaId
   в production identity DB.
4. [Серверный access log](final-server.log): один PUT, один COPY до kill;
   после reopen только чтение PROPFIND. [Экран результата](after-reopen.png)
   снят в предыдущем успешном эквивалентном прогоне stage2; не выдаётся за кадр
   именно final2.

Это recovery-window injection после отдельного writer COPY плюс настоящий
SIGKILL и настоящий App reopen. Не проверялись force-stop, reboot, системный
foreground timeout или серверное атомарное выполнение durable COPY.

## Attention, ручной retry и отмена на Uploads

[Начальный attention](attention.png): загрузка/link приняты, участник отсутствует,
ReplayUnsupported. Нажатие «Повторить действие» повторяет только проверку.
[Контрольные API операции](attention-api.json) затем заменили source fileId
344→345 по тому же пути: [UI](source-changed.png) показал изменение источника.
Удаление/создание собственного одноимённого альбома изменило albumId 43→44:
[UI после повторной проверки](target-changed.png) требует выбрать альбом заново.
Один промежуточный retry встретил `unexpected end of stream` и дал Contract
attention; следующее явное нажатие успешно проверило replacement.

[Access log](attention-server.log) различает исходный PUT okhttp, управляющий
PUT python-requests и read-only повторы NextGallery. COPY для attention нет.
[Явная отмена](cancelled.png) убрала retry/cancel, сохранила «Загружен» и сообщение,
что облачный файл остаётся. UI действия выполнены собственным agent-device;
скриншоты просмотрены через device_screenshot.

## Cleanup и границы

[Серверный cleanup](cleanup.json) удалил только точные task albums/files и их
записи корзины (проверены basename и original-location).
[Локальный cleanup](local-cleanup.json): удалены только найденные MediaStore IDs
398–402 из `Pictures/durable158/`; итоговый query пуст. Более ранний flow файл
к этому моменту уже отсутствовал. [Cleanup fixture](cleanup-instrumentation.log)
отменил свои jobs, снял owner после завершения и очистил credentials `.automation`.
Fixtures/чужая корзина и main/debug пакеты не менялись. Стенд healthy,
maintenance=false, restart/pause не выполнялись. Собственная device session и
панель закрыты с shutdown=false; эмулятор оставлен booted.

Доказательства PR156 не засчитываются здесь как собственные. CI и независимые
review этого PR на момент публикации не проверялись; merge не выполнялся.

Focused self-review: сверены переходы accept/begin/finish/retry/cancel/recover,
CAS fencing и production DI; проверено отсутствие COPY в durable runner,
изменений PUT conflict policy, scheduler и scope sibling PR153/154.
Независимые Standards/Spec review здесь не запускались.
