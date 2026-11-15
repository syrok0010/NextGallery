# Сборки и релизы

## Тестовые APK

Для ручных проверок используй `preview` (**NextGallery Test**): отдельный пакет
сохраняет основную установку и не удаляется instrumentation-тестами. Постоянный
публичный тестовый ключ и фиксированный versionCode позволяют обновлять его
сборками разных веток. Этот ключ не предназначен для релизов.

Для опубликованной ветки предпочитай GitHub Actions локальной сборке, чтобы
разгрузить наш хост. Используй уже разрешённый commit/push; незакоммиченные
правки проверяй локальными целевыми тестами и компиляцией.

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

Версия хранится в корневом `version.properties`. Тег имеет вид `vX.Y.Z`;
versionCode повышается только при подготовке следующего релиза.

1. **Actions → Release → Run workflow**, ветка **main**, operation **prepare**.
   В `version` укажи `patch`, `minor` или точную следующую версию. Workflow откроет
   PR с бампом; проверь CI и передай PR автору проекта на слияние. GitHub может
   запросить **Approve workflows to run** для PR, созданного ботом.
2. После слияния запусти **Release** из `main` с operation **publish** и выбери
   отметку `prerelease`. Версия берётся из Git, поле `version` здесь не используется.
   Если нужная версия уже подготовлена, начинай с этого шага.
3. Из полученного черновика GitHub Release скачай APK и проверь вход, ленту,
   просмотр и загрузку. Автор проекта нажимает **Publish release**: публикуется
   тот же проверенный APK без пересборки.

Если подготовка остановилась после push release-ветки, открой PR из неё: повторный
prepare существующую ветку не перезаписывает. При незавершённом выпуске используй
**Re-run jobs** исходного запуска, чтобы сохранить SHA. Уже загруженный APK
сохраняется; опубликованный релиз и тег другого commit изменить нельзя.

Для разбора ошибок R8 сохрани mapping из artifacts исходного запуска до истечения
их срока хранения.

## Настройка подписи

В GitHub разреши Actions создавать pull requests. Для environment **release**
выбери **Selected branches and tags → main** (именно ветку) и добавь secrets
`RELEASE_KEY_BASE64` — base64 существующего PKCS12 без переносов — и
`RELEASE_KEY_PASSWORD`. Режим **Protected branches only** не подходит:
при отсутствии branch protection он разрешает все ветки.

На `syrok-server` релизный ключ находится в
`/home/syrok/.config/nextgallery/release.p12`; Gradle properties
`nextgalleryReleaseStoreFile` и `nextgalleryReleasePassword` заданы в
`~/.gradle/gradle.properties`. Локальная сборка: `./gradlew :app:assembleRelease`.
Без этих properties APK будет unsigned; в CI подпись выполняется отдельно.

Ожидаемый сертификат закреплён в `RELEASE_CERT` в [release.py](../../tools/ci/release.py).
Замена ключа лишает возможности обновлять существующую установку. Для проверки
локально подписанного APK:

```bash
$ANDROID_HOME/build-tools/<версия>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```
