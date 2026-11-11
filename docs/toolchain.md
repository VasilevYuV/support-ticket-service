# Инструменты и воспроизводимая сборка

**Автор:** Васильев Юрий, 4П1.23

## 1. Закреплённые версии

| Компонент | Версия | Где закреплено |
|---|---|---|
| Java и целевая JVM | 25 | `jvmToolchain(25)` в build.gradle.kts |
| Gradle | 9.7.0 | gradle/wrapper/gradle-wrapper.properties |
| Spring Boot | 4.1.2 | gradle/libs.versions.toml |
| Kotlin/JVM и Kotlin Spring plugin | 2.4.21 | gradle/libs.versions.toml |
| Spotless | 8.11.0 | gradle/libs.versions.toml |
| Google Java Format, стиль AOSP | 2.0.0 | gradle/libs.versions.toml |
| ktlint для Kotlin и Gradle Kotlin DSL | 1.8.0 | gradle/libs.versions.toml |

Внешний Gradle для работы не нужен: используются `gradlew.bat` в Windows и `gradlew` в Linux/macOS. Wrapper загружает конкретный дистрибутив и сверяет SHA-256 `84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae`.

Зависимости Spring, Jackson, JUnit и Mockito согласуются BOM Spring Boot 4.1.2. Kotlin compiler, стандартная библиотека, reflection приложения и test-библиотека используют одну версию 2.4.21, выбранную явно вместо версии Kotlin из BOM Spring Boot. Точные разрешённые зависимости сохраняются в `gradle.lockfile`; `settings-gradle.lockfile` фиксирует состояние каталога версий Gradle. Внутренние зависимости инструментов могут иметь собственные версии. Новые библиотеки добавляются с обновлением lock-файлов и проверкой сборки.

Выбор основан на [требованиях Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html), [матрице Kotlin Gradle plugin](https://kotlinlang.org/docs/gradle-configure-project.html), [совместимости Gradle и Java](https://docs.gradle.org/current/userguide/compatibility.html) и [управлении зависимостями Spring Boot](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html). Поддерживаемый диапазон Kotlin 2.4.21 включает Gradle 9.7.0; выбранный Gradle поддерживает запуск на Java 25. Актуальность документации не меняет закреплённые версии автоматически.

## 2. Остальная часть стека

| Компонент | Выбранная версия | Этап подключения |
|---|---|---|
| PostgreSQL server | 18.6 | BL-06 для проверки схемы, BL-21 для Compose |
| Spring Data JPA, PostgreSQL JDBC, Flyway | 4.1.2, 42.7.14, 12.4.0 из BOM Spring Boot 4.1.2 | Подключены на BL-06 |
| Testcontainers | 2.0.5 из BOM Spring Boot 4.1.2 | Подключён на BL-06 |
| springdoc-openapi | 3.2.0 | Подключён на BL-20, Swagger UI 5.34.0 зафиксирован в lock-файле |
| JaCoCo | 0.8.14 | Подключён на BL-19; версия в каталоге, зависимости в lock-файле |

PostgreSQL, JPA, Flyway и Testcontainers подключены на BL-06. Разрешённые версии записаны в lock-файле; результаты проверки схемы приведены в [инструкции PostgreSQL](database.md). springdoc подключён на BL-20: Swagger UI читает общий статический контракт `/openapi.yaml`. JaCoCo подключён на BL-19.

Для PostgreSQL выбран опубликованный выпуск [18.6](https://www.postgresql.org/docs/release/18.6/). springdoc 3.2.x указан как совместимый со Spring Boot 4.1.x в [матрице springdoc](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot). Схема и миграции проверяются на реальном PostgreSQL 18.6.

## 3. Структура текущего каркаса

- `SupportApplication.java` содержит точку входа Spring Boot.
- `config/TimeConfiguration.kt` предоставляет общий `Clock` в UTC.
- `application.yaml` задаёт имя приложения, порт, подключение к базе, Flyway, JPA и проверки здоровья.
- Предметные модули `ticket` и `category` содержат собственные `controller`, `service`, `repository`, `dto`, `entity`; у заявок выделены `mapper` и `validation`. Модуль `report` содержит controller, service, repository, dto и validation; бизнес-расчёт написан на Kotlin. Пустые классы и пакеты для будущих операций не создаются.
- Gradle Wrapper является стандартным сгенерированным инструментом Gradle; исходные служебные скрипты и их лицензия сохраняются.

Конфигурация времени на Kotlin подтверждает наличие исполняемого Kotlin-кода в каркасе. Она не заменяет обязательный бизнес-блок статистики и три содержательных Kotlin-теста по заданию.

## 4. Проверки каркаса

| Проверка | Что подтверждает |
|---|---|
| `ApplicationStartupTest` на Java | Приложение запускает HTTP-сервер на свободном порту, `/actuator/health` возвращает `200` и `UP` |
| `ApplicationWiringTest` на Kotlin | Java-приложение и Kotlin-конфигурация загружаются в один Spring context, общий Clock использует UTC |
| `spotlessCheck` | Формат Java, Kotlin и Gradle Kotlin DSL соответствует закреплённым форматтерам |
| `checkTextConventions` | В исходниках и документации отсутствуют длинные и средние тире |

Проверки каркаса отделены от бизнес-сценариев [тест-плана](test-plan.md). Они не засчитываются как проверки миграций, заявок или сквозные сценарии практики. Тесты запускают серверы на случайных портах. Начиная с BL-06 они требуют Docker и получают отдельную базу через Testcontainers.

## 5. Обновление зависимостей

Обычная сборка использует существующий lock-файл:

```powershell
.\gradlew.bat clean build
```

После намеренного изменения версий или добавления зависимости:

```powershell
.\gradlew.bat clean build --write-locks
```

Изменения каталога версий и lock-файла проверяются вместе. `--write-locks` не нужен для обычного запуска. Версия Wrapper изменяется официальной задачей `wrapper` с указанием версии и SHA-256 нового дистрибутива.

Для модульных проверок предусмотрена задача `unitTest`: классы *Test из пакетов service, validation и common.contract, без Spring-контекста и Docker, с зоной Pacific/Honolulu и случайным порядком JUnit (seed=20261112). Она не входит в build и записывает результаты отдельно в build/test-results/unitTest и build/reports/tests/unitTest. Состав и результаты: [BL-15](unit-testing.md).

Для интеграционных проверок добавлена задача `integrationTest`: классы *Test из controller и database.migration, с настоящим PostgreSQL через Testcontainers. Она запускается явно и сохраняет собственные результаты. `processTestResources` копирует docs/openapi.yaml в тестовый classpath. Проверка ответов использует только тестовые зависимости JSON Schema Validator 3.0.6 и Jackson YAML 3.1.7; версии закреплены в каталоге и lock-файле. Jackson 3.1.7 из BOM одинаков в приложении и тестах. Подробнее: [BL-16](integration-testing.md).

Для сквозных проверок предусмотрена задача `e2eTest`: классы *E2ETest из пакетов scenario, полное HTTP-приложение и PostgreSQL через Testcontainers. Она запускается явно и сохраняет результаты в build/test-results/e2eTest и build/reports/tests/e2eTest. Полный test включает E2E, integrationTest и unitTest их не выбирают. Сценарии и результаты: [BL-17](end-to-end-testing.md).

Для повторных проверок отчёта добавлена задача `reportRegressionTest`. Она использует тот же testRuntimeClasspath и закреплённые зависимости, отдельную JVM с Pacific/Kiritimati и случайный порядок классов и методов JUnit с seed=20261111. Задача запускается явно, не входит в build и сохраняет собственный XML/HTML-отчёт. Объём и результаты: [BL-14](report-boundaries.md).

Для проверки исправлений DEF-01 и DEF-02 используется `scripts/verify-regressions.ps1` (PowerShell 7). Он копирует текущий коммит через Git, запускает выбранные тесты в отдельном каталоге build и сохраняет результаты для вариантов с исправлением и без него. В обычный build эта проверка не включена. Подробности: [BL-18](defect-regression.md).

Для покрытия используется JaCoCo 0.8.14, поддерживающий Java 25 согласно [истории выпусков](https://github.com/jacoco/jacoco/releases/tag/v0.8.14). Задача `jacocoTestReport` зависит от полного `test` и входит в `check` и `build`. HTML и XML включены явно по [документации Gradle](https://docs.gradle.org/current/userguide/jacoco_plugin.html). Измеряются классы всего main, включая Java и Kotlin, без пользовательских исключений. Используется только `build/jacoco/test.exec`; результаты отдельных unitTest, integrationTest, e2eTest и reportRegressionTest не смешиваются. Отчёт и ограничения: [BL-19](test-coverage.md).

Для проверки коллекции BL-20 отдельно используется Newman 6.2.1 через npm exec (проверено с Node.js 24.9.0 и npm 11.6.0). Node.js не нужен для Java/Kotlin-сборки и запуска сервиса. Команда и отчёты: [демонстрация API](api-demo.md).

На BL-21 добавлены Dockerfile и Compose. Сборка внутри Linux использует закреплённый digest JDK 25, запуск - JRE 25; PostgreSQL 18.6 также закреплён digest. Конфигурация Gradle внутри образа совпадает с локальной, bootJar не запускает тесты. Команды, условия первой сборки и результаты: [контейнерный запуск](container-runtime.md).

## 6. Локальное окружение

При первоначальной проверке Gradle 9.3.0 компилировал классы, но тестовый процесс завершался с `ClassNotFoundException`: файл аргументов с кириллическим путём записывался в UTF-8, а Java в Windows читала его в Cp1251. Это соответствует [ошибке Gradle #30304](https://github.com/gradle/gradle/issues/30304), исправленной в 9.7.0. Поэтому выбраны Gradle 9.7.0 и совместимый Kotlin 2.4.21. Путь проекта и системная кодировка Windows не изменялись.

При подготовке BL-05 обнаружены JDK 25 (`25+37-LTS-3491`) и Docker CLI 28.4.0. Docker Engine не был запущен: отсутствовал канал `dockerDesktopLinuxEngine`. На BL-05 база не требовалась. Перед BL-06 Docker Engine запущен и проверен: Docker Desktop 4.46.0, Engine 28.4.0, Linux/amd64. Текущие тесты требуют Docker.

Сборочные файлы не содержат локального абсолютного пути к JDK. Для запуска нужен установленный JDK 25 и корректный `JAVA_HOME`. Первый запуск Wrapper и первая сборка требуют доступа к официальным репозиториям Gradle, Maven Central и Gradle Plugin Portal. Последующие сборки используют локальный кеш.

Удалённый репозиторий в Git пока не настроен. Pull request, registry и учебный стенд остаются внешними зависимостями этапов BL-24-BL-26.

## 7. Результаты BL-05

Проверки выполнены 11.11.2026 в Windows на JDK 25, в исходной папке проекта с кириллицей в имени.

| Проверка | Результат |
|---|---|
| `spotlessApply clean build --write-locks` | Сборка успешна, версии зависимостей сохранены |
| Повторный `clean build` без `--write-locks` | Сборка успешна с сохранёнными версиями зависимостей |
| Тесты запуска Java и Kotlin | 2 выполнено, 0 ошибок, 0 пропущено |
| `spotlessCheck` и `checkTextConventions` в составе сборки | Форматирование соответствует правилам, запрещённых тире нет |
| `java -jar ... --server.port=0`, затем HTTP-запрос | `200`, `status: UP`, группы `liveness` и `readiness`; процесс остановлен после проверки |

В зависимостях тестов разрешены JUnit 6.0.3 и Mockito 5.23.0. XML и HTML-отчёты находятся в `build/test-results/test` и `build/reports/tests/test`. Эти результаты подтверждают каркас и не заменяют проверки PostgreSQL, бизнес-API, контейнеризации или доставки.
