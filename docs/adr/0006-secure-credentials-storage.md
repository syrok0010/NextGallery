# ADR 0006: Сессия, вход и хранение credentials

## Статус

Принято.

## Контекст

Nextcloud Login Flow v2 возвращает долгоживущий app password для Basic Auth. NextGallery — single-account приложение, поэтому хранит один набор `AccountCredentials`; выход удаляет все данные приложения и завершает процесс, и следующий вход начинается как после свежей установки (#53).

Signed-out сценарий входа и авторизованная медиатека не должны жить в одном плоском состоянии. Кроме того, минимальная поддерживаемая версия Memories — **11** (базовый проверяемый сервер — 11.1.4, #234): несовместимый сервер нужно обнаруживать до входа в медиатеку, не ломая warm start кэшированной ленты.

## Критерии выбора

- App password не хранится в plaintext; решение работает на minSdk проекта без лишних security dependencies.
- Signed-out и signed-in состояния взаимоисключаемы типами; root navigation наблюдает только session boundary.
- Остальной код зависит от `CredentialsStore` / `SessionStore`, а не от формата хранения, и не пробрасывает credentials через UI.
- Повреждённое или отсутствующее значение приводит к signed-out состоянию.
- Несовместимая версия Memories обнаруживается при новом входе, отличима от сетевой ошибки и не требует обязательного сетевого запроса на каждом старте.

## Альтернативы

### Обычный SharedPreferences или AndroidX Security Crypto

Обычный SharedPreferences хранит app password открытым текстом. Выбранные API AndroidX Security Crypto deprecated в пользу platform Keystore и добавляют зависимость ради одного небольшого payload.

### Плоский root state или единый orchestration ViewModel

Плоский state допускает невозможные сочетания login, credentials и медиатеки. Единый ViewModel смешивает polling Login Flow, индексацию и lifecycle экранов.

### Поддержка Memories 10 параллельно с 11 или проверка версии на каждом старте

Поддержка двух контрактов Memories требует двойных веток без пользы на раннем этапе (один пользователь, базовый стенд 11.1.4). Проверка при каждом старте блокировала бы warm start без сети ради редкого понижения версии сервера.

## Решение

### Типизированная session boundary

- `SessionStore` хранит `StateFlow<SessionUiState>`: `SignedOut` или `SignedIn(AccountCredentials)`.
- `SessionViewModel` предоставляет session root и действие выхода для `NextGalleryApp`.
- `LoginViewModel` владеет `LoginUiState` (`LoginAttempt` с взаимоисключающими фазами) и Login Flow v2. Credentials сохраняются вне Main-потока до публикации `SignedIn`; ошибка позволяет начать новый Login Flow.
- Индексация и операции медиатеки работают вне UI в application/session-scoped компонентах (`MediaLibraryCoordinator`, `AlbumCatalogRepository`, `UploadQueue`, `MediaDeletionRepository`, ADR 0001/0002), а не в общем screen ViewModel.
- Session-aware инфраструктура получает credentials из `SessionStore`, а не через параметры composable.
- Выход выполняется через `ActivityManager.clearApplicationUserData()` (ADR 0010). Пока есть активные операции (загрузка, включая ожидающие задания и шаги после загрузки, добавление в альбом или облачное удаление), выход заблокирован; отсутствие активных операций перепроверяется непосредственно перед вызовом очистки, чтобы закрыть гонку с операцией, стартовавшей при открытом меню (#102). Непоказанные итоги операций выход не блокируют.

### Хранение credentials и `userId`

- `KeystoreCredentialsStore` сериализует `AccountCredentials` в JSON и шифрует AES/GCM ключом `nextgallery.credentials.v1` из Android Keystore. В SharedPreferences сохраняются ciphertext и IV.
- Кроме server URL, login name и app password, `AccountCredentials` хранит `userId` — uid Nextcloud для DAV-путей. Login name может быть e-mail и не совпадать с uid, а uid аккаунта не меняется, поэтому он запрашивается один раз сразу после Login Flow (`GET /ocs/v2.php/cloud/user`), а не перед каждой DAV-операцией. Если uid прочитать не удалось, вход завершается ошибкой и начинается заново: poll token уже израсходован.
- Неполная или повреждённая запись сбрасывается и ведёт к `SignedOut` без миграций.

### Проверка версии Memories при новом входе (#113)

- После Login Flow v2, до сохранения credentials и публикации `SignedIn`, выполняется авторизованный `GET apps/memories/api/describe` (через разовый `MemoriesApiClient` с проверяемыми credentials, ADR 0005). Эндпоинт `config` при Basic Auth без дополнительных заголовков требует CSRF, тогда как `describe` возвращает строковое поле `version`.
- Числовая major-версия ниже `11` блокирует вход; `11` и выше проходят минимальную проверку (без гарантии совместимости с будущими major).
- HTTP `404`/`410` означают недоступное приложение Memories (`MemoriesApiClient.describe` возвращает `null`, срез входа не разбирает HTTP-статусы сам). Отсутствующая/некорректная версия даёт состояние неизвестной совместимости. Сетевая ошибка и прочие HTTP-ошибки имеют отдельные сообщения и не объявляют сервер устаревшим. Все эти состояния блокируют вход.
- До успешной проверки credentials остаются только в памяти `LoginViewModel`. «Повторить проверку» использует их без повторного Login Flow, чей poll token уже израсходован; «Выйти» отменяет проверку, отбрасывает credentials и возвращает к форме входа. После потери процесса до успешной проверки нужен новый Login Flow.
- Существующие сохранённые подключения на старте не перепроверяются; изменение версии или отключение Memories обнаружатся при следующем новом входе.

## Последствия

- Root navigation не знает состояния формы входа или медиатеки; `SessionStore`, репозитории и загрузка изображений не знают формат хранения credentials.
- При старте наличие расшифрованных credentials определяет `SignedIn` без сетевого запроса.
- Выход стирает encrypted payload вместе со всеми остальными данными приложения; списка аккаунтов и переключения профилей нет.
- Keystore adapter требует Android runtime проверки, JSON contract тестируется на JVM.
- Повреждённый payload не восстанавливается автоматически.

## Открытые вопросы

- Нужен ли user-auth-bound key с подтверждением biometrics/device credential.
- Когда compatibility локального storage станет продуктовым требованием.
- При появлении пользователей на будущих major-версиях Memories потребуется отдельно подтвердить контракт API и уточнить диапазон поддержки.
