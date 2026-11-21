# Тестирование

Устройство, хост `syrok-server`, роли пакетов (`preview` / `debug` / `automation`)
и защита данных реального телефона описаны в [android-development.md](android-development.md);
тестовый Nextcloud/Memories — в [test-nextcloud.md](test-nextcloud.md).

## Локальные проверки (как в CI)

Запускаются из корня checkout на любой машине и повторяют
`.github/workflows/android-build.yml` и `.github/workflows/kotlin-format.yml`:

```bash
./gradlew verifyFeatureBoundaries :core:media:test :app:testAutomationUnitTest :app:compileAutomationAndroidTestKotlin
./gradlew spotlessCheck
```

При изменениях в `tools/ci/` дополнительно запускай:

```bash
python3 -m unittest discover -s tools/ci -p 'test_*.py' -v
```

Форматирование и `spotlessApply` — в [kotlin-formatting.md](kotlin-formatting.md).

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

Запускаются на `syrok-server` из checkout проверяемой ревизии (при работе с другой
машины — после разрешённого push ветки или делегированием на `syrok-server`, см.
[android-development.md](android-development.md)):

```bash
ANDROID_SERIAL=<serial> ./gradlew :app:connectedAutomationAndroidTest
```

Для ручных проверок выбирай `preview` ([release.md](release.md#тестовые-apk)) или
`debug` на эмуляторе по правилам [android-development.md](android-development.md).
