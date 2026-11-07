# Проверка исправления ревью #159

После замечания Spec восстановлена граница: обычная первая загрузка получает одноразовое право на COPY в тот же проход, который принял новый receipt. После reopen и targeted retry передаётся режим `Reconcile`, который только читает membership.

На API36 и тестовом Nextcloud выполнен opt-in production-interface flow с реальным MediaStore JPEG. Получены `PUT 201`, ровно один `COPY 201`, затем `PROPFIND` с точным fileId/path/href участника. [HTTP trace](live-flow-http.log) и [manifest](live-flow-manifest.log) не содержат credentials. Flow завершился JUnit `OK (1 test)`.

JVM targeted tests покрывают Initial COPY, отсутствие повторного COPY после неподтверждённого ответа и отмену до mutation. Runtime instrumentation проверяет, что новый receipt получает `Initial`, а retry/reopen — `Reconcile`.

Это подтверждает production interfaces и не является проверкой полного UI selection path. Server-side SIGKILL window для нового Initial COPY в этом прогоне не выполнялся.
