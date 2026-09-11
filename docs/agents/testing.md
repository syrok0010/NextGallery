# Тестирование

Устройство выбирай по [android-development.md](android-development.md).
JVM-тесты запускаются из корня checkout: `./gradlew :app:testAutomationUnitTest`.

Instrumentation запускай на хосте устройства из checkout проверяемой ревизии.
Для эмулятора через SSH Device Hub это `syrok-server`, а не локальный ADB
на `syrok-arch`. Укажи выбранный ADB serial и вариант `automation`:

```bash
ANDROID_SERIAL=<serial> ./gradlew :app:connectedAutomationAndroidTest
```

> **Данные телефона:** нельзя трогать данные `com.syrok0010.nextgallery` на телефоне
> пользователя: очищать данные, удалять приложение или выполнять действия,
> сбрасывающие его состояние. Для тестов используй отдельный пакет
> `com.syrok0010.nextgallery.automation`. `connectedDebugAndroidTest` на телефоне
> запрещён: он может удалить или переустановить основное приложение с потерей данных.
