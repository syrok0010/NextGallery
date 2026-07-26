# Тесты

## Инструментальные тесты на общем физическом устройстве

Установленный debug-вариант `com.syrok0010.nextgallery` считается «горячим» приложением:
в нем сохраняются авторизация, Room database и cache для ручной проверки.

Инструментальные тесты запускаются только для build type `automation`:

```bash
./gradlew connectedAutomationAndroidTest
```

Automation-вариант имеет application ID `com.syrok0010.nextgallery.automation` и отдельное
имя `NextGallery Automation`. Android хранит его данные отдельно от debug-варианта, поэтому
установка и удаление target APK не сбрасывают состояние «горячего» приложения.

Не запускать `connectedDebugAndroidTest` на общем физическом устройстве: эта задача использует
target package «горячего» debug-приложения и может переустановить или удалить его вместе с данными.

Локальные JVM-тесты не обращаются к устройству и запускаются как обычно:

```bash
./gradlew testDebugUnitTest
```
