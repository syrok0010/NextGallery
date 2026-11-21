# ADR 0003: Навигация

## Статус

Принято.

## Контекст

Приложению нужна Compose-first навигация, где авторизация определяет корневой экран, вкладки сохраняют состояние прокрутки и фильтра, вложенные экраны (альбом, экран загрузок) открываются поверх вкладок, а viewer остаётся связан с живым timeline и shared transition.

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

Облегчает сборку общего меню, но заставляет навигацию знать индексацию, содержимое экранов и состояние viewer. Локальная инъекция state holders внутри destinations сохраняет плоский router и локализует экранное состояние.

### Отдельный route для каждого состояния viewer

Упрощает URL-подобную модель, но разрывает координатор grid/viewer transition и live timeline state.

## Решение

Использовать стабильную Navigation3 через `NavKey`, `rememberNavBackStack` и `NavDisplay`.

Корневые routes (`NextGalleryRoute`):

```text
Login
Photos
Albums
Album(location, title)
Uploads
```

- `SessionUiState` синхронизирует один плоский back stack (`syncedBackStack`). `NextGalleryApp` получает только `SessionViewModel`, связывает destinations, плавающий переключатель вкладок (`LibraryIsland`) и входящие внутренние ссылки (`AppLinks.Uploads` → `linkRoute()` → `NextGalleryRoute.Uploads`, собираются только в состоянии `SignedIn`).
- Экраны получают свои зависимости внутри destination: `PhotosScreen` и `UploadsScreen` инжектируют сторы и репозитории через `koinInject`, а `AlbumsScreen` и `AlbumScreen` используют свои ViewModel (`AlbumsViewModel`, `AlbumContentsViewModel`) и владеют прокруткой/фильтром.
- Saveable state вкладок (`Photos`, `Albums`) сохраняется через `rememberSaveableStateHolder` при переключении и сбрасывается на границе сессии.
- Viewer отображается внутри экрана фото или конкретного альбома поверх его сетки и адресуется по `MediaId`; `ViewerTransitionCoordinator` связывает tile bounds, reveal и закрытие viewer. Корень получает только признаки видимости viewer и режима выбора для скрытия нижнего переключателя вкладок.

Версия Navigation3 определяется version catalog, а не дублируется в ADR.

## Последствия

- Выход из аккаунта очищает данные приложения и завершает процесс (ADR 0010); при старте без сессии корневым экраном является `Login`.
- Timeline state не уничтожается отдельным detail route во время просмотра.
- Уведомление загрузки открывает экран `Uploads` поверх текущей сессии через `AppLinks.Uploads`.
- Deep links на конкретный медиаобъект и самостоятельная route-модель viewer потребуют отдельного решения.

## Открытые вопросы

- Нужны ли внешние deep links на медиаобъект?
