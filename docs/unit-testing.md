# Модульные проверки бизнес-правил

**Автор:** Васильев Юрий, 4П1.23

## 1. Объём BL-15

Проверена полнота модульного набора по UT-01-UT-15 из [тест-плана](test-plan.md). Добавлены TicketRequestValidatorTest и TicketCreationServiceTest для UT-04, UT-05 и UT-06. Остальные правила уже проверялись вместе с реализацией. Бизнес-код, контракт API, зависимости и схема БД на этом этапе не менялись.

TicketRequestValidatorTest проверяет обязательность заголовка, обычные и Unicode-пробелы, исходную длину заголовка 1/200/201 кодовая точка, описание 0/10000/10001 и обязательный положительный ID категории. Строки с символами вне BMP подтверждают подсчёт кодовых точек, а не UTF-16-элементов. Описание проверяется и при создании, и при редактировании. Длина заголовка проверяется до удаления пробелов по краям, как требует контракт.

TicketCreationServiceTest использует настоящие TicketService, CategoryService, TicketRequestValidator и TicketMapper. Подменяются репозитории, объект справочника и Clock. JSON с пропущенными необязательными полями и явными null разбирается настоящим десериализатором CreateTicketRequest. Проверяется объект, переданный на сохранение, и ответ сервиса. Это не HTTP-тест: Spring-контекст и сервер не запускаются.

Проверки подтверждают значения по умолчанию, все три явных приоритета, сохранение описания и срока, очистку только краёв заголовка, начальный статус NEW и единственное чтение Clock с точностью до микросекунд. Отсутствующая категория и неверный запрос не приводят к сохранению заявки. При неизвестной категории Clock не читается; при неверном заголовке не вызывается даже репозиторий категорий.

## 2. Соответствие тест-плану

Имена методов ниже относятся к классам в src/test/java и src/test/kotlin с той же структурой предметных модулей и слоёв, что у приложения.

| Сценарий | Тесты | Проверяемое правило |
|---|---|---|
| UT-01 | TicketWorkflowServiceTest.savesOneHistoryEntryWithSameClosingTime | Все четыре допустимых перехода выполняются |
| UT-02 | TicketWorkflowServiceTest.rejectsForbiddenPairWithoutMutation | Остальные 12 пар дают конфликт и не меняют состояние |
| UT-03 | TicketDetailsServiceTest.replacesOnlyEditableFieldsInOpenState, rejectsTerminalStateWithoutChangingDetails | Два открытых состояния доступны для редактирования, два терминальных запрещены |
| UT-04 | TicketRequestValidatorTest: проверки заголовка и описания; TicketCreationServiceTest.trimsOnlyTitleEdgesAndKeepsInternalWhitespace | Границы длины, Unicode, обязательность и очистка заголовка |
| UT-05 | TicketCreationServiceTest.appliesDefaultsForMissingAndNullOptionalFields, preservesExplicitPriorityDescriptionAndDueDate, usesOneMicrosecondClockValueForCreationAndOverdue | Значения по умолчанию, явные значения, NEW и управляемое время |
| UT-06 | TicketCreationServiceTest.appliesDefaultsForMissingAndNullOptionalFields, rejectsUnknownCategoryWithoutReadingClockOrSavingTicket | Сохранение выбранной категории и отказ без записи для отсутствующей |
| UT-07 | TicketOverduePolicyTest: все три метода | Java-правило просрочки из Kotlin: статусы, null, граница срока |
| UT-08 | SummaryReportServiceTest.calculatesControlDatasetWithOneSourceReadAndOneClockRead | Контрольные счётчики, один вызов источника и Clock |
| UT-09 | SummaryReportServiceTest.emptySelectionContainsEveryGroupAndNullAverage | Пустой набор даёт все нулевые группы и null среднего |
| UT-10 | SummaryReportServiceTest.cancelledAndOpenTicketsDoNotContributeToAverage | Открытые и отменённые не участвуют в среднем |
| UT-11 | SummaryReportServiceTest.roundsAverageOnlyAfterExactSummation, roundsHalfMicrosecondUp | Точное суммирование и HALF_UP после деления |
| UT-12 | SummaryReportServiceTest.invalidPeriodDoesNotReadSourceOrClock; ReportPeriodParserTest.requiresBothBoundaries | Неверный порядок и отсутствие границ отклоняются до чтения данных |
| UT-13 | TicketSearchParserTest: все три метода | Значения по умолчанию и границы пагинации |
| UT-14 | TicketDetailsServiceTest.replacesOnlyEditableFieldsInOpenState, clearsDescriptionAndDueDate | Замена разрешённых полей, очистка описания и срока |
| UT-15 | TicketWorkflowServiceTest.savesOneHistoryEntryWithSameClosingTime | Одна запись истории на переход, совпадение changedAt и closedAt при закрытии |

Один метод может подтверждать несколько связанных критериев. Строки таблицы не выдаются за отдельные методы. Атомарность транзакций, неизменность истории в PostgreSQL и SQL-фильтрация подтверждаются интеграционными тестами; подмены репозиториев не доказывают эти свойства.

## 3. Отдельный запуск

```powershell
.\gradlew.bat unitTest
```

Задача выбирает классы *Test в пакетах ru.vasilev.support.*.service и ru.vasilev.support.*.validation. В текущем составе это десять классов без Spring-контекста, HTTP-сервера и Testcontainers. Docker для них не требуется. При добавлении тестов с реальной БД в эти пакеты состав задачи необходимо пересмотреть.

Задача запускается явно и не добавляет второй прогон в обычный build. Полный test по-прежнему содержит все модульные и интеграционные проверки. У unitTest собственные результаты: build/test-results/unitTest и build/reports/tests/unitTest/index.html. Для принудительного повторного исполнения используется `.\gradlew.bat unitTest --rerun-tasks`.

unitTest задаёт часовой пояс Pacific/Honolulu, перемешивает классы и методы JUnit с seed=20261112. После обычного набора с системной зоной Windows это проверяет повторяемость по NFR-08. Бизнес-время задаётся через Clock, в новых проверках используется 2035-12-31T23:59:59.123456Z. Часы Windows не меняются. Seed позволяет повторить порядок; один такой прогон не перебирает все возможные порядки.

## 4. Результаты

Проверено 11.11.2026, ветка feature-c/unit-business-rules от ba2deb0, до коммита этапа. Окружение: Windows, Java 25, Kotlin 2.4.21; PostgreSQL 18.6 через Docker использован только полным набором.

Команда: `.\gradlew.bat spotlessApply build unitTest --no-daemon --console=plain`.

| Класс модульных тестов | Методов | Выполнений |
|---|---:|---:|
| TicketRequestValidatorTest | 6 | 21 |
| TicketCreationServiceTest | 6 | 10 |
| TicketDetailsServiceTest | 5 | 7 |
| TicketWorkflowServiceTest | 7 | 21 |
| TicketSearchParserTest | 3 | 12 |
| TicketSearchServiceTest | 2 | 6 |
| TicketOverduePolicyTest | 3 | 10 |
| SummaryReportServiceTest | 10 | 14 |
| ReportPeriodParserTest | 3 | 6 |
| QueryParameterInterceptorTest | 2 | 4 |
| Всего | 47 | 111 |

Все 111 выполнений unitTest успешны, ошибок и пропусков нет. Без технических проверок query-интерцептора бизнес-набор содержит 45 методов и 107 выполнений, что превышает минимум десяти содержательных тестов разных правил. Новые классы добавляют 12 методов и 31 выполнение.

Полный набор test: 483 выполнения, 0 ошибок, 0 пропусков. Компиляция и проверки оформления успешны. Полный и повторный наборы учитываются отдельно; повтор не увеличивает количество уникальных методов. XML/HTML хранятся в build и не включаются в Git.

UT-01-UT-15 выполнены в описанном объёме. Численный отчёт покрытия Java/Kotlin остаётся задачей BL-19; число успешных тестов не заменяет измерение покрытия. Следующий этап BL-16 проверяет полноту интеграционных сценариев.
