# Тестирование

Для интеграционных проверок с живым Nextcloud/Memories используй
[тестовый сервер](test-nextcloud.md). Устройство выбирай по
[android-development.md](android-development.md).

## Локальные проверки (как в CI)

JVM-проверки и компиляция instrumentation-тестов запускаются из корня checkout на
любой машине и повторяют `.github/workflows/android-build.yml` и
`.github/workflows/kotlin-format.yml`:

```bash
./gradlew verifyFeatureBoundaries :core:media:test :app:testAutomationUnitTest :app:compileAutomationAndroidTestKotlin
./gradlew spotlessCheck
```

При изменениях в `tools/ci/` дополнительно запускай:

```bash
python3 -m unittest discover -s tools/ci -p 'test_*.py' -v
```

Подробности форматирования и автоисправления (`spotlessApply`) — в
[kotlin-formatting.md](kotlin-formatting.md).

## Правила для Flow-тестов

Для Flow используй Turbine (`test`, `awaitItem`, `awaitComplete`, `awaitError`),
когда он убирает ручное накопление emissions, обслуживание collector или try/catch
вокруг collect. Проверяй значимые результаты, порядок и терминальное событие;
не скрывай неожиданные emissions через `cancelAndIgnoreRemainingEvents`.
Для бесконечного потока явно обозначь границу проверки: `test` отменит подписку
при выходе из блока. Простые `first()`, `toList()` и проверки конечного
`state.value` оставляй, если они яснее. StateFlow может объединять мгновенные
обновления: не требуй всех промежуточных состояний. Debounce и другие временные
контракты проверяй через scheduler `kotlinx-coroutines-test`; нужный `runCurrent()`
оставляй, реальные задержки не добавляй.

## Instrumentation-тесты

Instrumentation запускай **на `syrok-server`** из checkout проверяемой ревизии —
локальный `adb` на `syrok-arch` или другой машине эмулятор на `syrok-server` не
видит. При работе с другой машины переноси проверку на `syrok-server` (после
разрешённого push ветки или делегированием в linked environment
`syrok0010.keenetic.pro` / по SSH). Укажи актуальный ADB serial из `adb devices`
и вариант `automation`:

```bash
ANDROID_SERIAL=<serial> ./gradlew :app:connectedAutomationAndroidTest
```

После прогона Gradle удаляет `.automation` с устройства вместе с сессией, не
затрагивая `preview` и `debug`.

## Ручные проверки

- Для обычных ручных проверок на `nextgallery-api36-dev` используй `preview`
  (**NextGallery Test**, см. [инструкцию получения APK](release.md#тестовые-apk)):
  он сохраняет постоянную сессию на `http://test.syrok/`.
- Для сценариев с нуля (Login Flow, проверка версии Memories, logout) используй
  `debug` на этом же эмуляторе.

> **Данные телефона:** нельзя трогать данные `com.syrok0010.nextgallery` на телефоне
> пользователя: очищать данные, удалять приложение или выполнять действия,
> сбрасывающие его состояние. Для тестов на телефоне используй отдельный пакет
> `com.syrok0010.nextgallery.automation`. `connectedDebugAndroidTest` на телефоне
> запрещён: он может удалить или переустановить основное приложение с потерей данных.
