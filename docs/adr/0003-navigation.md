# ADR 0003: Навигация

## Статус

Принято.

## Контекст

Приложению нужна Compose-first навигация, где авторизация определяет корневой экран, а viewer остаётся связан с живым timeline и shared transition.

## Критерии выбора

- Back stack принадлежит приложению, а не отдельным composable.
- Signed-out и authenticated root states взаимоисключаемы.
- Viewer сохраняет timeline/grid под собой.
- Корень приложения не владеет состоянием и ViewModel всех экранов.
- Route identity использует `MediaId`, а не source identifiers.

## Альтернативы

### Navigation Compose

Зрелый вариант, но проект выбрал современную модель явного back stack Navigation3.

### Все ViewModel в корне приложения

Облегчает сборку общего меню, но заставляет навигацию знать индексацию, содержимое экранов и состояние viewer. Получение ViewModel внутри destinations сохраняет плоский router и локализует экранное состояние.

### Отдельный route для каждого состояния viewer

Упрощает URL-подобную модель, но разрывает координатор grid/viewer transition и live timeline state.

## Решение

Использовать стабильную Navigation3 через `NavKey`, `rememberNavBackStack` и `NavDisplay`.

Корневые routes:

```text
Login
Photos
Albums
Album(location, title)
```

`SessionUiState` синхронизирует один плоский back stack. `NextGalleryApp` получает только `SessionViewModel`, связывает destinations и плавающий переключатель. `PhotosScreen`, `AlbumsScreen` и `AlbumScreen` получают свои ViewModel внутри destination и владеют прокруткой/фильтром. Saveable state вкладок сохраняется при переключении и сбрасывается на границе сессии. Viewer отображается внутри экрана фото или конкретного альбома поверх его сетки и адресуется по `MediaId`; `ViewerTransitionCoordinator` связывает tile bounds, reveal и закрытие viewer. Корень получает только признак видимости viewer для переключателя.

Версия Navigation3 определяется version catalog, а не дублируется в ADR.

## Последствия

- Logout атомарно возвращает приложение к signed-out root.
- Timeline state не уничтожается отдельным detail route во время просмотра.
- Общая оболочка использует repositories: постоянно наблюдает только флаг ошибки, подробную диагностику — пока открыта панель.
- Deep links и самостоятельная route-модель viewer потребуют отдельного решения.

## Открытые вопросы

- Нужны ли deep links на медиаобъект?
