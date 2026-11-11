# Границы отчёта и проверка Kotlin-интеграции

**Автор:** Васильев Юрий, 4П1.23

## 1. Объём BL-14

Этап дополняет [расчёт отчёта](report-calculation.md) и [проверки HTTP-маршрута](report-api.md). Проверяются KR-01-KR-06, границы UT-07-UT-11, IT-11 и независимость проверок отчёта от времени и порядка запуска по NFR-08.

SummaryReportBoundaryApiTest написан на Kotlin и наследует Java-класс ApiTestSupport. Он запускает HTTP-приложение и PostgreSQL 18.6 через Testcontainers. Контроллер, источник данных, Kotlin-сервис и операции изменения заявки настоящие; подменяется только Clock.

| Метод | Выполнений | Проверяемое поведение |
|---|---:|---|
| preservesMicrosecondsFromPostgresThroughJson | 5 | Нулевая длительность, округление вниз, вверх и половины микросекунды, переход через границу секунды; точность после чтения PostgreSQL и сериализации JSON |
| preservesLargeDurationWithoutOverflowOrDecimalLoss | 1 | От 0001-01-01 до 9999-12-31: одна заявка и среднее 315537897599.999999 секунды без переполнения и потери дробной части |
| recalculatesOverdueAtEachClockBoundaryAndAfterCancellation | 2 | До срока, на сроке с отброшенными наносекундами, на микросекунду позже и после отмены; даты в 2000 и 2035 годах |
| usesCurrentPriorityAndStatusWhenTicketClosesOutsideCreationPeriod | 1 | Отчёт отражает новый приоритет и текущий статус; закрытие за пределами периода создания учитывается в среднем |
| preservesCalendarDateInDatabaseAndReport | 5 | Создание через HTTP, независимое чтение PostgreSQL, чтение через API и попадание в отчёт для годов 0001, 1500, 1582 и 9999 |

Для проверки среднего SQL-данные содержат согласованную историю переходов. Для проверки пересчёта и календарных дат заявки создаются и изменяются через API. Между тестами таблицы заявок и истории очищаются. Все моменты времени задаются явно.

Тестовый HTTP-клиент разбирает дробные JSON-числа как BigDecimal через JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS. Это исключает потерю точности внутри проверок при промежуточном преобразовании в double; формат ответа API не изменяется.

## 2. Kotlin и Java

| Kotlin-класс | Методов | Выполнений | Назначение |
|---|---:|---:|---|
| TicketOverduePolicyTest | 3 | 10 | Прямой вызов Java-компонента TicketOverduePolicy: статусы, null срока и граница времени |
| SummaryReportServiceTest | 10 | 14 | Расчёт статистики, nullable-поля, коллекции и вызов Java-интерфейса ReportDataSource |
| SummaryReportBoundaryApiTest | 5 | 14 | Полный путь HTTP, Java-контроллер, Kotlin-сервис и PostgreSQL |
| ApplicationWiringTest | 1 | 1 | Проверка каркаса и загрузки Kotlin-компонента из Java-приложения |

Таким образом, бизнес-проверки содержат 18 Kotlin-методов и 38 выполнений. Тест каркаса учитывается отдельно. Требование KR-06 о трёх тестах и проверке Java-компонента выполнено без опоры на один только запуск контекста.

Два решения для RV-02: Java-модель с JSpecify передаёт nullable-поля в Kotlin, где отсутствие среднего выражено BigDecimal?; Kotlin data class и операции над коллекциями формируют результат одной выборки. Подробное обоснование приведено в [разделе решений Kotlin](report-calculation.md#3-решения-связанные-с-kotlin). Эти материалы потребуются при подготовке итогового отчёта BL-28.

## 3. Найденный дефект

До исправления проверка большой длительности получала totalCreated=0 вместо 1, хотя прямой JDBC-запрос с OffsetDateTime выбирал запись. Дополнительная проверка создания показала сдвиг календарных дат до октября 1582 года. Например, 0001-01-01 сохранялась как 0001-01-03.

В application.yaml задан `hibernate.type.preferred_instant_jdbc_type: TIMESTAMP_WITH_TIMEZONE`. Для Instant Hibernate использует передачу через OffsetDateTime вместо Timestamp с Calendar. Тип столбцов timestamptz(6), схема и миграции остаются прежними. Подробные шаги и результаты до и после исправления: [DEF-02](defects.md#def-02-сдвиг-календарных-дат-при-передаче-instant-в-postgresql).

## 4. Повторяемость и результаты

```powershell
.\gradlew.bat spotlessApply build reportRegressionTest --no-daemon --console=plain
```

Задача reportRegressionTest повторяет SummaryReportServiceTest, SummaryReportApiTest, SummaryReportBoundaryApiTest, ReportPeriodParserTest и TicketOverduePolicyTest в отдельном JVM-процессе. Установлены user.timezone=Pacific/Kiritimati, случайный порядок классов и методов JUnit, seed=20261111. Фиксированный seed позволяет воспроизвести порядок. Для принудительного повторного выполнения без использования результата Gradle:

```powershell
.\gradlew.bat reportRegressionTest --rerun-tasks
```

Часы и часовой пояс Windows не меняются. Логи обычного набора содержат смещение +03:00, повторного +14:00; локальная дата повторного процесса уже 12.11.2026. Бизнес-время берётся из Clock. Проверки с датами 2000 и 2035 годов дополнительно подтверждают отсутствие зависимости расчёта от текущего календарного года. Повторный прогон охватывает модуль отчёта и общее правило просрочки; проверка полноты остальных модульных правил остаётся BL-15.

Проверено 11.11.2026 в ветке feature-c/report-boundaries на основе коммита b1e954e, до коммита этапа. Окружение: Windows, Java 25, Kotlin 2.4.21, Docker, PostgreSQL 18.6.

| Проверка | Результат |
|---|---|
| spotlessApply build reportRegressionTest | Успешно |
| Полный набор test | 452 выполнения, 0 ошибок, 0 пропусков |
| Повторный набор reportRegressionTest | 69 выполнений, 0 ошибок, 0 пропусков |
| Новые проверки Kotlin | 5 методов, 14 выполнений в каждом наборе |
| До исправления DEF-02 | 14 выполнений нового класса, 4 падения; после исправления все 14 успешны |

XML и HTML: build/test-results/test, build/reports/tests/test, build/test-results/reportRegressionTest и build/reports/tests/reportRegressionTest. Исходный неуспешный XML сохранён локально в build/verification/bl14/before-fix.xml. Артефакты не включаются в Git и удаляются командой clean. Результаты разных наборов учитываются отдельно; повторный запуск не увеличивает число уникальных сценариев.
