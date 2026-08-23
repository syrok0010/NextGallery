# ADR 0003: Навигация

## Статус

Принято.

## Контекст

Приложению нужна Compose-first навигация, где авторизация определяет корневой экран, а viewer остаётся связан с живым timeline и shared transition.

## Критерии выбора

- Back stack принадлежит приложению, а не отдельным composable.
- Signed-out и authenticated root states взаимоисключаемы.
- Viewer сохраняет timeline/grid под собой.
- Route identity использует `MediaId`, а не source identifiers.

## Альтернативы

### Navigation Compose

Зрелый вариант, но проект выбрал современную модель явного back stack Navigation3.

### Отдельный route для каждого состояния viewer

Упрощает URL-подобную модель, но разрывает координатор grid/viewer transition и live timeline state.

## Решение

Использовать стабильную Navigation3 через `NavKey`, `rememberNavBackStack` и `NavDisplay`.

Корневые routes:

```text
Login
Authenticated
```

`SessionUiState` синхронизирует root back stack. Viewer отображается поверх authenticated timeline и адресуется по `MediaId`; `ViewerTransitionCoordinator` связывает tile bounds, reveal и закрытие viewer.

Версия Navigation3 определяется version catalog, а не дублируется в ADR.

## Последствия

- Logout атомарно возвращает приложение к signed-out root.
- Timeline state не уничтожается отдельным detail route во время просмотра.
- Deep links и самостоятельная route-модель viewer потребуют отдельного решения.

## Открытые вопросы

- Нужны ли deep links на медиаобъект?
