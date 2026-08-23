# ADR 0009: Типизированная session boundary

## Статус

Принято; первоначальный единый MainViewModel разделён по slices.

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
