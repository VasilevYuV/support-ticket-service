# Сервис обработки заявок технической поддержки

**Автор:** Васильев Юрий, 4П1.23  
**Учебная практика:** 4-й курс, 72 академических часа

## Назначение

Проект предназначен для регистрации обращений в техническую поддержку, управления их состоянием и получения статистики. Результат работы - серверное приложение с REST API.

Сервис должен сохранять заявки и историю изменения статусов, поддерживать поиск с фильтрацией и пагинацией, а также рассчитывать статистику за выбранный период.

## Текущее состояние

Подготовлены требования, 12 пользовательских историй, архитектура с четырьмя диаграммами, контракт восьми операций API и тест-план. Создан каркас Spring Boot с Java-точкой входа и Kotlin-конфигурацией времени, Gradle Wrapper, проверкой форматирования и двумя тестами запуска.

Подключены PostgreSQL и Flyway: при запуске создаются таблицы категорий, заявок и истории, ограничения и индексы, затем четыре категории. Реализованы чтение категорий, создание, чтение и редактирование заявки, смена статуса и чтение истории. Статус и история сохраняются в одной транзакции; при завершении или отмене записывается время закрытия. Изменение описания, приоритета и срока разрешено только в NEW и IN_PROGRESS; строка блокируется до завершения транзакции. Добавлены строгая проверка JSON, дат, UUID и query-параметров, единый формат ошибок HTTP. Доступны технические endpoints `/actuator/health` и его групп; они учитывают подключение к базе. Реализован поиск с фильтрами, пагинацией и сортировкой createdAt DESC, id DESC. На Kotlin реализован расчёт статистики по одной выборке через Java-интерфейс источника. Отчёт подключён к PostgreSQL и доступен через HTTP. Реализованы все восемь бизнес-маршрутов; итоговые проверки, эксплуатация и CI/CD ещё впереди.

## Архитектура

Предусмотрен один развёртываемый сервис на Spring Boot и база данных PostgreSQL. Структура кода следует **Modular + Layered**: предметные модули `ticket`, `category`, `report` содержат собственные пакеты `controller`, `service`, `repository`, `dto`, `entity` и другие необходимые слои. Например, контроллер заявок находится в `ticket.controller`, сервис в `ticket.service`, репозиторий в `ticket.repository`.

Назначение слоёв:

- Контроллеры принимают HTTP-запросы и возвращают ответы API.
- Сервисы выполняют бизнес-операции и определяют границы транзакций.
- Репозитории обеспечивают доступ к данным.
- PostgreSQL хранит заявки, категории и историю изменения статусов.

Основная функциональность реализуется на Java. Блок статистики реализуется на Kotlin/JVM в составе того же приложения и взаимодействует с Java-компонентами.

Корневой пакет - `ru.vasilev.support`. Доступ к данным выполняется через Spring Data JPA. Изменение статуса и запись истории входят в одну транзакцию; редактирование и переходы блокируют изменяемую строку до завершения операции. Kotlin получает данные через Java-интерфейс и рассчитывает все показатели отчёта по одной выборке.

Заявка проходит `NEW` → `IN_PROGRESS` → `RESOLVED`; из первых двух состояний разрешена отмена в `CANCELLED`. Терминальные заявки доступны только для чтения. Срок может отсутствовать; просрочка определяется только для открытой заявки с истёкшим сроком.

Подробности и диаграммы приведены в документах ниже. HTTP-операции и расчёт отчёта проверены с PostgreSQL; состояние остальных этапов указано в backlog.

## Технологии

| Назначение | Выбранные технологии |
|---|---|
| Серверное приложение | Java, Kotlin/JVM, Spring Boot |
| Доступ к данным, база и миграции | Spring Data JPA, PostgreSQL, Flyway |
| Сборка | Gradle с Wrapper |
| Автоматизированные тесты | JUnit, Mockito, Testcontainers |
| Покрытие кода | JaCoCo |
| Документирование API | OpenAPI, Swagger UI |
| Контейнеризация | Docker, Docker Compose |
| CI/CD | GitHub Actions |

Для текущей сборки закреплены Java 25, Gradle 9.7.0, Spring Boot 4.1.2 и Kotlin 2.4.21. Форматирование проверяет Spotless 8.11.0. Версии находятся в `gradle/libs.versions.toml`, конфигурации Wrapper и `gradle.lockfile`. Таблица выше включает также технологии последующих этапов; подробности приведены в [описании инструментов](docs/toolchain.md).

## Сборка и запуск

Нужны JDK 25 и работающий Docker Engine с Linux-контейнерами. Тесты сами создают временную базу PostgreSQL через Testcontainers. Отдельная установка Gradle не требуется. В PowerShell из корня проекта:

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat clean build
```

Если `JAVA_HOME` не настроен, указать путь к установленному JDK 25 для текущего сеанса, например:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

Для обычного запуска приложения нужна отдельная база. Первый запуск локального PostgreSQL в PowerShell:

```powershell
docker run --name support-postgres -d `
  -e POSTGRES_DB=support_service `
  -e POSTGRES_USER=support_service `
  -e POSTGRES_PASSWORD=support_local `
  -p 127.0.0.1:55432:5432 `
  --mount type=volume,source=support-postgres-data,target=/var/lib/postgresql `
  postgres:18.6
docker exec support-postgres pg_isready -U support_service -d support_service
```

Дождаться `accepting connections`; при необходимости повторить `pg_isready`. Если контейнер уже создан, использовать `docker start support-postgres`. Пароль в примере предназначен только для локальной учебной базы. Настройки для текущего окна PowerShell:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:55432/support_service'
$env:DB_USERNAME = 'support_service'
$env:DB_PASSWORD = 'support_local'
```

Запуск через Gradle:

```powershell
.\gradlew.bat bootRun
```

Или запуск собранного исполняемого JAR:

```powershell
java -jar .\build\libs\support-service-0.1.0-SNAPSHOT.jar
```

По умолчанию используется порт 8080. Его можно изменить переменной `SERVER_PORT` до запуска. Остановка приложения в терминале выполняется сочетанием `Ctrl+C`.

Проверка из другого окна PowerShell:

```powershell
Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health'
Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health/readiness'
```

Ожидается HTTP `200` и JSON со значением `status: UP`; общий health также содержит группы `liveness` и `readiness`. Миграции выполняются автоматически до готовности приложения. Без доступной базы обычный запуск не завершается успешно. В Linux/macOS используются `sh ./gradlew clean build` и `sh ./gradlew bootRun` с теми же переменными окружения. Подробности подключения, проверки таблиц и остановки базы приведены в [инструкции PostgreSQL](docs/database.md).

## Swagger UI и демонстрация

После запуска открыть `http://localhost:8080/swagger-ui.html`. Интерфейс использует [общий контракт](docs/openapi.yaml), опубликованный по `/openapi.yaml`; при другом порте запросы отправляются на текущий сервер.

Для демонстрации подготовлены [Postman-коллекция](docs/postman/support-service.postman_collection.json) и [локальное окружение](docs/postman/local.postman_environment.json). Коллекция создаёт четыре учебные заявки во всех статусах, выполняет 21 запрос и 42 проверки. Нужна отдельная пустая база; повторный запуск останавливается до создания новых записей. Подготовка базы, запуск через Postman/Newman и ожидаемые результаты: [инструкция BL-20](docs/api-demo.md).

На BL-20 полный набор содержит 507 успешных выполнений, включая два технических теста публикации контракта и Swagger UI. Коллекция также проверена на работающем JAR с PostgreSQL.

## Работа с API

После запуска приложения доступны:

| Метод | Адрес | Результат |
|---|---|---|
| GET | `/api/categories` | `200`, категории в порядке ID |
| POST | `/api/tickets` | `201`, новая заявка и `Location` |
| GET | `/api/tickets/{id}` | `200`, сохранённая заявка; `404`, если UUID отсутствует |
| PUT | `/api/tickets/{id}/details` | `200`, замена описания, приоритета и срока; `409` для терминального состояния |
| PATCH | `/api/tickets/{id}/status` | `200`, допустимый переход; `409` при запрете |
| GET | `/api/tickets/{id}/history` | `200`, история по возрастанию ID; у новой заявки пустой массив |
| GET | `/api/tickets` | `200`, страница заявок с фильтрами и общим количеством |
| GET | `/api/reports/summary` | `200`, Kotlin-статистика за период создания `[from, to)` |

Пример в PowerShell:

```powershell
$apiUrl = 'http://localhost:8080'
$categories = Invoke-RestMethod -Uri "$apiUrl/api/categories"
$categories

$requestBody = @{
    title = 'Не работает принтер'
    categoryId = $categories[0].id
    description = 'Принтер не отвечает.'
    priority = 'HIGH'
} | ConvertTo-Json

$createdTicket = Invoke-RestMethod -Method Post -Uri "$apiUrl/api/tickets" `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([Text.Encoding]::UTF8.GetBytes($requestBody))
$createdTicket
Invoke-RestMethod -Uri "$apiUrl/api/tickets/$($createdTicket.id)"
```

Созданная заявка получает `NEW`, время создания в UTC и пустую историю. Достаточно передать `title` и `categoryId`: описание станет пустой строкой, приоритет `MEDIUM`, срок `null`. Ошибки входных данных дают `400` без записи в базу. Правила типов, дат, UUID и единые ответы ошибок описаны в [валидации запросов](docs/request-validation.md).

Для редактирования передаются все три поля. Пустая строка очищает описание, `null` снимает срок:

```powershell
$detailsBody = @{
    description = 'Проверены питание и кабель.'
    priority = 'HIGH'
    dueAt = $null
} | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$apiUrl/api/tickets/$($createdTicket.id)/details" `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([Text.Encoding]::UTF8.GetBytes($detailsBody))
```

Пропуск любого поля даёт `400`. Заголовок, категория, статус и время создания не редактируются. Подробности и результаты приведены в [описании редактирования](docs/ticket-details.md).

Пример начала работы, завершения и чтения истории:

```powershell
Invoke-RestMethod -Method Patch -Uri "$apiUrl/api/tickets/$($createdTicket.id)/status" `
    -ContentType 'application/json' -Body '{"status":"IN_PROGRESS"}'
Invoke-RestMethod -Method Patch -Uri "$apiUrl/api/tickets/$($createdTicket.id)/status" `
    -ContentType 'application/json' -Body '{"status":"RESOLVED"}'
Invoke-RestMethod -Uri "$apiUrl/api/tickets/$($createdTicket.id)/history"
```

Для отмены открытой заявки передаётся `CANCELLED`. Повтор текущего статуса, прямое завершение NEW и изменение терминального статуса дают `409`. Время `changedAt` терминальной записи совпадает с `closedAt`. Полный контракт и результаты приведены в [описании переходов и истории](docs/ticket-status-history.md).

Поиск открытых просроченных заявок с высоким приоритетом:

```powershell
$page = Invoke-RestMethod -Uri "$apiUrl/api/tickets?priority=HIGH&overdue=true&page=0&size=20"
$page.content
$page.totalElements
$page.totalPages
```

Дополнительные фильтры: `status` и `categoryId`. Все условия соединяются через AND. Без параметров возвращается первая страница размером 20; допустимый размер от 1 до 100. `overdue=false` включает также бессрочные и терминальные заявки. Сортировка фиксирована, неизвестные, пустые и повторные параметры дают `400`. Подробнее в [описании поиска](docs/ticket-search.md).

Расчёт отчёта находится в `report.service.SummaryReportService`, результат представлен Kotlin `data class` SummaryReport. Он формирует количество заявок, распределения по всем статусам и приоритетам, открытую просрочку и среднее время решения с точностью до шести знаков. Для отсутствующих решённых заявок среднее равно `null`.

Java-контроллер вызывает Kotlin-сервис, который получает одну выборку PostgreSQL через ReportDataSource. Границы обязательны; from включается, to исключается:

```powershell
$from = [Uri]::EscapeDataString('2026-11-01T00:00:00Z')
$to = [Uri]::EscapeDataString('2026-12-01T00:00:00Z')
Invoke-RestMethod -Uri "$apiUrl/api/reports/summary?from=$from&to=$to"
```

EscapeDataString сохраняет знак `+` при использовании смещения вместо Z. Пустая выборка возвращает `200`, нулевые счётчики и явный null среднего. Подробности формул находятся в [описании расчёта](docs/report-calculation.md), правила запроса и фактические результаты в [описании HTTP-отчёта](docs/report-api.md).

## Проверки

```powershell
.\gradlew.bat test
.\gradlew.bat spotlessCheck checkTextConventions
```

`build` включает тесты, проверки оформления и отчёт покрытия Java/Kotlin. Для автоматического форматирования Java, Kotlin и Gradle Kotlin DSL:

```powershell
.\gradlew.bat spotlessApply
```

Отчёт тестов создаётся в `build/reports/tests/test/index.html`, XML-результаты в `build/test-results/test`. Эти файлы и кеши не включаются в Git. Два теста проверяют запуск приложения и взаимодействие языков; `DatabaseMigrationTest` проверяет схему на реальном PostgreSQL, повторное применение миграций и ограничения целостности. `TicketApiTest` отправляет реальные HTTP-запросы, затем независимо читает PostgreSQL для проверки сохранения и отсутствия побочных изменений. `RequestValidationApiTest` проверяет строгий входной контракт и ошибки HTTP, включая сбой записи в PostgreSQL. `QueryParameterInterceptorTest` отдельно проверяет разрешённые имена, пустые и повторные query-параметры. `TicketDetailsServiceTest` проверяет правила редактирования, `TicketDetailsApiTest` проверяет HTTP, неизменность данных, блокировку и откат. `TicketWorkflowServiceTest`, `TicketWorkflowApiTest` и `TicketWorkflowConcurrencyTest` проверяют матрицу переходов, историю, атомарность и конкурирующие запросы. `TicketSearchApiTest` проверяет фильтры и страницы на PostgreSQL, модульные тесты проверяют параметры, общее время и число страниц. Kotlin-тесты `TicketOverduePolicyTest` вызывают Java-правило просрочки. `SummaryReportServiceTest` проверяет расчёт Kotlin-отчёта, nullable-поля, точное округление и один вызов Java-источника. `SummaryReportApiTest` проверяет полный путь Java/Kotlin/PostgreSQL и один SQL-запрос на отчёт. SummaryReportBoundaryApiTest написан на Kotlin и проверяет точность среднего через PostgreSQL и JSON, пересчёт после изменения заявки и календарные границы. На BL-17 успешно выполнены 505 проверок полного набора, 3 выполнения двух E2E-сценариев и отдельный интеграционный набор из 371 проверки. E2E подтверждают полный жизненный цикл и неизменность данных после запрета операций для RESOLVED и CANCELLED. Все HTTP-ответы через ApiTestSupport сверяются со схемами OpenAPI. DatabaseRestartTest подтверждает повторный запуск приложения на одной базе с сохранением данных и истории миграций. TicketRequestValidatorTest и TicketCreationServiceTest дополняют проверки границ текста, значений по умолчанию и выбора категории. Исправлен сдвиг старых дат при передаче Instant в PostgreSQL (DEF-02).

Покрытие всего основного кода по полному набору тестов:

```powershell
.\gradlew.bat jacocoTestReport
```

Задача зависит от `test`, требует Docker и включена в `check`/`build`. HTML: `build/reports/jacoco/test/html/index.html`, XML: `build/reports/jacoco/test/jacocoTestReport.xml`. Для нового прогона без использования актуальных результатов Gradle: `jacocoTestReport --rerun-tasks`. После запуска `test --tests ...` полный отчёт также следует пересобрать этой командой. На BL-19 полный набор с JaCoCo содержит 505 успешных выполнений без ошибок и пропусков. Покрытие строк Java 97,17%, Kotlin 100%. Метрики по языкам, бизнес-правилам и непокрытые участки приведены в [отчёте BL-19](docs/test-coverage.md).

Модульные проверки сервисов и валидации без Docker и запуска Spring-контекста:

```powershell
.\gradlew.bat unitTest
```

Отчёт: `build/reports/tests/unitTest/index.html`, XML: `build/test-results/unitTest`. Задача запускает 53 метода в одиннадцати классах, включая технические проверки OpenAPI, использует управляемое время, Pacific/Honolulu и перемешанный порядок JUnit. Соответствие всем UT-01-UT-15 приведено в [описании модульных проверок](docs/unit-testing.md). Полный `test` по-прежнему требует Docker.

Отдельный интеграционный набор с PostgreSQL через Testcontainers:

```powershell
.\gradlew.bat integrationTest
```

Нужен работающий Docker. Отчёт: `build/reports/tests/integrationTest/index.html`, XML: `build/test-results/integrationTest`. Проверяются десять классов, все восемь операций API, ограничения БД, повторный запуск приложения и откат транзакций. Состав и соответствие IT-01-IT-15: [интеграционные проверки](docs/integration-testing.md).

Два сквозных сценария через HTTP и PostgreSQL:

```powershell
.\gradlew.bat e2eTest
```

Нужен Docker. Отчёт: `build/reports/tests/e2eTest/index.html`, XML: `build/test-results/e2eTest`. Два метода дают три выполнения: жизненный цикл и запрет операций для двух терминальных статусов. E2E входят в полный test, но не входят в integrationTest. Подробности: [сквозные сценарии](docs/end-to-end-testing.md).

Для повторного подтверждения DEF-01 и DEF-02 с исправлениями и без них:

```powershell
.\scripts\verify-regressions.ps1
```

Нужны PowerShell 7, JDK 25, Git и Docker. Скрипт проверяет HEAD в отдельной копии внутри build, не изменяет рабочие исходники и сохраняет XML, журналы и summary.json. Ожидаемые падения без исправлений проверяются отдельно от успешных запусков с исправлениями. На BL-18 оба дефекта воспроизведены, после восстановления исправлений все 10 выбранных выполнений успешны. Подробнее: [проверка регрессий](docs/defect-regression.md).

Дополнительный прогон отчёта с часовым поясом Pacific/Kiritimati и перемешанным порядком классов и методов:

```powershell
.\gradlew.bat reportRegressionTest
```

Отдельные результаты: `build/reports/tests/reportRegressionTest/index.html` и `build/test-results/reportRegressionTest`. Для гарантированного повторного исполнения добавляется `--rerun-tasks`. Подробнее в [проверках границ отчёта](docs/report-boundaries.md).

## Документация

- [Постановка задачи и требования](docs/requirements.md).
- [Пользовательские истории и критерии приёмки](docs/user-stories.md).
- [Backlog и плановые трудозатраты](docs/backlog.md).
- [Архитектура и взаимодействие Java и Kotlin](docs/architecture.md).
- [Модель данных и ER-диаграмма](docs/data-model.md).
- [Процесс обработки заявки и матрица переходов](docs/ticket-workflow.md).
- [Контракт REST API и примеры](docs/api-contract.md).
- [Машиночитаемая спецификация OpenAPI](docs/openapi.yaml).
- [Тест-план и связь с критериями приёмки](docs/test-plan.md).
- [Версии инструментов и устройство сборки](docs/toolchain.md).
- [Подключение PostgreSQL, миграции и проверки схемы](docs/database.md).
- [Реализованное API категорий и заявок, результаты BL-07](docs/ticket-api.md).
- [Проверка запросов и обработка ошибок, результаты BL-08](docs/request-validation.md).
- [Редактирование заявки и транзакционные проверки, BL-09](docs/ticket-details.md).
- [Переходы статуса, история и конкурирующие запросы, BL-10](docs/ticket-status-history.md).
- [Поиск, фильтры, пагинация и результаты BL-11](docs/ticket-search.md).
- [Kotlin-модели и расчёт отчёта, результаты BL-12](docs/report-calculation.md).
- [HTTP-отчёт, PostgreSQL-источник и результаты BL-13](docs/report-api.md).
- [Границы статистики, Kotlin-интеграция и результаты BL-14](docs/report-boundaries.md).
- [Модульные правила, отдельный запуск и результаты BL-15](docs/unit-testing.md).
- [Интеграционные проверки, OpenAPI и результаты BL-16](docs/integration-testing.md).
- [Сквозные сценарии и результаты BL-17](docs/end-to-end-testing.md).
- [Журнал дефектов и регрессионные проверки](docs/defects.md).
- [Воспроизведение исправленных дефектов и результаты BL-18](docs/defect-regression.md).
- [Покрытие Java/Kotlin и сводка тестирования BL-19](docs/test-coverage.md).
- [Swagger UI, коллекция и демонстрационные данные BL-20](docs/api-demo.md).

Следующий этап - Dockerfile, Compose и проверка запуска приложения в контейнерах (BL-21). Диаграммы хранятся в блоках `mermaid` внутри Markdown и отображаются в просмотрщике с поддержкой Mermaid. В backlog отдельно отмечены подготовленные материалы и этапы, которые ещё предстоит выполнить.

Тест-план содержит 15 модульных, 15 интеграционных и 2 сквозных сценария, 10 эксплуатационных сценариев и 4 проверки материалов. Они связаны со всеми 55 критериями приёмки и 36 требованиями. Выполненные и частично выполненные проверки отмечены в [тест-плане](docs/test-plan.md); количество запусков на параметрах не подменяет количество бизнес-сценариев.

## Правила разработки

- Код следует принципам DRY и KISS: повторяющаяся логика выносится в общие методы, а структура остаётся простой и понятной.
- Структура кода следует Modular + Layered: имя предметного модуля, внутри него разделение на слои.
- Комментарии пишутся на русском языке там, где необходимо пояснить назначение метода или неочевидную логику.
- В коде, комментариях и документации используется обычный дефис `-`; длинное и среднее тире не используются.
- Документация и тесты дополняются вместе с функциональностью.
- Работа ведётся небольшими тематическими коммитами; изменения после начального оформления репозитория проходят через ветки и pull request.
- Реальные пароли, токены, локальные файлы окружения и резервные копии не включаются в репозиторий.
- Результаты проверок фиксируются только после их фактического выполнения.

## Дальнейшее наполнение README

По мере реализации будут добавлены запуск в Docker Compose, примеры остальных операций API, инструкции по резервному копированию, восстановлению, диагностике и откату.
