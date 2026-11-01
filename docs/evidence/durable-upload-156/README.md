# Проверка durable manual upload, #156

Исходный код и проверенная automation APK: **9bda61b86a87494c1a87e836b4cec3589e3873bd**.
Основание: PR #152, `feat/upload-management`, **185e15d0ed3557b8edbeafa50a3bb74db0d23830**.
Проверено 01.11.2026 на единственном `emulator-5554`, AVD `nextgallery-api36-dev`, Android 16 / API 36.
Стенд `http://test.syrok/`, uid `test`; ни рестартов, ни пауз стенда не было.
Этот каталог добавлен отдельным коммитом; runtime после проверенной ревизии не менялся.

## Автоматические проверки

[Итоговый Gradle log](checks.log):

- `spotlessApply spotlessCheck`, `:app:verifyFeatureBoundaries`;
- `:app:testAutomationUnitTest`: **255 JVM tests**, 0 failures;
- `:core:media:test`: **7 tests**, 0 failures;
- `:app:assembleAutomation`;
- `:app:connectedAutomationAndroidTest`: **26 tests**, 0 failures.

Instrumentation-фильтр: `DurableUploadJournalTest`, `UploadQueueRuntimeTest`,
`QueuedUploadsTest`, `AppCompositionTest`. Использованы настоящие файловые Room DB
с закрытием и повторным открытием; enqueue/commands/runner используют production
интерфейс. DAO mutations не подменялись тестовым хранилищем. HTTP/source handlers
в runtime protocol tests управляемые: это не end-to-end доказательство серверного
membership, permission lifecycle или поведения всех версий Android.

Во всех Gradle-запусках: `--no-daemon --max-workers=1`,
`-Dorg.gradle.jvmargs='-Xmx1g -XX:MaxMetaspaceSize=512m'`,
`-Pkotlin.compiler.execution.strategy=in-process`. Проверки последовательные.

[Red excerpt](red-excerpt.log): реальная ошибка повторного accept одного claim;
receipt был принят второй раз вместо отказа CAS. Исправлено ограничением
перехода Running без receipt. [Вторая red-проверка](rejected-enqueue-red-excerpt.log):
закрытие Room возвращало внутренний JobCancellationException вместо Rejected.
Исправлено различением отмены вызывающей coroutine и недоступности хранилища.
Зелёный итог включает cancel-before-accept, accepted receipt, uncertain destination,
новый owner, неизвестную версию intent, недоступный/изменённый источник,
отказ enqueue, logout и competing claims без двойного transfer.

## Реальный process death и reopen

Opt-in `UploadProcessDeathEvidence.stageRealDavCrashWindows` не запускается
обычным test suite. Fixture использует четыре своих MediaStore оригинала,
production queue/journal/source reader и настоящий conditional DAV upload.
Через journal protocol подготовлены точки сбоя; это управляемая расстановка
crash windows, а не случайное попадание в середину передачи большого видео.

После READY (16:20:48 по часам эмулятора) процесс automation **PID 9572** был убит
**SIGKILL (`kill -9` через run-as)**. UI затем открыт через собственную
`agent-device` сессию. Это не force-stop, не swipe Recents и не Stop в системном
Task Manager. Фонового wake до открытия не проверяли и не обещаем.
Instrumentation runner после kill ожидаемо завершился из-за смерти процесса.

[Состояние до reopen](before.json) снято из копии реального journal + WAL после
SIGKILL. [Состояние после reopen](after.json) снято после drain сервиса.
Credentials, owner tokens и полная БД не публикуются.

| Файл | До смерти процесса | После открытия | Серверное подтверждение |
| --- | --- | --- | --- |
| `durable156-cancelled.jpg` | Cancelled, receipt нет | Cancelled | Ни одного PUT |
| `durable156-receipt.jpg` | Accepted, receipt, link Pending | Accepted, прежний receipt | Только исходный PUT 201 |
| `durable156-uncertain.jpg` | Running, сервер ответил 201, receipt нет | Accepted existing-by-size | Тот же путь: PUT 412, HEAD 200, PROPFIND 207 |
| `durable156-pending.jpg` | Pending | Accepted created | PUT 201 после открытия |

[Серверный access log](dav.log) отфильтрован строго по `durable156-*`;
первые HEAD 404 — проверка отсутствия собственных имён перед fixture.
Другие запросы стенда в доказательства не включены. 412 остаётся эвристикой
имени/размера #131 и не доказывает равенства bytes.

Fixture подал queue snapshot без записей identity registry. Поэтому в этом
device-сценарии link завершился **Attention**, а UI сохранил «Загружен, действие
требует внимания»; успешный автоматический link на скриншоте не заявляется.
Повторяемый link и owner guard проверены JVM-тестами registry, а replay receipt
без нового PUT — также настоящим Room runtime test с управляемым link handler.
Album COPY/replay на живом стенде не выполнялся: безопасный membership replay
вынесен в следующий срез. Pending/running album-step после рестарта требует внимания.

## UI и logout

Скриншоты 1080×2340 сняты через собственную device-сессию без редактирования.

- [Восстановленный экран](reopened-uploads.png): вход из плашки, четыре прежних файла,
  transfer и follow-up outcomes раздельны, доступна отмена unresolved действий.
- [Выход запрещён](logout-blocked.png): три Attention link-шагa удерживают сессию;
  диалог объясняет, где завершить или отменить задания.
- [Явная отмена действий](actions-cancelled.png): «Отменить все» подавляет pending
  действия и сохраняет принятые облачные результаты.
- [Выход после отмены](logout-after-cancel.png): без очистки завершённой истории
  logout открывает Login Flow. Полная очистка приложения #53 не реализована.

Свой device lease закрыт, эмулятор оставлен работать. Три своих серверных файла
и их записи корзины удалены; четыре своих MediaStore оригинала удалены по точным URI.
Постоянные fixtures и данные других задач не удалялись.

## Границы

Не проверены: reboot, настоящий force-stop/Task Manager Stop, API 31–35,
dataSync quota/timeout на ОС, ENOSPC, backup/restore, миграция будущей schema
и album membership. Отказ storage проверен закрытием настоящей Room DB,
а не заполнением диска. Без spool metadata не доказывает неизменности bytes
и не устраняет гонку изменения во время чтения. JVM/protocol tests не выдаются
за UI evidence этих сценариев.

## Исправления Standards review

На runtime `80ef6c115b339a4799ff0b7a36a6f8ff22c20814` оба замечания исправлены и проверены отдельно: [red/green и ограничения](standards-fixes.md). Итог — 262 JVM и 29 instrumentation; первоначальный ручной SIGKILL-прогон выше не выдаётся за повторный прогон нового SHA.
