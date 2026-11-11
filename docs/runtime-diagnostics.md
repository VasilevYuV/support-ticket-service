# Диагностика отказов приложения

**Автор:** Васильев Юрий, 4П1.23

## 1. Порядок диагностики

Сначала проверить состояние контейнеров и последние логи, затем готовность HTTP и доступность PostgreSQL. Статус running означает только работающий процесс. При unhealthy процесс может продолжать работать; restart unless-stopped сам по себе не перезапускает такой контейнер. При exited сначала найти причину завершения в логах.

| Признак | Что проверить | Восстановление |
|---|---|---|
| readiness 503, db DOWN | Состояние postgres, логи обоих сервисов, доступность БД, правильность адреса и учётных данных | Вернуть доступность БД или исправить подключение, дождаться readiness 200 |
| readiness 503, db UP | Компоненты readiness, результат Docker healthcheck, логи приложения | Устранить причину DOWN, затем повторить HTTP-проверку и проверить healthy |
| Приложение exited, ошибка Flyway | SQL State, файл и строка миграции, flyway_schema_history и фактическая схема | Определить, откатилась ли транзакция, исправить причину и повторить запуск с согласованным набором миграций |

В этом проекте readiness включает JDBC-проверку db, а liveness отражает жизнеспособность приложения. Поэтому liveness 200 при недоступной БД не означает готовность обслуживать заявки. HTTP 503 для DOWN и настройка групп описаны в [документации Spring Boot](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health).

Обычный запуск скрывает детали health. Учебный override включает их только в отдельном локальном окружении, сокращает ожидание JDBC до 2 секунд и отключает автоматический перезапуск приложения. Эти настройки не включаются в основной compose.yaml. Файлы scripts/diagnostics исключены из контекста образа через .dockerignore; ошибочная V3 подключается только отдельным read-only mount.

## 2. Подготовка отдельного окружения

Нужны PowerShell 7, Docker Compose, Node.js/npm для Newman и свободный локальный порт. Команды выполняются из корня репозитория. Каждый запуск использует новое имя проекта и отдельный том. Значение APP_PORT 18083 заменить, если порт занят.

```powershell
$project = 'support-diagnostics-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$port = 18083
New-Item -ItemType Directory -Force build | Out-Null
$envFile = Join-Path $PWD "build/$project.env"
@(
    'DB_NAME=support_service'
    'DB_USERNAME=support_service'
    ('DB_PASSWORD=' + [Guid]::NewGuid().ToString('N'))
    "APP_PORT=$port"
    'APP_IMAGE=support-service:diagnostics'
) | Set-Content $envFile -Encoding utf8
$dc = @('compose', '--env-file', $envFile, '-p', $project,
    '-f', 'compose.yaml', '-f', 'scripts/diagnostics/compose.lab.yaml')
$baseUrl = "http://127.0.0.1:$port"
docker @dc up -d --build --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { throw 'Окружение не запустилось.' }
npm exec --yes --package=newman@6.2.1 -- newman run `
    docs/postman/support-service.postman_collection.json `
    --env-var "baseUrl=$baseUrl" --bail --timeout-request 10000
if ($LASTEXITCODE -ne 0) { throw 'Контрольные данные не подготовлены.' }
```

Ожидаются два healthy-контейнера, 21 запрос и 42 проверки без ошибок. Коллекция запускается один раз на пустой базе. При повторном заполнении её защита остановит выполнение. С готовым проверенным образом можно указать его тег в APP_IMAGE и использовать --no-build вместо --build; именно так выполнена проверка BL-23 с образом BL-21.

До отказов сохранить полный снимок. [snapshot.sql](../scripts/diagnostics/snapshot.sql) читает категории, заявки, историю, историю Flyway, состояния identity, столбцы, ограничения и индексы без изменения БД.

```powershell
$snapshotSql = Get-Content scripts/diagnostics/snapshot.sql -Raw
$before = docker @dc exec -T postgres psql -U support_service -d support_service `
    -At -v ON_ERROR_STOP=1 -c $snapshotSql
if ($LASTEXITCODE -ne 0) { throw 'Исходный снимок не получен.' }
$before | Set-Content "build/$project-before.json"
```

При каждом отказе выполнять общий набор диагностики. Последние результаты healthcheck находятся в State.Health.Log; для контейнера приложения достаточно получить State, без вывода переменных окружения с паролем.

```powershell
docker @dc ps -a
$appId = docker @dc ps -a -q app
docker inspect --format '{{json .State}}' $appId
docker @dc logs --no-color --tail 180 app postgres
Invoke-WebRequest "$baseUrl/actuator/health/readiness" -SkipHttpErrorCheck
Invoke-WebRequest "$baseUrl/actuator/health/liveness" -SkipHttpErrorCheck
```

При exited HTTP может завершиться ошибкой соединения: это ожидаемо, сервер не готов. Состояние health может некоторое время оставаться starting; повторить проверку после нескольких проб. У учебного окружения интервал 2 секунды, две неуспешные пробы и начальный период 10 секунд. Если процесс завершён, ориентироваться на Status/ExitCode и логи, а не только на Health.Status.

## 3. База недоступна

```powershell
docker @dc stop postgres
```

Выполнить общий набор диагностики. Ожидаются остановленный postgres и работающий, но unhealthy app. Readiness возвращает 503 с db DOWN; liveness остаётся 200. В логах приложения видны ошибка подключения/JDBC и тайм-аут Hikari. У остановленного контейнера exec выполнить нельзя. Если postgres работает, проверить приём соединений:

```powershell
docker @dc exec -T postgres pg_isready -h 127.0.0.1 -U support_service -d support_service
```

pg_isready внутри postgres проверяет доступность сервера в его контейнере, но не заменяет проверку JDBC из приложения. При работающем postgres и db DOWN сопоставить адрес сервиса postgres:5432, имя базы и пользователя с настройками приложения; ошибки аутентификации искать в логах. Изменение POSTGRES_PASSWORD у уже созданного тома не меняет пароль существующей роли.

В этом сценарии причина известна: postgres остановлен. Запустить прежний проект с тем же томом:

```powershell
docker @dc up -d --no-build --wait --wait-timeout 120
if ($LASTEXITCODE -ne 0) { throw 'База или приложение не восстановились.' }
```

Ожидаются оба healthy и readiness 200. Выполнить контроль из раздела 6 перед следующим отказом.

## 4. Приложение не проходит health check

[compose.unhealthy.yaml](../scripts/diagnostics/compose.unhealthy.yaml) добавляет diskSpace в readiness и задаёт недостижимый порог свободного места 1000000000000000 байт. Диск при этом не заполняется.

```powershell
docker @dc -f scripts/diagnostics/compose.unhealthy.yaml up -d --no-build --force-recreate app
if ($LASTEXITCODE -ne 0) { throw 'Сценарий не запустился.' }
```

Выполнить общий набор диагностики и pg_isready. Ожидаются postgres healthy, app running/unhealthy, readiness 503 с diskSpace DOWN и db UP, liveness 200. В логах приложения указан недостаток свободного места относительно заданного порога; в Docker healthcheck curl получает 503. Причина определяется компонентом diskSpace, а не предполагается по общему статусу DOWN.

Восстановление: убрать дополнительный override из команды и пересоздать app. Простой restart не изменит переменные окружения контейнера.

```powershell
docker @dc up -d --no-build --wait --wait-timeout 120 --force-recreate app
if ($LASTEXITCODE -ne 0) { throw 'Готовность приложения не восстановилась.' }
```

В реальном отказе diskSpace сначала проверить фактический объём и порог, затем устранить причину. Удаление учебного override исправляет только намеренно изменённую конфигурацию этого сценария. Выполнить контроль из раздела 6.

## 5. Ошибка миграции

[compose.migration-failure.yaml](../scripts/diagnostics/compose.migration-failure.yaml) подключает дополнительную учебную V3. Она создаёт diagnostic_probe, затем обращается к отсутствующей diagnostic_missing_table. Файлы V1/V2 приложения не меняются. Это воспроизведение эксплуатационного отказа, а не новый дефект бизнес-кода в журнале BL-18.

```powershell
docker @dc -f scripts/diagnostics/compose.migration-failure.yaml up -d --no-build --force-recreate app
if ($LASTEXITCODE -ne 0) { throw 'Контейнер сценария не создан.' }
```

Успешный up без --wait подтверждает запуск контейнера, но не успешный запуск Spring Boot. Дождаться exited и выполнить общий набор диагностики. Ожидаются ExitCode 1, ошибка Flyway для V3, SQL State 42P01 и имя отсутствующей таблицы. PostgreSQL продолжает работать. Проверить историю и отсутствие пробной таблицы:

```powershell
docker @dc exec -T postgres psql -U support_service -d support_service -v ON_ERROR_STOP=1 `
    -c "SELECT installed_rank, version, script, success FROM flyway_schema_history ORDER BY installed_rank; SELECT to_regclass('public.diagnostic_probe') AS diagnostic_probe;"
```

Ожидаются только V1 и V2 с success=true, diagnostic_probe=NULL. В этом сценарии CREATE TABLE и INSERT находятся в одной транзакции PostgreSQL: пробная таблица и запись неуспешной V3 откатились. [Flyway откатывает миграцию при возможности](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/migration-error-and-logging-handling); для нетранзакционных операций результат может отличаться, поэтому необходима проверка схемы и истории.

Восстановление учебного окружения: исключить override с дополнительным расположением миграций и mount. Снова запустится исходный набор V1/V2, которому соответствует неизменившаяся база.

```powershell
docker @dc up -d --no-build --wait --wait-timeout 120 --force-recreate app
if ($LASTEXITCODE -ne 0) { throw 'Приложение после ошибки миграции не восстановилось.' }
```

Для реальной ошибочной миграции определить, какие операции успели примениться, и подготовить исправление на копии базы. Успешно применённые версии не переписывать. flyway clean, удаление истории и безусловный repair не являются способом диагностики: [repair изменяет историю миграций](https://documentation.red-gate.com/flyway/reference/commands/repair) и не исправляет произвольные изменения схемы. Здесь эти команды не требовались. Проверка возврата к предыдущему образу через pipeline остаётся BL-27.

## 6. Контроль после восстановления

После каждого сценария проверить healthy обоих контейнеров, readiness/liveness 200, API и полное совпадение снимка. При сравнении не изменять контрольные данные параллельно.

```powershell
docker @dc ps -a
Invoke-RestMethod "$baseUrl/actuator/health/readiness"
Invoke-RestMethod "$baseUrl/actuator/health/liveness"
docker @dc exec -T postgres pg_isready -h 127.0.0.1 -U support_service -d support_service
Invoke-RestMethod "$baseUrl/api/categories"
Invoke-RestMethod "$baseUrl/api/tickets"
Invoke-RestMethod "$baseUrl/api/reports/summary?from=0001-01-01T00:00:00Z&to=9999-12-31T23:59:59Z"
$after = docker @dc exec -T postgres psql -U support_service -d support_service `
    -At -v ON_ERROR_STOP=1 -c $snapshotSql
if ($LASTEXITCODE -ne 0) { throw 'Контрольный снимок не получен.' }
if ($before -cne $after) { throw 'Снимок базы изменился.' }
```

После всех проверок остановить только учебный проект, сохранив том:

```powershell
docker @dc down
```

Не добавлять -v: эта команда удаляет том с данными. Учебный env-файл и результаты в build не включаются в Git; clean удаляет их, поэтому нужные материалы проверки заранее сохранить отдельно.

## 7. Результаты BL-23

Проверено 11.11.2026 в ветке feature-c/runtime-diagnostics поверх коммита e6edad0 (BL-22). Окружение: PowerShell 7, Docker Engine 28.4.0, Compose 2.39.2-desktop.1, PostgreSQL 18.6, Spring Boot 4.1.2. Использован существующий образ support-service:bl21 с ID sha256:dcd40f87d519981fb62ea136854fe083dd1a9ef83b4ef27e3da1b4e443c591fd; исходники приложения после его сборки не изменялись.

| Проверка | Фактический результат |
|---|---|
| Новый проект support-bl23-880fb967, отдельный том, порт 60051 | Два healthy-контейнера; Newman: 21 запрос, 42 проверки, 0 ошибок |
| Остановка postgres | app running/unhealthy; readiness 503, db DOWN; liveness 200 |
| Запуск прежнего postgres | Два healthy, readiness 200; полный снимок совпал |
| Учебный порог diskSpace | app running/unhealthy; readiness 503, diskSpace DOWN, db UP; liveness 200; pg_isready успешен |
| Исключение override diskSpace | Два healthy, readiness 200; полный снимок совпал |
| Учебная миграция V3 | app exited с кодом 1, SQL State 42P01; postgres healthy; в логах подтверждён rollback |
| Схема сразу после неуспешной V3 | diagnostic_probe отсутствует; только успешные V1/V2; полный снимок совпал |
| Исключение override миграции | Два healthy, readiness 200; полный снимок совпал |
| API после каждого восстановления | Категории, список заявок и отчёт семантически совпали с исходными JSON: 9 сравнений |

Контрольный набор содержит четыре категории, четыре заявки и четыре записи истории. Сравнивались все значения строк, обе последовательности identity, столбцы, ограничения, индексы и история Flyway. Сравнение JSON API учитывает значения и порядок массивов, но не порядок ключей объектов.

spotlessCheck и checkTextConventions завершились успешно. Пароль учебного окружения отсутствует в сохранённых логах и результатах. После проверки выполнен down без -v: контейнеры и сеть учебного проекта удалены, том support-bl23-880fb967_postgres-data сохранён. Окружения BL-21/BL-22 не изменялись.

Артефакты находятся в игнорируемом build: bl23-baseline-newman.json, bl23-*-health.json, bl23-*-container.json, bl23-*-compose.json, bl23-*-logs.txt, bl23-*-snapshot.json, bl23-*-api.json, bl23-migration-history.txt и bl23-verification.json. Сырые файлы локальны и не входят в Git. Результаты проверки оформления: bl23-documentation-check.log.

OPS-04 и AC-11.3/AC-11.4 подтверждены. Бизнес-код и JUnit-тесты не менялись; полный набор из 507 выполнений подтверждён на BL-20 и здесь повторно не запускался. Следующий этап: BL-24, CI для pull request.
