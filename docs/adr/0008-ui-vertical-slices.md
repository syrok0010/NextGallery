# ADR 0008: UI по вертикальным slice

## Статус

Принято.

## Контекст

`NextGalleryApp.kt` вырос до большого файла, где одновременно жили навигация, login flow, timeline browser, detail screen, загрузка authenticated images и общие статусные сообщения. Это ухудшало локальность: изменение timeline требовало читать код login/detail, а изменение login было рядом с grid/scrollbar логикой.

Проект остается учебным, но уже содержит несколько пользовательских сценариев. Дальше приложение удобнее развивать как набор вертикальных slice, где UI сценария, его локальные helper-модели и Compose-логика лежат рядом.

## Критерии выбора

- Новый код должен быть легче искать по пользовательскому сценарию.
- `NextGalleryApp` должен оставаться shell: route, back stack, top-level composition.
- Slice не должны дробиться на слишком мелкие файлы без выигрыша в локальности.
- Общий код допустим только когда он реально переиспользуется несколькими slice.
- Рефакторинг не должен менять runtime-поведение.

## Альтернативы

### Один большой `ui` package с файлами по типу виджета

Например `Buttons.kt`, `Images.kt`, `Panels.kt`.

Плюсы:

- Простая структура на старте.
- Легко переиспользовать мелкие composable.

Минусы:

- Локальность хуже: сценарий снова собирается из разрозненных файлов.
- Есть риск получить shallow modules, где файл почти ничего не скрывает за своим interface.

Вывод: не подходит как основной принцип.

### Один файл на каждый composable

Плюсы:

- Маленькие файлы.
- Простая навигация по имени composable.

Минусы:

- Сценарий распадается на слишком много seam без реального leverage.
- Появляется лишняя публичная поверхность между мелкими helper-функциями.

Вывод: использовать только когда composable становится самостоятельным глубоким module.

### Vertical slices

Разделять UI по пользовательским сценариям:

```text
ui/
  NextGalleryApp.kt      -> navigation shell
  auth/                  -> login/connect flow
  timeline/              -> browsing grid, placeholders, scrollbar
  detail/                -> media detail route
  common/                -> small shared UI adapters
```

Плюсы:

- Локальность выше: timeline scrollbar, date headers и grid живут рядом.
- `NextGalleryApp` остается узким shell.
- Внутри slice можно держать helper-типы private, не расширяя interface.
- Будущие тесты проще привязывать к сценарию.

Минусы:

- Между package появляются явные `internal` interface.
- Нужно дисциплинированно не превращать `common/` в свалку.

## Решение

Строить UI по вертикальным slice:

- `ui/auth` - подключение к Nextcloud и browser login flow.
- `ui/timeline` - Memories timeline browser, grouped grid, placeholders, smart scrollbar и статус дозагрузки.
- `ui/detail` - detail route для выбранного media item.
- `ui/common` - только реально общий UI: authenticated image и общий status text.
- `ui/NextGalleryApp.kt` - Navigation3 routes, back stack, top app bar и выбор активного slice.

## Последствия

- Новые UI-сценарии добавляются как отдельные slice package.
- Helper-функции остаются `private` внутри slice, пока не появится реальное переиспользование.
- `common/` требует осторожности: перед выносом туда нужно проверить, что код используется минимум двумя slice и имеет понятный interface.
- Архитектурные изменения внутри slice можно делать локальнее, не смешивая auth/timeline/detail.

## Открытые вопросы

- Когда `Home` shell станет достаточно сложным, нужен ли отдельный `ui/home` slice.
- Нужно ли позже разделить `timeline` на browser UI и timeline interaction policy, если scrollbar/debounce начнут требовать unit-тестов.
