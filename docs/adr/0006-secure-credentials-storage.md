# ADR 0006: Secure storage для Nextcloud app password

## Статус

Принято.

## Контекст

Nextcloud Login Flow v2 возвращает долгоживущий app password для Basic Auth. NextGallery — single-account приложение, поэтому хранит один набор `AccountCredentials`; logout заменяет аккаунт только через полную очистку текущей cloud session.

## Критерии выбора

- App password не хранится в plaintext.
- Решение работает на minSdk проекта без лишней security dependency.
- Остальной код зависит от `CredentialsStore`, а не от формата хранения.
- Повреждённое или отсутствующее значение приводит к signed-out состоянию.
- Logout удаляет сохранённые credentials.

## Альтернативы

### Обычный SharedPreferences

Недостаточен, потому что хранит app password открытым текстом.

### AndroidX Security Crypto

Предоставляет готовую обёртку, но выбранные API deprecated в пользу platform Keystore и добавляют dependency для одного небольшого payload.

### Android Keystore + encrypted payload

Требует собственного security-sensitive adapter, но использует platform API и полностью скрывается за `CredentialsStore`.

## Решение

`KeystoreCredentialsStore` сериализует `AccountCredentials` в JSON и шифрует AES/GCM ключом `nextgallery.credentials.v1` из Android Keystore. В SharedPreferences сохраняются ciphertext и IV.

Формат plaintext-прототипа не мигрируется. Пока действует dev-допущение без compatibility guarantees, несовместимое локальное состояние сбрасывается переустановкой приложения.

## Последствия

- `SessionStore`, repositories и image loading не знают формат credentials.
- При старте наличие расшифрованных credentials определяет signed-in session.
- Logout очищает encrypted payload; список аккаунтов и account switcher не создаются.
- Keystore adapter требует Android runtime проверки, а JSON contract тестируется отдельно.
- Повреждённый payload не восстанавливается автоматически.

## Открытые вопросы

- Нужен ли user-auth-bound key с подтверждением biometrics/device credential.
- Когда compatibility локального storage станет продуктовым требованием.
