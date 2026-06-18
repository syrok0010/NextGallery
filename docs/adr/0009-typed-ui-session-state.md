# ADR 0009: Типизированный UI session state

## Статус

Принято.

## Контекст

После разделения UI на вертикальные slice `MainUiState` оставался плоским состоянием для всего приложения. В нем одновременно жили поля login form, browser login flow, authenticated credentials, Memories timeline, batch loading и общие сообщения.

Такой state начал превращаться в God State: он был удобен для быстрого tracer bullet, но допускал невозможные комбинации вроде `credentials == null` вместе с загруженным timeline, или активный `loginSession` рядом с authenticated session.

## Критерии выбора

- Сократить невозможные состояния на уровне типов.
- Не вводить несколько ViewModel раньше, чем появятся самостоятельные lifecycle и side effects у slice.
- Дать `auth` и `timeline` UI только их slice state, а не весь `MainUiState`.
- Сохранить один `MainViewModel` как orchestration layer на текущем этапе.
- Не менять runtime-поведение.

## Альтернативы

### Оставить плоский `MainUiState`

Плюсы:

- Меньше изменений сейчас.
- Простые `.copy(...)` на верхнем уровне.

Минусы:

- Невозможные состояния остаются возможными.
- UI slice получают лишние поля и знания о других slice.
- При росте приложения `MainUiState` будет становиться все более shallow module.

Вывод: не подходит для дальнейшего роста.

### Сразу выделить несколько ViewModel

Например `AuthViewModel`, `TimelineViewModel`, `DetailViewModel`.

Плюсы:

- Явные state holder по workflow.
- Больше локальности для side effects.

Минусы:

- Сейчас это преждевременный seam: login и timeline еще сильно завязаны на общий app session.
- Появится больше DI/lifecycle кода без достаточного leverage.
- Detail пока не имеет самостоятельной загрузки данных.

Вывод: отложить до появления самостоятельных workflow.

### Типизированный session state внутри одного `MainViewModel`

Разделить root state на:

```text
MainUiState
  session: SessionUiState
    SignedOut(LoginUiState)
    SignedIn(credentials, TimelineUiState)
  message: AppMessageUiState
```

Плюсы:

- `SignedOut` и `SignedIn` становятся взаимоисключающими состояниями.
- `auth` получает `LoginUiState`, `timeline` получает `TimelineUiState`.
- `MainViewModel` остается одним orchestration module.
- Позже `LoginUiState` или `TimelineUiState` можно поднять в отдельный state holder без переписывания UI slice.

Минусы:

- Обновления state требуют helper-функций вроде `updateLogin` и `updateTimeline`.
- `MainViewModel` пока все еще содержит side effects разных workflow.

## Решение

Использовать типизированный session state:

- `SessionUiState.SignedOut(LoginUiState)` для login/connect flow.
- `SessionUiState.SignedIn(AccountCredentials, TimelineUiState)` для authenticated app state.
- `AppMessageUiState` для общих status/error сообщений.
- `MainViewModel` остается единственным ViewModel на текущем этапе.
- UI slice принимают свой state: `LoginPanel(LoginUiState, AppMessageUiState, isBusy)` и `TimelinePanel(TimelineUiState, AppMessageUiState, credentials)`.

## Последствия

- Нельзя случайно передать timeline в signed-out UI state.
- Нельзя случайно держать login session как peer authenticated credentials.
- `MainUiState` стал root-состоянием приложения, а не списком всех полей всех экранов.
- Следующий естественный шаг: выделить `TimelineStateHolder` или `TimelineViewModel`, когда timeline получит cache/offline policy, retry policy, thumbnail batching или background sync.

## Открытые вопросы

- Когда именно timeline станет достаточно самостоятельным module для отдельного state holder.
- Нужно ли выделять `AppMessageUiState` по slice, если общие сообщения начнут конфликтовать между login и timeline.
