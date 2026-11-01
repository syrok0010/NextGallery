# #129 — evidence облачного избранного

Проверено 2026-11-01 на `local / emulator-5554`, AVD `nextgallery-api36-dev`,
Android 16 / API 36. Стенд — `http://test.syrok/`, аккаунт `test`, Nextcloud
34.0.5 / Memories 10.1.0. Только одноразовые медиа задачи `ng129-*` изменялись.

## Ревизии

- Base: PR [#153](https://github.com/syrok0010/NextGallery/pull/153),
  `feat/album-media-actions-133`, `5288c42df1523aab92b959a239a2371cb0928e14`.
  Live head повторно сверен перед публикацией. Нижний стек не изменялся.
- Код и APK всех перечисленных ниже UI-сценариев:
  `480e2fd5e5305e6f0555390361a8c173dbf397c4`.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, SHA-256
  `13dc968e3dd580e7df1caf825d082489e9c21805a2dac7e0a6028ebef0f1ee87`.
- Следующий коммит добавляет только эти артефакты и пояснения; APK не пересобирается
  ради добавления evidence. Сам бинарный APK в Git не включён.

## Реальные UI и серверные проверки

UI управлялся собственной сессией `agent-device`; скриншоты и видео сохранены ею.
ADB использовался для установки, test runner, логов, metadata и файлов, без UI input.
После UI-изменений значение независимо читалось DAV PROPFIND: результаты находятся
в [api-evidence.json](api-evidence.json), без авторизации и паролей.

| Сценарий | Evidence и результат |
| --- | --- |
| Mark → unmark → mark текущего облачного фото | [Видео](favorite-flow.mp4), [сердечко](01-marked-timeline-viewer.png); `fileId=320`, отдельные server truth `1 → 0 → 1` |
| Фильтр находит старые, ранее не загруженные объекты | [Фильтр](02-filter-old-unseen.png): фото 2010 года (`321`) и 2001 года (`334`). Последнее создано после запуска приложения; до включения фильтра его не было в app metadata DB |
| Снятие отметки в активном фильтре | [Переход к соседу](03-filter-next-neighbor.png), затем [пустой фильтр и закрытый viewer](04-filter-empty-viewer-closed.png); server truth `320/321/334 = 0` |
| Объединённый local + cloud объект | [Сердечко](05-merged-marked.png), [одна плитка с двумя badges](06-merged-copy-badges.png), одинаковый AUID локальной и облачной копии в JSON; серверный `322 = 1` |
| Local-only | [Неактивное сердечко и пояснение](07-local-only-disabled.png); reusable `phone-6.jpg` только просматривался |
| Viewer открытого альбома | [Общая отметка](08-album-viewer-heart.png); unmark/mark оттуда подтверждены сервером `322 = 0 → 1` |
| Viewer папки | [Та же отметка](09-folder-viewer-heart.png) |
| Ошибка записи | Свой `ng129-error.jpg` (`323`) удалён через API перед tap. [Ошибка](10-error-old-value-retained.png), [прежнее пустое сердце](11-error-unmarked-after-dismiss.png); lookup возвращает `404`, очередь повторов не создаётся |
| Повторный запуск процесса | [Сердечко сохранено](12-heart-after-process-relaunch.png) на той же APK, server truth `322 = 1`; дополнительно прочитано сохранённое `isFavorite=1` в metadata |

В [api-evidence.json](api-evidence.json) также сохранён фактический успешный DAV
`207` с self-closing `<oc:favorite/>` в `200 propstat` и проверка native
`days?fav=1` / `days/{ids}?fav=1`. Полнота фильтра относится к доступному
индексированному Memories timeline; это не обещание охватить все файлы Nextcloud,
архив и скрытые объекты. Имена месяцев на скриншоте не доказывают год — годы и
`fileId` зафиксированы в данных API, выбранных строках metadata и описании сценария.

## Автоматические проверки

[Финальный Gradle log](gradle-final.log), [сводка test runner](test-results-summary.json).
`BUILD SUCCESSFUL`: `spotlessApply`, `spotlessCheck`, `:app:verifyFeatureBoundaries`,
`:app:testAutomationUnitTest` (287, ошибок 0, один существующий live album test
пропущен), `:core:media:test` (7/7), `:app:assembleDebug`,
`:app:compileAutomationAndroidTestKotlin`, `:app:installDebug`.

Выбранный `:app:connectedAutomationAndroidTest` — 3/3:

- DAV XML с пустым свойством в успешном `propstat` на Android parser;
- отложенное чтение HTTP response body при вызове writer с Main dispatcher;
- подтверждённая metadata переживает stale refresh, invalidation дня и повторное
  открытие Room database.

JVM-сценарии проверяют отказанные/mixed `propstat`, owner/fileId/path/permission,
redirect и XML entities, pending/busy/error, старую сессию (включая повторный вход
в тот же аккаунт), stale reads после подтверждения, merged/local-only, все 23 дня
порциями `10+10+2`, отказ принять неполный ответ и viewer neighbor/empty sequence.

Реальная UI-проверка обнаружила `NetworkOnMainThreadException`: получение headers
через `await()` не гарантирует, что body уже прочитан. До исправления воспроизведено
отдельным красным Android-тестом: [исходный regression excerpt](regression-before-io-fix.log).
В проверенной ревизии writer полностью выполняется на IO; этот тест зелёный.

Gradle запускался последовательно, один процесс, с `--no-daemon --max-workers=1`,
`-Dorg.gradle.jvmargs='-Xmx1g -XX:MaxMetaspaceSize=512m'` и
`-Pkotlin.compiler.execution.strategy=in-process`. Instrumentation ограничен
названными тремя тестами; весь Android-набор не запускался.

## Границы и cleanup

Pending сохраняет прежнее сердце до серверного подтверждения; при ошибке оно
остаётся прежним. UI warm start проверен с доступной сетью. Offline UI и
восстановление незавершённой записи после process death не заявляются; durable
metadata проверена Room reopen test. Локальное избранное, offline queue и batch
favorite actions отложены согласно утверждённому scope.

[Cleanup evidence](cleanup-evidence.json): удалены только свой альбом
`test/ng129-favorites`, `/Photos/ng129-favorites/`, точные собственные записи
корзины `ng129-favorites.d1793527534/` и `ng129-error.jpg.d1793527326`, локальные
MediaStore `387/389` и пустая `/sdcard/Pictures/ng129-favorites/`, а также временный
`/tmp/ng129-exif.jpg` в контейнере стенда. После удаления серверные пути возвращают
`404`, своих записей корзины и локальных строк больше нет. Фикстуры, чужие файлы
и корзина, настройки стенда не изменялись; стенд не останавливался.

Собственная сессия штатно закрыта, эмулятор оставлен включённым:
`Closed: t3-f89cd0f0e3f8fcf316024b58`. Другие сессии не закрывались.

## Исправления после независимого review

Три P2 исправлены после исходного UI-прогона. Ревизия, red/green регрессии и
пределы повторной проверки — в [дополнении](review-fixes.md).
