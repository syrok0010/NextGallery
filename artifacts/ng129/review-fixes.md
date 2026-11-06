# Исправления по Standards review #154

Проверена ревизия `32bdcbb0f00a447ec2aec6fcaa51b065937a053a`, следующая
после исходного evidence `b3e93b0`. После неё меняются только артефакты.

Исправлены три P2:

- Cache ingestion использует диагностируемый `bestEffort`: успешные серверные
  metadata принимаются при ошибке Room, `CancellationException` пробрасывается.
- Remote album reads проходят общий revision-aware ingestion. Свежий ответ
  принимает изменения другого клиента, начатый до подтверждения не откатывает
  отметку. В Room обновляются только существующие строки: чтение альбома
  не материализует его участников в warm-start timeline.
- Day invalidation сохраняет только metadata, подтверждённую после начала
  конкретного чтения. Новый индекс удаляет исчезнувший день из кэша;
  подтверждение favorite не защищает его всю сессию.

## Регрессии до исправления

На исходном production-коде запущены новые JVM-тесты:
`./gradlew :app:testAutomationUnitTest --tests '*CloudFavoritesTest' --tests '*MemoriesAlbumContentsTest'`.
Результат: **16 tests completed, 3 failed** — IOException при cache write,
старая отметка после свежего album read, откат ответа, начатого до confirmation.

Отдельно на `local / emulator-5554`, AVD `nextgallery-api36-dev` выполнен
`TimelineCacheRepositoryOfflineTest#freshIndexRemovesDeletedDayAfterFavoriteConfirmationAndDatabaseReopen`.
Результат: **FAILED**, удалённый объект оставался в восстановленном Room snapshot.

## После исправления

- Те же JVM-регрессии: успешно.
- Полный `:app:testAutomationUnitTest`: **291**, failures/errors **0**, skipped **1**
  (существующий opt-in live album test).
- `:core:media:test`: **7/7**, результат актуален, задача UP-TO-DATE.
- `spotlessCheck`, `:app:verifyFeatureBoundaries`, `:app:assembleDebug`,
  `:app:compileAutomationAndroidTestKotlin`: успешно.
- `:app:connectedAutomationAndroidTest` с фильтром класса
  `TimelineCacheRepositoryOfflineTest`: **6/6**. Проверены как защита от старого
  refresh, так и удаление последнего фото дня при свежем индексе после
  confirmation и повторного открытия Room; обновление favorite не материализует
  незакэшированного участника альбома.

[Лог полного прогона и сборки](review-fixes-gradle.log),
[лог шести Android-тестов](review-fixes-room.log).
Все Gradle-команды выполнялись последовательно с `--no-daemon --max-workers=1`,
`-Dorg.gradle.jvmargs='-Xmx1g -XX:MaxMetaspaceSize=512m'` и
`-Pkotlin.compiler.execution.strategy=in-process`.

Ручные UI/API-сценарии повторно не запускались: исходные screenshot/video
относятся к ревизии в основном README. Новые проверки используют localhost HTTP
fixture и тестовую Room DB, общий Nextcloud и его данные не менялись.
Собственная сессия закрыта: `Closed: t3-698085a5c17368d55b8fd529`;
эмулятор оставлен включённым. Новые скриншоты и offline UI не заявляются.
