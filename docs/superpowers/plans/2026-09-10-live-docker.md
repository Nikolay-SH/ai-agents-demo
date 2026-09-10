# Live Docker Implementation Plan

**Goal:** Запускаемый Docker-стенд и настоящие tools с подтверждением рестарта внутри цикла агента.

**Architecture:** demo-services и Compose предоставляют реальные API; live-lab содержит ограниченные диагностические адаптеры и консольное согласование. Spring/Koog получают одинаковые tools и промпт.

**Spec:** ../../live-docker-design.md

## Задачи

- [x] Создать demo-services, compose.yaml, docker/ и scripts/lab. Проверить HTTP роли и fault injection тестами, затем healthy/failing состояние в Docker.
- [x] Создать live-lab. Сначала тесты подтверждения с управляемым backend, затем Docker/HTTP/JDBC адаптеры. Публичный интерфейс: listServices(), getServiceInfo(String), getMetrics(String), getLogs(String,String), getDatabaseActivity(), restartService(String,String), все возвращают String.
- [x] Подключить режим LIVE в обоих entrypoint. Native agent loop вызывает restartService и ждёт человека; тот же loop получает отказ/результат исполнения. Никакого fixture-specific EvidenceReview в LIVE.
- [x] Проверить здоровый запрос, закрытие пула, отказ, подтверждение, новый bootId, успешный запрос. Действия ограничить контейнерами sherlock-live, без изменения сторонних ресурсов.
- [x] Повторить build/tests, выполнить независимое ревью, обновить README и сценарий выступления.

Для репетиции согласование доступно через отдельный operator harness без LLM; он пользуется тем же production-кодом LIVE, а не симуляцией. LLM-прогоны отдельно помечаются как реальные и не заменяются сценарной последовательностью.
