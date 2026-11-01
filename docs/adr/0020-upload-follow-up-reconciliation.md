# ADR 0020: Подтверждение действий после принятой загрузки

## Статус

Принято для безопасного subset #158 поверх #156 / PR #157. Уточняет
[ADR 0019](0019-durable-manual-uploads.md), [ADR 0015](0015-add-to-album.md) и
[ADR 0017](0017-link-uploaded-copies.md). Полное durable AddToAlbum пока блокировано
серверным контрактом; исследование #155 не принимается целиком.

## Контекст

Upload receipt уже доказывает принятую облачную копию. Смерть процесса между
identity write или серверным COPY и записью результата отдельного действия
не должна заставлять заново читать локальный URI или выполнять PUT.

Photos 7 / Nextcloud 34 на стенде публикует точные fileId и исходный путь
участника через DAV. Альбом не публикует DAV resource-id, fileid или ETag.
Memories 10.1 отдаёт числовой `album_id` из Photos DB: удаление и создание
одноимённого альбома меняет ID. Это позволяет сверить исходную коллекцию при
чтении, но этот ID нельзя передать как условие COPY. Сверка перед COPY оставляет
гонку: путь может начать обозначать replacement между запросами.

## Критерии выбора

- Успех передачи неизменяем; link и album имеют собственные durable результаты.
- Повтор link сохраняет тот же MediaId и прежние session guards.
- Чужая сессия, отмена и старый claim не принимают поздний ответ.
- Удалённый/recreated album, заменённый source и неоднозначные DAV свойства
  требуют внимания; одинаковое имя и 409 не являются доказательством успеха.
- Существующие v1 задания и receipts сохраняются без destructive migration.
- Не вводить scheduler, spool, hash/reset, retry budget и сетевые defaults.

## Решение

Journal v2 добавляет nullable JSON результатов link и album. Production migration
1→2 сохраняет остальные колонки. Transfer receipt и pending steps принимаются в
прежней атомарной транзакции; отдельный step receipt принимается только из
Running с актуальными owner, executor и revision. Begin/finish увеличивают
revision. Cancel после accept подавляет незавершённые действия, сохраняя receipt;
поздний ответ не возобновляет действие и не удаляет облачный файл.

Album intent v2 сохраняет owner, имя, точный DAV href и числовой Memories album_id.
Для существующей цели ID выбранной карточки должен совпасть со свежим lookup;
новая цель создаётся до enqueue. Legacy intent не получает новый snapshot при
reopen/retry. Отсутствующий snapshot или неизвестный формат требует внимания.

Модуль `DurableAlbumStep` проверяет источник и исходный альбом за небольшим
interface: snapshot / run. App DI соединяет его с upload; upload не зависит от
albums. Run выполняет только чтение. Он проверяет:

- DAV URL источника внутри files данного uid на том же origin, без userinfo,
  query и fragment; exact href, положительный fileId, owner-id, тип файла и
  право чтения из успешных свойств источника;
- исходный album_id до и после DAV membership, точный owner/name/cluster_id;
- collection type, original-name и read/bind privilege альбома;
- ровно одного участника с нужными fileId, `{fileId}-{basename}` href и исходным
  путём. Противоречие fileId/path/href или неоднозначность не принимаются.

Redirects и HTTP connection retry отключены. Требуется HTTP 207, корректный
multistatus, прямые response/propstat/prop/status элементы, однозначные свойства
в успешном propstat; свойства 404 не доказывают ничего. DTD/entities запрещены,
DAV body ограничен 1 MiB. Неизвестная структура даёт attention.

Доказанный уже существующий участник даёт Done с fileId/albumId/href без COPY.
Отсутствующий участник даёт `ReplayUnsupported`. **Durable runner не делает COPY
даже при первом выполнении**: у этого сервера нет условия на identity коллекции.
Это намеренное ограничение относительно переходного adapter PR157. Файл
загружается и связывается; неисполненное добавление остаётся явным attention.
Прежняя отдельная операция добавления уже облачного объекта не изменена и не
получает новых durable гарантий.

Reopen возвращает Running link в Pending. Versioned album с известным snapshot
возвращается в Pending для чтения membership. Legacy album остаётся attention.
Graceful/system stop не создаёт user cancellation; возможный неизвестный исход
шага сохраняется как Uncertain. Только явный retry на Uploads возвращает
Attention/Uncertain шаги в Pending. Retry не снимает receipt и не проверяет
доступность исчезнувшего локального URI. Завершённый link не выполняется снова.

Uploads показывает «Загружен»/«Уже в облаке», причину незавершённого действия,
«Повторить действие» и отмену. Просмотр сводки не завершает задание. Новый
результат retry снова виден; unresolved действие продолжает блокировать logout
до результата или явной отмены.

## Альтернативы

- Повторять COPY после 409/существования альбома: не доказывает нужный membership.
- Сравнивать Memories ID до COPY и считать это защитой: не закрывает гонку с
  пересозданием destination. Поэтому отклонено и для первой durable попытки.
- Сохранить прежний небезопасный adapter только для первой попытки: сохраняет
  обычный UX, но создаёт исключение из обещания identity; отклонено.
- Остановить всю очередь при неподдержанном album contract: теряет полезный
  независимый upload/link результат. Выбран отдельный отменяемый attention.

## Последствия

Для local→album на текущем сервере сохраняются upload и explicit link, но
автоматическое завершение album-step недоступно. Пользователь может отменить
действие и отдельно добавить уже облачный объект; либо повторить проверку после
добавления в исходный альбом вне durable runner. Retry с отсутствующим участником
не меняет сервер и честно возвращает ту же причину без автоматического цикла.

Две Room DB по-прежнему не образуют общую транзакцию. Link может повториться
между identity write и journal commit; повторяемый production link ограничивает
это окно. Success receipt фиксирует подтверждённый результат в момент чтения,
а не обещает, что другой клиент никогда не удалит участника позднее.

## Открытые вопросы

- Для полноценного AddToAlbum нужен серверный endpoint/условие, атомарно
  проверяющее album_id и source fileId перед вставкой membership, либо доказанный
  эквивалент в DAV (identity + применимая precondition). Текущий Photos DAV его
  не предоставляет. Не добавлять name-addressed COPY в runner без нового
  исследования и проверки такого контракта.
- Сетевые настройки, WM/UIDT, backoff и пользовательская политика параллелизма
  остаются следующими срезами. Полная очистка #53 и identity research #115 отдельно.

## Источники

- [Photos PropFindPlugin, bd23dd1](https://github.com/nextcloud/photos/blob/bd23dd102b5c34ac0b17130712349a1ec3fdd07d/lib/Sabre/PropFindPlugin.php): свойства альбома и участников.
- [Photos AlbumRootBase, bd23dd1](https://github.com/nextcloud/photos/blob/bd23dd102b5c34ac0b17130712349a1ec3fdd07d/lib/Sabre/Album/AlbumRootBase.php): COPY и имена участников.
- [Photos AlbumRoot, bd23dd1](https://github.com/nextcloud/photos/blob/bd23dd102b5c34ac0b17130712349a1ec3fdd07d/lib/Sabre/Album/AlbumRoot.php): проверка owner/fileId, конфликт повторного добавления.
- [Live API traces и границы проверки](../evidence/durable-upload-158/README.md).
