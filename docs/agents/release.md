# Релизная сборка

Release подписывается ключом `nextgallery` из PKCS12-хранилища вне репозитория.
Путь и пароль берутся из Gradle properties, на `syrok-server` — из
`~/.gradle/gradle.properties`:

```properties
nextgalleryReleaseStoreFile=/home/syrok/.config/nextgallery/release.p12
nextgalleryReleasePassword=...
```

В CI те же значения передаются как `ORG_GRADLE_PROJECT_nextgalleryReleaseStoreFile`
и `ORG_GRADLE_PROJECT_nextgalleryReleasePassword`. Без них release собирается
неподписанным. Хранилище и пароль не коммить и не публикуй. Потеря ключа
означает, что следующие версии не встанут обновлением поверх установленной.

Сборка и проверка подписи:

```bash
./gradlew :app:assembleRelease
$ANDROID_HOME/build-tools/<версия>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

SHA-256 сертификата: `B5:AD:59:36:30:B4:22:ED:70:24:93:53:FA:05:C1:96:F5:C1:05:D3:13:75:A3:B7:A6:14:2F:A9:69:D5:FE:F5`.

Debug и release используют один `applicationId`: release не ставится поверх debug
с другой подписью, сначала debug удаляется вместе с данными. `versionName` следует
тегам GitHub-релизов (`v0.3.0` → `0.4.0`), `versionCode` растёт с каждым APK,
который ставится обновлением.
