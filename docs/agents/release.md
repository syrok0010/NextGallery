# Сборки и релизы

## Тестовые APK

APK собирается только по запросу: **Actions → Test APK → Run workflow → ветка**.
Автоматический CI на PR и push в `main` запускает проверки и компиляцию тестов;
упаковка APK и R8 выполняются в ручном workflow **Test APK**. Для опубликованной
ветки без PR этот workflow тоже доступен.

Ручной запуск станет доступен после первого слияния workflow в `main`.
Выбранная ветка также должна содержать `.github/workflows/test-apk.yml`.

Через CLI из checkout репозитория:

```bash
gh workflow run test-apk.yml --ref <ветка>
gh run list --workflow test-apk.yml --branch <ветка> --event workflow_dispatch --limit 5 \
  --json databaseId,headSha,status,conclusion,url
```

Выберите запуск с нужным `headSha` и дождитесь успешного завершения. Скачайте его
artifact в отдельный каталог и проверьте контрольную сумму:

```bash
gh run download <run-id> --pattern 'NextGallery-preview-*' --dir <каталог>
cd <каталог>/NextGallery-preview-*
sha256sum --check SHA256SUMS
```

Ссылка на artifact есть в summary успешного запуска. Внутри — APK и `SHA256SUMS`;
срок хранения 7 дней. Собирается commit выбранной ветки на момент запуска.
В `versionName` и имени APK записаны номер запуска и SHA. Ручная сборка APK
не запускает JVM-тесты повторно: результаты проверок этой ревизии смотри в CI.

`preview` — отдельное приложение **NextGallery Test** с пакетом
`com.syrok0010.nextgallery.preview`. Оно использует R8 и resource shrinking, как
release, и допускает HTTP только для `test.syrok`. `automation` остаётся отдельным
приложением instrumentation-тестов. Основная установка и её данные не затрагиваются.

Все preview подписываются одним **публичным тестовым** ключом
`tools/ci/preview.p12`, alias `preview`, пароль `android`. Это намеренно не секрет
и не подтверждение доверия к автору сборки. Для release этот ключ не используется.
Постоянная подпись и фиксированный `versionCode = 1` позволяют ставить preview
разных веток друг поверх друга; версия и ревизия видны в системных сведениях о приложении.

Локальная сборка: `./gradlew :app:assemblePreview -PnextgalleryPreviewLabel=local`.
Debug и release сохраняют основной пакет; debug с другой подписью нельзя ставить
поверх release. Для ручных проверок веток используйте preview.

## Версии

Единственный источник версии — корневой `version.properties`:

```properties
versionName=0.4.0
versionCode=3
```

`versionName` имеет вид `X.Y.Z`, тег — `vX.Y.Z`. `versionCode` повышается на единицу
при подготовке следующего релиза, хранится в Git и не зависит от номера запуска CI.
Повторные сборки не меняют версию. Публикация проверяет, что code больше code
предыдущих тегов. Исторические теги до `version.properties` читаются из Gradle.

`0.4.0 / 3` уже подготовлены: для первого выпуска используйте сразу `publish`.
Следующий `prepare` с `patch` даст `0.4.1 / 4`, с `minor` — `0.5.0 / 4`.

## Подготовка следующего релиза

1. **Actions → Release → Run workflow**, ветка **main**, operation **prepare**.
2. В `version` укажите `patch`, `minor` или точную следующую версию, например `1.0.0`.
3. Workflow создаст ветку `release/X.Y.Z` и PR с изменением только версии.
   Описание содержит изменения с предыдущего тега. Ссылка появится в summary.
4. Проверьте CI и слейте PR. Для PR, созданного `GITHUB_TOKEN`, GitHub может
   потребовать **Approve workflows to run**. Дополнительный PAT не нужен.

Существующие release-ветки не перезаписываются. Если после push создание PR упало,
откройте PR из уже созданной ветки; бамп повторять не нужно.

## Сборка и публикация

1. **Actions → Release → Run workflow**, ветка **main**, operation **publish**.
   Поле `version` здесь не используется: берётся версия из Git.
   `prerelease` определяет соответствующую отметку будущего выпуска.
2. Workflow фиксирует SHA на момент запуска, проверяет версии, теги,
   форматирование, границы пакетов, release tooling и JVM-тесты, собирает
   unsigned release с R8. Этот job не получает ключ подписи.
3. Отдельный job в environment `release` подписывает APK, проверяет сертификат,
   package, versionName, versionCode и отсутствие debuggable. Затем создаёт тег
   на исходном SHA и **черновик** GitHub Release с APK, SHA256SUMS и release notes.
   Тег и черновик создаются в том же запуске: от push тега через `GITHUB_TOKEN`
   следующий workflow автоматически не запустится.
4. Скачайте APK из черновика и проверьте вход, ленту, просмотр и загрузку.
   После smoke нажмите **Publish release**. Публикуется тот же APK без пересборки.

R8 mapping и unsigned APK сохраняются в artifacts исходного запуска на 7 дней.
При необходимости долгого разбора ошибок скачайте mapping до истечения срока.

Повторный `publish` продолжает незавершённый черновик. Если APK уже загружен,
используется именно он, с повторной проверкой подписи и метаданных; отсутствующая
SHA256SUMS добавляется. Существующие файлы не заменяются, несовпадение суммы — ошибка.
Тег другого commit и опубликованный релиз — ошибка. Если основной код изменился
после создания тега, используйте **Re-run jobs** первоначального запуска, который
сохраняет исходный SHA; новый запуск с другим SHA не перенесёт тег.

## Настройка GitHub один раз

- **Settings → Actions → General → Workflow permissions**: разрешить GitHub
  Actions создавать pull requests. Обычные jobs сохраняют read-only permissions;
  права записи запрашивают только prepare и publish.
- Создать environment **release**. В **Deployment branches and tags** выбрать
  **Selected branches and tags** и добавить именно ветку **main**. Не выбирать
  «Protected branches only»: при отсутствии branch protection это разрешит все ветки.
- В environment добавить secrets `RELEASE_KEY_BASE64` (base64 существующего
  PKCS12 без переносов строк) и `RELEASE_KEY_PASSWORD` (его пароль).
  Для PR и веток эти secrets не используются. Workflow сам никогда не публикует
  черновик, поэтому отдельное ручное одобрение job не требуется.

Standard GitHub-hosted Ubuntu runners бесплатны для публичного репозитория.
Artifacts имеют отдельные ограничения хранения, поэтому APK и отчёты хранятся
7 дней. Окончательные APK размещаются в GitHub Releases.

## Локальная релизная подпись

Release подписывается ключом `nextgallery` из PKCS12 вне репозитория.
Локально на `syrok-server` путь и пароль находятся в `~/.gradle/gradle.properties`:

```properties
nextgalleryReleaseStoreFile=/home/syrok/.config/nextgallery/release.p12
nextgalleryReleasePassword=...
```

Поддерживаются и переменные `ORG_GRADLE_PROJECT_nextgalleryReleaseStoreFile`,
`ORG_GRADLE_PROJECT_nextgalleryReleasePassword`. Без ключа `assembleRelease`
собирает unsigned APK. CI подписывает его отдельно через `apksigner`.
Хранилище и пароль не коммить и не публикуй.

```bash
./gradlew :app:assembleRelease
$ANDROID_HOME/build-tools/<версия>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Ожидаемый SHA-256 сертификата:
`B5:AD:59:36:30:B4:22:ED:70:24:93:53:FA:05:C1:96:F5:C1:05:D3:13:75:A3:B7:A6:14:2F:A9:69:D5:FE:F5`.
Проверка в `tools/ci/release.py` закрепляет этот сертификат. Замена ключа требует
явного изменения проверки и лишает возможности обновлять существующую установку.
