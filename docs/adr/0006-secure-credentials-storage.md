# ADR 0006: Secure storage для Nextcloud app password

## Статус

Принято.

## Контекст

Nextcloud Login Flow v2 возвращает `appPassword`. Это постоянный секрет, который приложение использует для Basic Auth к Nextcloud/Memories API. В первом tracer bullet credentials временно сохранялись в обычный `SharedPreferences`, что допустимо только для проверки API, но не для реального использования.

Нужно заменить MVP-хранилище так, чтобы `CredentialsStore` остался единственной точкой доступа к учетным данным, а реализацию можно было менять без изменений в UI, repositories и network-коде.

## Критерии выбора

- App password не должен храниться в plaintext-файле preferences.
- Решение должно работать на минимальном SDK проекта.
- Внешний контракт `CredentialsStore` должен сохраниться.
- Logout должен очищать сохраненные credentials.
- Решение не должно раздувать зависимости, если platform API достаточно.

## Альтернативы

### Обычный `SharedPreferences`

Плюсы:

- Минимум кода.
- Просто тестировать.

Минусы:

- App password хранится в plaintext.
- Не соответствует даже базовому ожиданию для постоянного auth-секрета.

Вывод: больше не подходит после tracer bullet.

### AndroidX Security Crypto / `EncryptedSharedPreferences`

Плюсы:

- Готовая обертка для encrypted preferences.
- Простая интеграция.

Минусы:

- По AndroidX release notes на 18 июня 2026 `security-crypto` имеет stable `1.1.0`, но API уже deprecated в пользу platform APIs и прямого Android Keystore.
- Добавляет зависимость ради небольшого локального сценария.
- Все равно важно держать storage за собственным `CredentialsStore`, потому что направление библиотеки изменилось.

Вывод: не используем для нового кода.

### Прямой Android Keystore + encrypted payload в `SharedPreferences`

Плюсы:

- Секрет шифруется AES/GCM ключом из Android Keystore.
- В preferences лежат только ciphertext и iv.
- Не нужна новая dependency.
- Решение изолировано внутри `CredentialsStore`.

Минусы:

- Больше собственного security-sensitive кода.
- Нужно аккуратно обрабатывать поврежденные данные и невозможность расшифровки.
- Это не полноценная account/session архитектура, а только secure storage для одного аккаунта MVP-1.

Вывод: лучший вариант для текущего MVP.

## Решение

Использовать `KeystoreCredentialsStore`.

Формат:

```text
AccountCredentials -> JSON -> AES/GCM encrypt -> SharedPreferences(ciphertext, iv)
```

Ключ:

```text
AndroidKeyStore alias: nextgallery.credentials.v1
algorithm: AES
block mode: GCM
padding: none
purposes: encrypt/decrypt
```

Миграцию старого plaintext-формата не делаем. Это осознанное ломающее изменение: после обновления dev-сессию нужно пройти заново через Login Flow v2. Причина - проект еще в MVP/tracer bullet стадии, пользователей и совместимости формата storage пока нет, а миграционный код увеличивает поверхность auth-хранилища без продуктовой пользы.

Несовместимые storage-состояния не чистим автоматически. Если локальное состояние стало неудобным или сломанным, на текущей стадии допустимо переустановить приложение. Это следует из dev-допущения в MVP: приложение установлено только на одном устройстве автора, а compatibility локального состояния пока не является продуктовым требованием.

## Последствия

Плюсы:

- `MainViewModel`, repositories и image loading не знают о способе хранения credentials.
- Logout очищает encrypted store.
- Нет legacy-ветки чтения plaintext credentials.
- Storage format меняется с ломающей совместимостью, старые dev credentials игнорируются.
- Unit-тесты покрывают JSON save/load/clear контракт независимо от Android Keystore.

Минусы:

- Keystore-часть требует проверки на реальном устройстве/эмуляторе.
- При повреждении encrypted payload приложение не пытается восстановить или подчистить состояние; нормальный reset-путь на текущей стадии - переустановка.
- Для нескольких аккаунтов формат придется расширять.

## Источники

- Android Developers: AndroidX Security release notes  
  https://developer.android.com/jetpack/androidx/releases/security

## Открытые вопросы

- Нужен ли позже user-auth-bound key, требующий biometrics/device credential перед доступом к app password?
- Как хранить credentials при поддержке нескольких аккаунтов?
- Нужно ли отдельное instrumentation-тестирование Keystore-backed реализации на CI/emulator?
