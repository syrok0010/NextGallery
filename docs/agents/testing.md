# Тесты

## Сборка и JVM-тесты

Запускайте подходящие задачи из корня проверяемого checkout:

```bash
./gradlew --no-daemon --max-workers=2 --console=plain \
  :app:testAutomationUnitTest :app:assembleAutomation \
  :app:assembleAutomationAndroidTest :app:lintAutomation
```

Для короткого цикла выбирайте только задачи, относящиеся к изменению.

## Instrumentation

Выбирайте устройство по [правилам Android-разработки](android-development.md).

На хосте устройства проверьте `adb devices -l`; для эмулятора дополнительно
сверьте имя через `adb -s <serial> emu avd name`. Serial зависит от запуска,
поэтому не предполагайте, что это всегда `emulator-5554`. Запускайте тесты
из checkout проверяемой ревизии с явно выбранным serial:

```bash
ANDROID_SERIAL=<serial> ./gradlew --no-daemon --max-workers=2 --console=plain \
  :app:connectedAutomationAndroidTest
```

Замените `<serial>` фактическим значением. При SSH device host на `syrok-server`
эту команду выполняйте на `syrok-server`, а не против локального ADB на
`syrok-arch`. Отчёты находятся в `app/build/reports/androidTests/` на хосте запуска;
перенесите нужные артефакты в рабочий checkout агента.

## Данные физического телефона

Debug-приложение `com.syrok0010.nextgallery` сохраняет пользовательскую
авторизацию, Room database и cache. Instrumentation выполняется только для
`automation`: пакет `com.syrok0010.nextgallery.automation` имеет отдельные данные.

Не запускайте `connectedDebugAndroidTest` на общем физическом устройстве:
переустановка или удаление target APK может уничтожить данные debug-приложения.
