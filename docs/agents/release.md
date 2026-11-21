# Сборки и релизы

## Тестовые APK

Для ручных проверок используется `preview` (**NextGallery Test**, см.
[android-development.md](android-development.md)); его публичный тестовый ключ не
предназначен для релизов.

Для опубликованной ветки предпочитай GitHub Actions локальной сборке, чтобы
разгрузить `syrok-server`. Используй уже разрешённый commit/push; незакоммиченные
правки проверяй локальными целевыми тестами и компиляцией (или локальной сборкой
на более мощной рабочей машине при необходимости).

1. Запусти **Actions → Test APK → Run workflow**, выбрав ветку, либо используй CLI:

   ```bash
   gh workflow run test-apk.yml --ref <ветка>
   gh run list --workflow test-apk.yml --branch <ветка> --event workflow_dispatch \
     --json databaseId,headSha,status,conclusion,url
   ```

   Для ручного запуска файл workflow должен присутствовать в `main` и выбранной ветке.
2. Выбери успешный запуск с `headSha`, совпадающим с проверяемым commit.
   Скачай artifact в отдельный каталог и проверь контрольную сумму:

   ```bash
   gh run download <run-id> --pattern 'NextGallery-preview-*' --dir <каталог>
   cd <каталог>/NextGallery-preview-*
   sha256sum --check SHA256SUMS
   ```

   **Test APK** собирает только APK. Результаты JVM-проверок этой ревизии смотри
   в автоматическом CI; работу приложения проверяй на выбранном устройстве.

Если нужна локальная сборка: `./gradlew :app:assemblePreview`.

## Выпуск версии

1. **Actions → Release → Run workflow**, ветка **main**. Выбери `major`, `minor`
   или `patch` и отметку `prerelease`. Версия считается от последнего опубликованного
   GitHub Release, включая prerelease; например, `0.3.0` + minor → `0.4.0`.
   Незавершённый черновик блокирует следующий выпуск.
2. Workflow проверяет код, собирает и подписывает APK. Затем сам коммитит
   `version.properties` в `main` с увеличением versionCode на один и создаёт тег
   `vX.Y.Z`. Если за время сборки `main` изменился, запуск останавливается.
3. Открой черновик по ссылке из summary запуска. GitHub генерирует описание из
   изменений после предыдущего релиза; отредактируй его перед публикацией.
   Скачай APK, проверь вход, ленту, просмотр и загрузку. Автор проекта нажимает
   **Publish release**: публикуется тот же проверенный APK без пересборки.

При сбое автоматического восстановления нет. Если коммит версии и тег ещё не
отправлены, запусти новый Release. Если тег уже создан, заверши выпуск вручную
для этого тега, используя подписанный APK, SHA256SUMS и mapping.txt из artifacts исходного
запуска. Проверь commit тега; существующий тег и опубликованные файлы не меняй.
APK, контрольная сумма и R8 mapping хранятся в artifacts 7 дней.
В файлах GitHub Release сохраняются APK, SHA256SUMS и mapping.txt без автоматического
истечения срока хранения; mapping нужен для разбора стектрейсов именно этой сборки.

## Настройка подписи

Для environment **release** выбери **Selected branches and tags → main**
(именно ветку) и добавь secrets
`RELEASE_KEY_BASE64` — base64 существующего PKCS12 без переносов — и
`RELEASE_KEY_PASSWORD`. Режим **Protected branches only** не подходит:
при отсутствии branch protection он разрешает все ветки.

На `syrok-server` релизный ключ находится в
`/home/syrok/.config/nextgallery/release.p12`; Gradle properties
`nextgalleryReleaseStoreFile` и `nextgalleryReleasePassword` заданы в
`~/.gradle/gradle.properties`. Локальная сборка: `./gradlew :app:assembleRelease`.
Без этих properties APK будет unsigned; в CI подпись выполняется отдельно.

Ожидаемый сертификат закреплён в `RELEASE_CERT` в [release.yml](../../.github/workflows/release.yml).
Замена ключа лишает возможности обновлять существующую установку. Для проверки
локально подписанного APK:

```bash
$ANDROID_HOME/build-tools/<версия>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```
