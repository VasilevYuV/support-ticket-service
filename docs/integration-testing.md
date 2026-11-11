# Интеграционные проверки PostgreSQL и API

**Автор:** Васильев Юрий, 4П1.23

## 1. Объём BL-16

Сопоставлены IT-01-IT-15 из [тест-плана](test-plan.md) с исполняемыми проверками. Сценарии используют PostgreSQL 18.6 через Testcontainers и настоящее приложение Spring Boot. H2 и подмена репозиториев для интеграционных проверок не применяются. Итоговые E2E-сценарии остаются отдельным этапом BL-17.

Добавлены проверка повторного запуска приложения на одной базе и автоматическая сверка HTTP-ответов с OpenAPI. Уже существующие тесты миграций, создания, редактирования, поиска, истории, статистики и rollback повторно выполнены с проверкой контракта. Бизнес-код, схема БД и API на этом этапе не изменены.

## 2. Проверка OpenAPI

Общий ApiTestSupport передаёт каждый HTTP-ответ в common.contract.OpenApiContract. Проверка сопоставляет путь и метод с docs/openapi.yaml, находит ответ для фактического HTTP-статуса, разрешает ссылки на общие ответы и проверяет JSON по соответствующей схеме. Для неподдерживаемого метода допускается только 405 с общей схемой ошибки.

Проверяются Content-Type, объявленные поля и их типы, обязательность, enum, ограничения чисел и строк, nullable-поля, отсутствие лишних свойств и форматы UUID/date-time. У ответа создания проверяется объявленный заголовок Location. Значение Location, совпадение status/path внутри ошибки и бизнес-содержание ответа дополнительно проверяются существующими тестами. Проверка схемы не заменяет эти утверждения и не является полной проверкой всех возможных HTTP-запросов.

Спецификация копируется задачей processTestResources в contract/openapi.yaml. Исходный файл не дублируется вручную, его изменение учитывается Gradle при следующем запуске тестов. Документ не загружается из сети во время проверок.

Используется JSON Schema Validator 3.0.6 с Draft 2020-12 и явно включёнными проверками format. Версия основана на Jackson 3.1.4 согласно [POM библиотеки](https://github.com/networknt/json-schema-validator/blob/3.0.6/pom.xml); в проекте разрешён Jackson 3.1.7 из BOM Spring Boot. Jackson одинаков в приложении и тестах. Валидатор, Jackson YAML и их новые транзитивные зависимости добавлены только в тестовые конфигурации и закреплены в gradle.lockfile. Производственные зависимости не изменились.

OpenApiContractTest содержит шесть технических методов, 18 выполнений. Они подтверждают принятие корректного ответа с null-датами и отклонение неверного UUID, даты, enum, типа, null обязательного значения, отсутствующего и лишнего поля. Отдельно проверяются отсутствие Location, неверный Content-Type, необъявленные статус и маршрут, успешный ответ неподдерживаемого метода и общие схемы ошибок. Эти проверки не засчитываются как интеграционные тесты.

| Операция | Исполняемые HTTP-проверки успешного ответа |
|---|---|
| GET /api/categories | TicketApiTest.listsCategoriesInIdOrder |
| POST /api/tickets | TicketApiTest.createsAndReadsFullTicket, appliesDefaultsForOmittedAndNullFields |
| GET /api/tickets/{id} | TicketApiTest.createsAndReadsFullTicket, readsTerminalTicketWithoutChangingItOrItsHistory |
| PUT /api/tickets/{id}/details | TicketDetailsApiTest.replacesAndClearsDetailsWithoutChangingOtherData |
| PATCH /api/tickets/{id}/status | TicketWorkflowApiTest.persistsAllowedTransitionAndOrderedHistory |
| GET /api/tickets/{id}/history | TicketWorkflowApiTest.persistsAllowedTransitionAndOrderedHistory, returnsEmptyHistoryAndKeepsTicketsSeparate |
| GET /api/tickets | TicketSearchApiTest.filtersByEveryConditionAndTheirIntersection, returnsEmptyPageForEmptyDatabase |
| GET /api/reports/summary | SummaryReportApiTest.calculatesControlDatasetThroughJavaAndKotlinWithOneQuery, returnsExplicitNullAndAllZeroGroupsForEmptySelection |

Ошибочные сценарии через тот же клиент проверяют ответы 400, 404, 409, 405, 406, 415 и искусственный 500. Валидация схем включена также при конкурирующих запросах и для Kotlin-класса SummaryReportBoundaryApiTest.

## 3. Соответствие тест-плану

| Сценарий | Исполняемая проверка | Свидетельство |
|---|---|---|
| IT-01 | DatabaseMigrationTest и DatabaseRestartTest.restartsApplicationOnSameDatabaseWithoutChangingDataOrMigrations | Пустая БД, две миграции, четыре категории; полное закрытие первого Spring-контекста, второй запуск на том же PostgreSQL, неизменные данные и история Flyway |
| IT-02 | TicketApiTest.listsCategoriesInIdOrder, createsAndReadsFullTicket, appliesDefaultsForOmittedAndNullFields | HTTP 201/200, Location, UUID, значения по умолчанию и независимое чтение PostgreSQL |
| IT-03 | TicketApiTest.rejectsInvalidCreationWithoutSaving; RequestValidationApiTest | Неверные поля и JSON дают 400 без записей |
| IT-04 | TicketApiTest.distinguishesMissingTicketFromMalformedId; TicketDetailsApiTest.distinguishesInvalidAndMissingUuid; TicketWorkflowApiTest.distinguishesInvalidAndMissingIds | Все операции с UUID различают 400 и 404 |
| IT-05 | TicketDetailsApiTest.replacesAndClearsDetailsWithoutChangingOtherData, rejectsInvalidBodyBeforeLookupAndWithoutPartialUpdate | Полная замена, повтор PUT, очистка и отказ без частичных изменений |
| IT-06 | TicketWorkflowApiTest.persistsAllowedTransitionAndOrderedHistory | Четыре перехода, время закрытия и история согласованы |
| IT-07 | TicketWorkflowApiTest.rejectsEveryForbiddenPairWithoutChanges; TicketDetailsApiTest.rejectsTerminalTicketWithoutChangingSnapshot | 12 запрещённых переходов и два терминальных состояния без изменения снимков |
| IT-08 | TicketWorkflowApiTest.rollsBackStatusAndClosingTimeWhenHistoryInsertFails | Ограничение PostgreSQL вызывает сбой записи истории после изменения заявки; новая транзакция читает прежние статус, closedAt и историю |
| IT-09 | TicketSearchApiTest.filtersByEveryConditionAndTheirIntersection | Фильтры, AND, обе стороны просрочки и неизвестная категория |
| IT-10 | TicketSearchApiTest.pagesInStableUnsignedUuidOrderWithoutGapsOrDuplicates, countsOnlyMatchingRowsOnEveryPageIncludingPagePastEnd и проверки неверных параметров | Устойчивая сортировка, границы страниц, количество и ошибки |
| IT-11 | SummaryReportApiTest и SummaryReportBoundaryApiTest | R1-R8, пустой отчёт, null среднего, границы периода и округление |
| IT-12 | RequestValidationApiTest и SummaryReportBoundaryApiTest | Смещения, микросекунды, неверные даты, граница срока и диапазон лет |
| IT-13 | TicketWorkflowConcurrencyTest; TicketDetailsApiTest.checksTerminalStateAfterWaitingForRowLock | Две независимые транзакции, конкурирующие терминальные переходы и оба порядка редактирования/закрытия |
| IT-14 | RequestValidationApiTest и ошибочные сценарии остальных контроллеров | Единый ApiError, согласованные статус/путь, отсутствие SQL и стека в ответе |
| IT-15 | DatabaseMigrationTest и OpenApiContract в ApiTestSupport | Ограничения таблиц и схемы ответов всех восьми HTTP-операций |

DatabaseRestartTest сохраняет заявку IN_PROGRESS и запись истории, затем закрывает приложение и запускает его заново на той же базе. Сравниваются полные упорядоченные снимки categories, tickets, ticket_status_history и flyway_schema_history. Контейнер закрывается после обоих запусков. Тест проверяет перезапуск приложения; перезапуск контейнера с постоянным томом и восстановление резервной копии остаются эксплуатационными задачами.

В IT-08 временное ограничение снимается в finally. После rollback тест повторяет допустимый переход и подтверждает успешное сохранение. Искусственный сбой проверяет транзакционность и не добавляется в журнал реальных дефектов.

## 4. Запуск и результаты

Для отдельного интеграционного набора нужны Java 25 и работающий Docker:

```powershell
.\gradlew.bat integrationTest
```

Задача выбирает классы *Test в пакетах controller и database.migration, включая Kotlin. Она не запускает два теста каркаса и модульные классы. Полный test содержит все проверки. integrationTest запускается явно, не добавляется вторым прогоном в build и имеет отдельные XML/HTML-результаты.

Артефакты: build/test-results/integrationTest и build/reports/tests/integrationTest/index.html. Для принудительного повторного исполнения добавляется --rerun-tasks. Контейнеры временные, данные пользовательской базы не используются.

Проверено 11.11.2026, ветка feature-c/integration-contract от 85dc498, до коммита этапа. Окружение: Windows, Java 25, Kotlin 2.4.21, Docker, PostgreSQL 18.6.

Команда окончательной проверки: `.\gradlew.bat spotlessApply build integrationTest unitTest --no-daemon --console=plain`. Она выполнена без --write-locks, с уже сохранёнными зависимостями.

| Интеграционный класс | Методов | Выполнений |
|---|---:|---:|
| DatabaseMigrationTest | 12 | 59 |
| DatabaseRestartTest | 1 | 1 |
| TicketApiTest | 8 | 27 |
| RequestValidationApiTest | 14 | 101 |
| TicketDetailsApiTest | 9 | 40 |
| TicketWorkflowApiTest | 8 | 40 |
| TicketWorkflowConcurrencyTest | 2 | 4 |
| TicketSearchApiTest | 9 | 60 |
| SummaryReportApiTest | 11 | 25 |
| SummaryReportBoundaryApiTest | 5 | 14 |
| Всего | 79 | 371 |

Все 371 выполнение integrationTest успешны, ошибок и пропусков нет. Десять классов содержат 79 методов, проверяющих разные правила API и БД; минимум шести содержательных интеграционных тестов выполнен без подмены его числом параметров одного метода.

Полный test: 502 выполнения; unitTest: 129 выполнений в 53 методах и 11 классах. Ошибок и пропусков нет. Модульный набор теперь включает 18 технических проверок OpenAPI. Бизнес-набор BL-15 по-прежнему содержит 45 методов и 107 выполнений, технические проверки контракта и query-интерцептора не увеличивают это число.

Сборка, совместная компиляция и оформление успешны. Новых дефектов приложения на BL-16 не выявлено. IT-01-IT-15 выполнены в описанном объёме. E2E, измерение покрытия и эксплуатационные проверки остаются в плане. Отчёты разных задач учитываются отдельно и не включаются в Git.
