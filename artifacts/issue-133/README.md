# Проверка #133 / PR #153

Реальный UI-прогон на `emulator-5554` (`nextgallery-api36-dev`, Android 16),
debug-приложение, сервер `http://test.syrok/`, тестовая учётная запись.
Все операции выполнялись на одноразовых `ng133-*`; fixtures не изменялись.
UI управлялся собственной agent-device-сессией, без UI-команд adb.

## Ревизии и сборки

| Этап | Ревизия кода | SHA256 установленного debug APK |
| --- | --- | --- |
| Исходные сценарии, материалы 01–13 | `a2c9d6e5288db6330376676f994342db06b36882` | `3cc8638b379ff39af8ff33ca31cd3c4d04ae680556ac2a10f3f71e276b42f896` |
| Исправление upload, материалы 14–22 | `ae6ba58ab87f409fc3e5774fe1ac8b4cb90fbebc` | `c5d1d2f5639afbe46ec98f6041bd719452809e872dc356a051ddae6aa407657a` |

В обоих случаях SHA256 собранного APK совпал с APK, извлечённым из установленного
пакета. Последующий коммит этих артефактов не меняет код приложения.
Base: `70d8c0f270ca6bd834787c68bed6ac4b628bf3cf` (#139).

## Фактически выполненные UI-сценарии

| Сценарий и результат | Скриншоты | Видео |
| --- | --- | --- |
| Longpress в своём серверном альбоме: главное «Убрать из альбома», меню с добавлением в другой альбом и отдельным «Удалить» | [Выбор](ui/01-selection.png), [меню](ui/02-secondary-actions.png) | [Исходный прогон](ui/01-album-actions.mp4) |
| «Удалить» открывает отдельное подтверждение облачной области и предупреждает об остальных альбомах; отменено | [Диалог](ui/03-delete-confirmation.png) | [Исходный прогон](ui/01-album-actions.mp4) |
| «Убрать» C меняет только членство, после обновления остаются B/A | [Подтверждение](ui/04-remove-confirmation.png), [результат](ui/06-membership-updated.png) | [Исходный прогон](ui/01-album-actions.mp4) |
| Viewer B → убрать → сосед A → убрать последнего → viewer закрыт, пустой альбом | [До](ui/07-viewer-before.png), [сосед](ui/08-viewer-neighbor.png), [закрыт](ui/09-viewer-last-closed.png) | [Viewer](ui/02-viewer-sequence.mp4) |
| Локальная папка: добавление в альбом и удаление, без удаления членства; sheet предупреждает о загрузке, подтверждение локального удаления отменено | [Действия](ui/10-local-no-membership-action.png), [sheet](ui/11-add-other-upload-disclosure.png), [диалог](ui/12-delete-local-confirmation.png) | — |
| После исправления: новый локальный E загружен из папки в альбом 1; в серверном альбоме объединённая копия предлагает три области удаления. Подтверждение отменено | [Папка](ui/14-fixed-local-actions.png), [области](ui/16-fixed-merged-delete-scopes.png) | [Upload после исправления](ui/04-fixed-folder-upload.mp4) |
| После исправления: E с облачной копией добавлен через UI во второй альбом; viewer E → убрать → D → убрать последнего → закрыт | [Sheet](ui/17-cloud-add-other.png), [сосед](ui/18-fixed-viewer-neighbor.png), [закрыт](ui/19-fixed-viewer-last-closed.png) | [Добавление и viewer](ui/05-fixed-cloud-and-viewer.mp4) |
| Отказ: только тестовый альбом 1 удалён через API после выбора A/B. Убрать получает 404 listing, показывает ошибку, сохраняет обе плитки. Отдельное удаление всё равно требует подтверждения; отменено | [До](ui/20-before-failed-removal.png), [после](ui/21-after-failed-removal.png), [подтверждение удаления](ui/22-failed-album-delete-confirmation.png) | [Отказ](ui/06-failed-album.mp4) |

[Timeline с оригиналами A/B/C](ui/15-timeline-originals.png) снят после их
исключения из альбома 1. Сохранность второго членства подтверждена API ниже.
Видео — реальные записи agent-device, перекодированные в 720 px по ширине,
15 fps, H.264 для размера; скриншоты сохранены в исходном разрешении.

## Найденный и исправленный дефект

Загрузка D из локальной папки на исходной ревизии создавала серверную копию,
но не связывала её с локальной identity:
[cloud-only состояние в серверном альбоме](ui/13-before-fix-cloud-only.png).
Папка читает байты через URI конкретного тома (`external_primary`), а индекс
регистрирует aggregate URI (`external`). Upload follow-up теперь использует
существующий aggregate ключ при связывании. URI чтения байтов, alias policy
и правила конфликтов identity не менялись; старые несвязанные копии не мигрируются.
Повторный прогон использовал новый E, а не уже загруженный D.

## API-доказательства

- [Подготовка A/B/C и двух альбомов](api/api-prepare.json).
- [После исключения A/B/C из альбома 1](api/api-after-selection.json): у каждого
  `HEAD=200`, `intimeline=true`, единственное оставшееся членство — альбом 2.
  [Альбом 1 пуст](api/album1-after-selection.json),
  [альбом 2 содержит три элемента](api/album2-after-selection.json).
- [Индексация D](api/api-uploaded.json), [индексация и preview E](api/api-uploaded-E.json)
  выполнены API только для этих файлов, поскольку Memories не сразу индексирует PUT.
- [После повторного viewer-прогона](api/api-after-fixed-viewer.json): оригиналы
  D/E существуют и входят в timeline, E сохранил членство во втором альбоме.
- [Подготовка отказа](api/api-failure-prepare.json),
  [удаление только своей коллекции](api/api-failure-trigger.json),
  [после отказа](api/api-after-failure.json): коллекция отвечает 404, все пять
  оригиналов отвечают 200; A/B/C/E всё ещё во втором альбоме.
- [Cleanup](api/api-cleanup.json): удалены только свои альбомы, исходная папка,
  D/E и точные записи их корзины. Локальные D/E удалены через MediaStore по
  точному `relative_path` и именам, пустая папка удалена. После обновления UI
  тестовые объекты отсутствовали. Эмулятор оставлен включённым.

## Автоматические проверки

На `a2c9d6e`: 270 app + 7 core JVM прошли, один live test пропущен в обычном
прогоне ([счётчики](checks/base-test-summary.json)). Отдельный
`LiveAlbumRemovalContractTest` прошёл через production-адаптер на test.syrok:
[санитизированный trace](checks/live-production-contract.txt).
Также ранее прошли format/boundaries/debug/automation build/androidTest compile.

Для исправления `ae6ba58` новая регрессия сначала воспроизвела дефект:
[красный прогон](checks/regression-red.log), 14 тестов, 1 failure.
После исправления успешно выполнено:

```text
./gradlew spotlessApply :app:testAutomationUnitTest :core:media:test \
  :app:verifyFeatureBoundaries :app:assembleDebug \
  :app:compileAutomationAndroidTestKotlin spotlessCheck \
  --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1g -XX:MaxMetaspaceSize=512m' \
  -Pkotlin.compiler.execution.strategy=in-process
```

[Полный лог](checks/verification-fix.log),
[счётчики](checks/fix-test-summary.json): 272 app + 7 core = **279 прошедших**,
0 failures/errors, один opt-in live test пропущен. Live membership contract
после upload-исправления не повторялся; членство дополнительно проверено UI/API.
`git diff --check` прошёл. Instrumentation с устройством не запускался;
`compileAutomationAndroidTestKotlin` подтверждает только компиляцию.

## Пределы evidence

- Смешанный частичный отказ отдельных member DELETE вручную не индуцировался.
  Его сохранение результатов проверено JVM; UI проверяет полный отказ listing 404.
- Чужой/shared read-only альбом не создавался и вручную не проверялся.
  Неподтверждённые shared mutation API в реализации не включены.
- Удаление оригиналов в UI в этом прогоне отменялось в подтверждении.
  Выполнение корзин и системных запросов удаления не выдаётся за ручную проверку.
- Video/removable-volume вариант исправления проверен JVM, на эмуляторе — фото
  `external_primary`. Process death, конфликты идентичности #115, #129 и
  WorkManager не входят в этот прогон.
- Серые preview у свежих серверных загрузок не доказывают отсутствие файла;
  для E preview был прогрет API. Имена текущего/соседнего объекта видны во viewer.

Все записи остановлены. Штатный handoff собственной сессии:
`Closed: t3-8fde1874ff4025bad6311311`. Чужие сессии не использовались и не закрывались.
