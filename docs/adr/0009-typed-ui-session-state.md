# ADR 0009: Типизированная session boundary

## Статус

Принято; первоначальный единый MainViewModel разделён по slices.

Распределение timeline orchestration уточнено [ADR 0012](0012-feature-slices.md): workflow принадлежит TimelineWorkflow; AuthenticatedViewModel адаптирует session и UI state. Остальные решения этого ADR сохраняются.

## Контекст

Signed-out login workflow и authenticated timeline не должны одновременно жить в одном плоском state. По мере роста timeline единый `MainViewModel` также стал владельцем слишком разных lifecycle и был разделён.

## Критерии выбора

- Signed-out и signed-in состояния взаимоисключаемы типами.
- Root navigation наблюдает только session boundary.
- Login и authenticated timeline имеют самостоятельные state holders.
- Credentials не передаются через UI, которому они не нужны.
- Single-account lifecycle остаётся явным.

## Альтернативы

### Плоский root state

Допускает невозможные сочетания login, credentials и timeline и заставляет все screens наблюдать лишние изменения.

### Один orchestration ViewModel

Сохраняет единый entry point, но смешивает polling Login Flow, cache/network timeline и MediaStore lifecycle.

## Решение

- `SessionStore` хранит `StateFlow<SessionUiState>`: `SignedOut` или `SignedIn(AccountCredentials)`.
- `SessionViewModel` предоставляет session root для `NextGalleryApp`.
- `LoginViewModel` владеет `LoginUiState` и Login Flow.
- `AuthenticatedViewModel` владеет timeline, permission, cache и refresh workflow.
- Login success обновляет `SessionStore`; logout очищает credentials/cloud state и переводит store в `SignedOut`.
- Session-aware infrastructure, включая media requests, получает credentials из session boundary, а не через composable parameters.

## Последствия

- Root navigation не знает состояния login form или timeline.
- Slice recomposition и lifecycle локализованы.
- `AuthenticatedViewModel` остаётся крупным coordinator и может потребовать более глубокого timeline module при дальнейшем росте.
- Нельзя одновременно представить несколько signed-in аккаунтов.

## Открытые вопросы

- Какую часть timeline orchestration первой выделить из `AuthenticatedViewModel`, если добавятся upload, albums и sync.

## Проверка Memories при новом входе (#113)

**Контекст и критерии.** Минимальная версия Memories — 10: версии 8/9 дают
молчаливую деградацию функций. Политика поддержки описана в ADR 0018 в ветке
совместимости с Memories 10; этот срез не переносит её реализацию в `main`.
Нужно обнаруживать несовместимость до медиатеки, отличать её от ошибки сети
и сохранить открытие кэшированной ленты без нового обязательного запроса.

**Решение.** После Login Flow v2, до сохранения credentials и `SignedIn`, читаем
авторизованный `GET apps/memories/api/describe`. На стенде Memories 10.1.0 он
возвращает строковое поле `version`; `config` при Basic Auth без дополнительных
заголовков требует CSRF. Числовая major-версия ниже 10 блокирует вход; 10 и выше
проходят минимальную проверку (это не гарантия совместимости с будущими major).
HTTP 404/410 означает недоступное приложение, отсутствующая/некорректная версия —
неизвестную совместимость. Сетевая ошибка и прочие HTTP-ошибки имеют отдельные
сообщения и не объявляют сервер устаревшим. Все эти состояния блокируют вход.

Credentials до успешной проверки остаются только в памяти `LoginViewModel`.
«Повторить проверку» использует их без повторного Login Flow, чей poll token уже
израсходован; «Выйти» отменяет проверку, отбрасывает credentials и открывает форму
входа. После потери процесса до успешной проверки нужен новый Login Flow.

**Альтернативы и последствия.** Проверка при каждом старте с сохранённой сессией
отклонена: недоступная сеть блокировала бы warm start ради редкого понижения
Memories. Существующие сохранённые подключения не перепроверяются; смена версии
или отключение Memories обнаружатся этой проверкой при следующем новом входе.
Одной документации недостаточно: она не останавливает молчаливую деградацию.

**Открытый вопрос.** При появлении пользователей на будущих major Memories нужно
отдельно подтвердить контракт API и уточнить диапазон поддержки.
