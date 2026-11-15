# Тестирование

Для интеграционных проверок с живым Nextcloud/Memories используй
[тестовый сервер](test-nextcloud.md).

Устройство выбирай по [android-development.md](android-development.md).
JVM-тесты запускаются из корня checkout: `./gradlew :app:testAutomationUnitTest`.

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

Instrumentation запускай на хосте устройства из checkout проверяемой ревизии.
Для эмулятора через SSH Device Hub это `syrok-server`, а не локальный ADB
на `syrok-arch`. Укажи выбранный ADB serial и вариант `automation`:

```bash
ANDROID_SERIAL=<serial> ./gradlew :app:connectedAutomationAndroidTest
```

После прогона Gradle удаляет `.automation` с устройства вместе с сессией.
Сборку для ручной проверки выбирай по [инструкции получения APK](release.md#тестовые-apk).

> **Данные телефона:** нельзя трогать данные `com.syrok0010.nextgallery` на телефоне
> пользователя: очищать данные, удалять приложение или выполнять действия,
> сбрасывающие его состояние. Для тестов используй отдельный пакет
> `com.syrok0010.nextgallery.automation`. `connectedDebugAndroidTest` на телефоне
> запрещён: он может удалить или переустановить основное приложение с потерей данных.

Проверка и автоматическое исправление оформления Kotlin описаны в [kotlin-formatting.md](kotlin-formatting.md).
