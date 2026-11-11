# CI для pull request

**Автор:** Васильев Юрий, 4П1.23

## 1. Назначение и запуск

Workflow [ci.yml](../.github/workflows/ci.yml) выполняет проверки pull request на GitHub Actions. Публикация образа и развёртывание относятся к BL-25/BL-26 и в этот workflow не входят.

Событие pull_request запускает CI при открытии PR, добавлении коммитов и повторном открытии. Фильтров по путям и целевым веткам нет, поэтому изменения документации также проверяются. Новый запуск для того же PR отменяет предыдущий. Предусмотрен workflow_dispatch для ручной проверки; кнопка Run workflow доступна после появления workflow в основной ветке.

Оба задания получают одну проверяемую ревизию. Для PR github.sha обозначает служебный merge-коммит GitHub, а не обязательно последний коммит исходной ветки. Этот SHA входит в имена артефактов и локальный тег образа. Поведение событий описано в [документации GitHub](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request).

## 2. Задания pipeline

| Задание | Действия | Условие успеха |
|---|---|---|
| verify / Format, build and test | Checkout, Temurin 25, Gradle Wrapper, проверка Docker, Spotless Java/Kotlin и текстовых правил, build со всеми тестами и JaCoCo, сохранение отчётов и JAR | Все команды успешны, HTML тестов и HTML/XML покрытия существуют, JAR сохранён |
| image / Build Docker image | Checkout той же ревизии, многоэтапная сборка Dockerfile, сохранение image inspect | verify успешен, docker build и сохранение метаданных завершены успешно |

Оба задания используют ubuntu-24.04 и ограничены 25 минутами. Gradle запускается через sh ./gradlew, поэтому права executable у файла в checkout не влияют на запуск. Зависимости разрешаются с существующими lock-файлами; --write-locks не используется. `build` включает полный `test`, Spotless, checkTextConventions и jacocoTestReport. Повторный запуск отдельных unitTest/integrationTest/e2eTest не нужен: их классы уже входят в test.

Проверка оформления отдельно предшествует сборке, чтобы быстро показать ошибки Java/Kotlin и запрещённые тире. checkTextConventions дополнен каталогом .github. Docker проверяется до тестов. Testcontainers сам создаёт PostgreSQL и передаёт подключение через ServiceConnection; .env, отдельный Compose и секреты БД для CI не нужны. Отсутствие Docker приводит к ошибке, тесты не пропускаются.

Связь needs: verify не позволяет начать image при провале проверок. У задания image нет условия always или continue-on-error. [Правила зависимых заданий GitHub](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-jobs#defining-prerequisite-jobs) определяют пропуск при ошибке зависимости. Проверка блокировки публикации/доставки реальным неуспешным запуском остаётся OPS-07 после добавления CD.

Образ собирается из исходников Dockerfile, без переноса JAR из verify. Это проверяет самостоятельность контейнерной сборки. Тег support-service:pr-<SHA> существует только на runner; команды login/push и подключения к стенду отсутствуют. ID в image.json подтверждает сборку, но не публикацию или доставку.

## 3. Actions и права

Версии Actions закреплены полными SHA коммитов в workflow; соответствие тегам проверено через GitHub API при подготовке BL-24.

| Action | Проверенный тег | Назначение |
|---|---|---|
| actions/checkout | v7.0.1 | Чтение исходников, persist-credentials=false |
| actions/setup-java | v6.2.0 | Установка Temurin 25 |
| gradle/actions/setup-gradle | v6.4.1 | Проверка Wrapper JAR и кэш Gradle |
| actions/upload-artifact | v7.0.2 | Отчёты, JAR и метаданные образа |

[setup-gradle](https://github.com/gradle/actions/blob/v6.4.1/setup-gradle/action.yml) по умолчанию проверяет Wrapper JAR. Выбран cache-provider=basic; для веток, отличных от основной, кэш по умолчанию доступен только для чтения. Wrapper отдельно проверяет SHA-256 дистрибутива Gradle 9.7.0 из gradle-wrapper.properties. Версия Java в CI закреплена по основной версии 25, обновление патча выполняет setup-java; Dockerfile использует точный digest JDK/JRE.

GITHUB_TOKEN ограничен contents:read; credentials не сохраняются в checkout. Workflow не запрашивает repository secrets, packages:write, доступ к environments или pull_request_target. Для PR из fork может потребоваться разрешение запуска согласно настройкам GitHub. Конфигурация предназначена для GitHub.com и GitHub-hosted runner.

## 4. Артефакты и ошибки

| Артефакт | Содержимое | Когда сохраняется |
|---|---|---|
| test-reports-<SHA> | JUnit XML из build/test-results/test, HTML тестов, HTML/XML JaCoCo | always(), включая неуспешные шаги |
| application-<SHA> | Исполняемый Boot JAR из build/libs, без *-plain.jar | После успешных предыдущих шагов verify |
| image-metadata-<SHA> | build/ci/image.json с результатом docker image inspect | После успешной сборки образа |

Срок хранения 14 дней. Открыть Actions → CI → нужный запуск → Artifacts, скачать и распаковать архив. В архиве отчётов найти reports/tests/test/index.html и reports/jacoco/test/html/index.html; XML тестов доступен в test-results/test. Полный Docker-образ не загружается в артефакты.

Если сборка упала до тестов, файлов отчётов может ещё не быть: upload-artifact выдаст предупреждение, исходная ошибка задания сохранится. Если тесты упали, публикуются полученные JUnit XML и HTML; JaCoCo может отсутствовать, поскольку зависимый jacocoTestReport не завершился. При успешной сборке отдельный шаг требует наличие всех трёх файлов отчётов. Параметры загрузки описаны в [upload-artifact](https://github.com/actions/upload-artifact/tree/v7.0.2).

При ошибке оформления исправить код или текст и отправить новый коммит. При ошибке теста изучить JUnit/HTML и логи, воспроизвести локально. При ошибке Dockerfile открыть шаг сборки образа; успешные отчёты и JAR первого задания сохраняются отдельно.

## 5. Локальное воспроизведение

PowerShell, JDK 25 и запущенный Docker Engine:

```powershell
.\gradlew.bat spotlessCheck checkTextConventions --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Проверка оформления не пройдена.' }
.\gradlew.bat build --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Сборка или тесты не пройдены.' }
docker build --tag support-service:ci-local .
if ($LASTEXITCODE -ne 0) { throw 'Образ не собран.' }
docker image inspect support-service:ci-local
```

На Linux используются те же Gradle-задачи через sh ./gradlew. Для чистого полного повторения можно выполнять команды в новой копии исходников; clean в рабочей папке удалит локальные материалы предыдущих этапов из build. Запуск команд локально проверяет сборку и тесты, но не работу GitHub runner, загрузку Actions-артефактов или события PR.

Синтаксис YAML, выражения и связи workflow можно проверить [actionlint](https://github.com/rhysd/actionlint). В BL-24 использована версия 1.7.12, архив проверен по SHA-256 из официального файла checksums. Инструмент размещён только в игнорируемом build.

## 6. Завершение проверки на GitHub

1. Подключить GitHub-репозиторий как remote, отправить основную ветку и ветку с CI, открыть pull request с изменением workflow. Перед отправкой убедиться, что в коммит не входят .env, build и backups.
2. В настройках репозитория разрешить GitHub Actions и используемые Actions, проверить доступность GitHub-hosted runner.
3. Дождаться успеха обоих заданий CI. Скачать все три артефакта, открыть отчёты, сверить проверяемый SHA и ID образа в image.json.
4. Сохранить URL PR и URL запуска, SHA, результаты тестов и ссылки/имена артефактов в этом документе. Только после этого отметить OPS-06 и AC-12.1 выполненными.

Чтобы ошибки CI запрещали слияние, в правилах основной ветки настроить обязательные проверки Format, build and test и Build Docker image после их первого появления. Наличие workflow само по себе не включает защиту ветки.

## 7. Результаты BL-24

Подготовлено в ветке feature-c/pull-request-ci на основе коммита 9eb861b (BL-23). Конфигурация и Gradle-проверка .github входят в текущий этап. Бизнес-код и тесты не менялись.

Локальная проверка выполнена 11.11.2026. Для Linux создана новая копия через git archive, добавлены изменённый build.gradle.kts и workflow. В ней отсутствовали .git, готовые JAR, результаты тестов и кэш проекта. Побайтово сверены 98 файлов исходников, тестов, сборки и workflow. Использован JDK-образ из Dockerfile, Temurin 25.0.5, Docker Engine 28.4.0 linux/amd64. Для вложенного Testcontainers на Docker Desktop передан TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal; workflow на Ubuntu runner этого параметра не требует.

Первый проход с рабочей папкой на Windows bind mount остановлен из-за медленной загрузки классов; его неполные результаты не засчитываются. Полная проверка повторена во внутренней файловой системе Linux-контейнера с копией исходников без build/.gradle/.kotlin и с уже загруженными зависимостями. Итоговый build завершился за 1 минуту 7 секунд. Проверочные контейнеры завершены и удалены, отчёты скопированы на компьютер.

| Проверка | Локальный результат |
|---|---|
| actionlint 1.7.12 | YAML, выражения и связи workflow проверены без ошибок |
| SHA и параметры Actions | Совпадают с метаданными закреплённых релизов |
| Spotless и текстовые правила в новой Linux-копии | Успешно, все задачи выполнены с новым кэшем |
| Актуальные документы и .github в рабочей папке | checkTextConventions успешен |
| Полный build в Linux | 507 тестов, 0 ошибок, 0 пропусков; 25 файлов JUnit XML |
| JaCoCo HTML/XML | Файлы созданы; строки 488/499, ветви 204/214 |
| Артефакт приложения | Boot JAR существует, проверены JarLauncher и Start-Class; plain.jar исключён из набора загрузки |
| Многоэтапный Dockerfile из проверочной копии | bootJar внутри Linux собран, итоговый образ создан |

Проверочный образ support-service:bl24-check имеет ID sha256:abb19ffc58700702a83eb49698834098624cb8a7849773534ff461a258f96dbb. Он сохранён локально, в registry не публиковался. Образ и Docker-том support-bl24-gradle с кэшем зависимостей сохранены для повторной проверки.

Материалы находятся в игнорируемом build: bl24-linux-native.log, bl24-linux-results/test-results/test, bl24-linux-results/reports, bl24-linux-results/libs, bl24-image-build.log, bl24-image.json, bl24-actionlint.log, bl24-action-versions.json, bl24-text-check.log и bl24-verification.json. Журнал остановленного прохода сохранён отдельно в bl24-linux-verification.log. clean удалит эти локальные файлы.

Удалённый репозиторий не настроен: git remote -v не возвращает записей. PR и запуск GitHub Actions пока отсутствуют, URL запуска нет. OPS-06 и AC-12.1 остаются ожидающими удалённой проверки; публикация, развёртывание и проверка блокировки доставки остаются BL-25-BL-27.
