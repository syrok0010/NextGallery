# Durable действия после upload: #158

## Проверенный серверный контракт

01.11.2026: `http://test.syrok/`, uid `test`, Nextcloud 34.0.5,
Photos 7.0.0, Memories 10.1.0. Стенд не останавливался и не перезапускался.
[Записанные запросы](api-trace.json) содержат только одноразовые task-объекты,
без Authorization/app password.

- Альбом не отдаёт DAV resource-id/fileid/etag; эти свойства имеют 404 propstat.
- Memories `album_id` поменялся 36→37 при удалении/создании того же имени.
- COPY добавил fileId 338, а повтор дал 409. Точное membership доказано отдельным
  Depth:1: fileId 338, href `338-durable158-contract.txt`, исходный путь
  `/Photos/durable158-contract.txt` в успешном propstat.
- Существование альбома и 409 не доказывают нужного участника.

Upstream проверен отдельно по закреплённому Photos
`bd23dd102b5c34ac0b17130712349a1ec3fdd07d`: `PropFindPlugin`, `AlbumRootBase`,
`AlbumRoot`; установленный код стенда прочитан независимо. SHA256 скачанных
upstream файлов: PropFindPlugin
`71b0843079329926e90cdef13dbf7c8c32d03aba456a710550da7d4388563350`,
AlbumRootBase `faa89d445aadbc87762b1d77902830dd489da04ac68ce04edb5b6849b80ea291`.
Установленный Memories `Db/AlbumsQuery::getList` выбирает `photos_albums.album_id`
и ограничивает собственные альбомы uid; `count` зависит от индексации и здесь не
используется как membership proof.

## Принятая граница

[ADR 0020](../../adr/0020-upload-follow-up-reconciliation.md) разрешает только
проверку уже существующего membership. Durable runner не делает COPY, включая
первую попытку. Отсутствующий участник требует внимания; upload/link сохраняются.

**Blocker полного AddToAlbum:** нужен серверный атомарный insert membership с
условиями на album_id и source fileId либо доказанный DAV эквивалент. Отдельный
lookup перед name-addressed COPY оставляет гонку с replacement и не заменяет
такой контракт. Обычная отдельная cloud-only операция не изменена; новых durable
гарантий для неё нет. Это ограничение относится и к принятию результата live flow.
