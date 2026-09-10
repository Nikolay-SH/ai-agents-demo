# Живой Docker-стенд без MCP

## Утверждённое направление

Пользователь выбрал настоящие сервисы Docker и human-in-the-loop с подтверждением рестарта. Существующий синтетический режим остаётся для репетиции без Docker.

## Архитектура

Один модуль demo-services запускается в ролях checkout и payment (Spring Boot). Checkout вызывает payment по HTTP, payment пишет в PostgreSQL. Генератор нагрузки постоянно вызывает checkout. Actuator/Prometheus/Grafana показывают реальные показатели. Агент работает на хосте и использует localhost-порты, Docker Compose CLI и JDBC, без MCP и без произвольного shell/SQL.

Состав Compose-проекта: checkout, payment, payments-db, load, prometheus, grafana. Имя проекта фиксировано sherlock-live. HTTP: checkout=18081, payment=18082. PostgreSQL=15432, Prometheus=19090, Grafana=13000. Порты публикуются на 127.0.0.1.

Контракты: POST /checkout на checkout; POST /payments на payment (без обязательного тела). GET /actuator/health, /actuator/info, /actuator/metrics, /actuator/prometheus. GET /demo/status на обоих сервисах возвращает имя, зависимости, version, bootId (новый UUID после старта). POST /demo/faults/close-pool только на payment закрывает реальный Hikari pool: JDBC-запросы начинают завершаться ошибкой, restart его пересоздаёт. Это явно обозначенный fault injection, не случайный production-баг.

## Human-in-the-loop

Tool restartService(service, reason) разрешён только для checkout/payment этого проекта. Фиксирует полный container ID. Консоль требует точную строку approve <request-id>; Enter, EOF и любой другой ввод означают отказ. Решение одноразовое, относится к конкретному ID контейнера. Непосредственно перед рестартом повторно проверяются project/service labels и identity. Команды передаются списком аргументов ProcessBuilder, не через shell. Решение человеку не может передать модель и нет tool approve().

Во время ожидания человека новые tools не выполняются. Время ожидания согласования имеет собственный предел. После approve выполняется docker restart только выбранного ID; агент получает статус и делает новые диагностические запросы. Отказ возвращается в агентный цикл без изменения инфраструктуры. Повторный запрос на тот же сервис после отказа блокируется в пределах запуска.

## Диагностика

listServices, getServiceInfo, getMetrics, getLogs, getDatabaseActivity, restartService. Реальные timestamps/bootId/PID, никаких заранее записанных улик. getLogs читает только containers выбранного compose-проекта. Диагностика БД выполняет фиксированный SELECT, а не SQL от модели. Обёртки Spring и Koog используют одну реализацию и одинаковый системный промпт. Старый EvidenceReview для живого режима не применяется: он знает ответ учебной фикстуры.

## Проверка

Автотесты границы подтверждения: отказ/EOF/таймаут/повтор/неизвестный сервис не вызывают restart; approve вызывает ровно один restart выбранного контейнера; смена ID отменяет действие. Реальные проверки: healthy → close-pool → ошибки → reject (bootId тот же) → approve → bootId меняется → успешные запросы. Блокировку БД можно добавить как следующий сценарий, в котором рестарт payment не помогает; в текущий Docker-стенд она не включена.
