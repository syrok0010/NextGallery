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

- `SessionUiState` синхронизирует один плоский back stack (`syncedBackStack`). `NextGalleryApp` связывает session boundary, destinations и плавающий переключатель вкладок (`LibraryIsland`). `MainActivity` переводит `AppLinks` в команды того же `AppNavigator`, который получают экраны через DI.
- Экраны получают свои зависимости внутри destination: `PhotosScreen` и `UploadsScreen` инжектируют сторы и репозитории через `koinInject`, а `AlbumsScreen` и `AlbumScreen` используют свои ViewModel (`AlbumsViewModel`, `AlbumContentsViewModel`) и владеют прокруткой/фильтром.
- Saveable state вкладок (`Photos`, `Albums`) сохраняется через `rememberSaveableStateHolder` при переключении и сбрасывается на границе сессии.
- Viewer отображается внутри экрана фото или конкретного альбома поверх его сетки и адресуется по `MediaId`; `ViewerTransitionCoordinator` связывает tile bounds, reveal и закрытие viewer. Корень получает только признаки видимости viewer и режима выбора для скрытия нижнего переключателя вкладок.

Версия Navigation3 определяется version catalog, а не дублируется в ADR.

### Модуль router и команды

`router` — отдельный логический пакет с маршрутами, командами и app-level `AppNavigator` из DI. Feature-код может зависеть от router и не импортирует app. Отдельный Gradle-проект сейчас не нужен: он добавил бы build wiring без независимого жизненного цикла или платформы.

Альтернатива с владением стеком в singleton упрощала бы вызов перехода, но потребовала бы собственной интеграции saved state. Поэтому `rememberNavBackStack` остаётся в композиции; navigator хранит FIFO команд до появления авторизованного активного host. UI и notification links используют один путь.

Host собирает команды только в RESUMED и после входа. `consume` проверяет id головы очереди, меняет стек и подтверждает исполнение без suspension в одном вызове; старый collector не повторяет обработанную команду, ошибка host оставляет её в очереди. Пересоздание Activity сохраняет стек штатным Navigation3 и использует тот же navigator; начальный intent повторно не публикуется из saved instance. При повторном открытии верхнего маршрута сравнивается его content identity, поэтому title альбома не создаёт дубль. Очередь process-local; отдельное durable хранилище команд не вводится.

Навигационные callbacks убраны с промежуточных экранов/scaffold. Callback logout остаётся отдельной session-командой у root и доступен chrome через CompositionLocal; он сохраняет проверку операций, очистку данных и завершение процесса, а не заменяется командой back. Callback геометрии, видимости viewer, изменения selection и завершения операций остаются UI-контрактами.
## Последствия

- Выход из аккаунта очищает данные приложения и завершает процесс (ADR 0010); при старте без сессии корневым экраном является `Login`.
- Timeline state не уничтожается отдельным detail route во время просмотра.
- Уведомление загрузки открывает экран `Uploads` поверх текущей сессии через `AppLinks.Uploads`.
- Deep links на конкретный медиаобъект и самостоятельная route-модель viewer потребуют отдельного решения.

## Открытые вопросы

- Нужны ли внешние deep links на медиаобъект?
