# Исправления Standards review #157

Проверенный runtime: `80ef6c115b339a4799ff0b7a36a6f8ff22c20814`. Основание review: `63d7cc115258be5bfa6052ae8d055629ed567900`.

- Остановка executor переводит незавершённые файлы и шаги всего переданного сервису прохода в Attention и снимает handoff. Очистка выполняется вне отменённого coroutine scope, сохраняет receipt и ограждает позднее принятие revision/executor. Обработана остановка во время файла и между партиями; Attention не создаёт немедленного restart-loop.
- Просмотренная сводка Attention отмечается независимо от unfinished. Задание и история сохраняются, logout остаётся запрещён. Изменение результата, включая последующую отмену, публикует новую сводку.

Два новых instrumentation-теста сначала упали на исходном коде: остаток переданного прохода оставался Waiting, а сводка Attention не исчезала после consumeResult. [Реальный red excerpt](standards-red-excerpt.log).

После исправления прошли [проверки](standards-green.log): spotlessCheck, feature boundaries, 255 app JVM, 7 core-media JVM, assembleAutomation, 29 instrumentation на единственном API36 `nextgallery-api36-dev`. Ошибок и skipped нет. Все проверки последовательные с ограничением Gradle RAM/одного worker. Добавлен третий сценарий остановки между партиями: принятый receipt не теряется, pending link/album требуют внимания, отмена не повторяет PUT и разрешает logout.

Тесты используют настоящий файловый Room, production queue/journal и управляемые transfer/link/album handlers. Это проверка протокола и wiring; системный onTimeout/квоты, живые DAV/album/link и SIGKILL-сценарий заново не выполнялись. Первоначальное ручное evidence в README относится к своему прежнему runtime SHA.
