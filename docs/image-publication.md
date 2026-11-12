# Публикация контейнерного образа

**Автор:** Васильев Юрий, 4П1.23

## 1. Назначение

BL-25 дополняет [workflow CI](../.github/workflows/ci.yml) публикацией в GitHub Container Registry. Адрес образа: ghcr.io/vasilevyuv/support-ticket-service:<полный-SHA-коммита>. Тег latest не создаётся. Развёртывание на стенд остаётся BL-26.

Для связи образа с репозиторием при сборке устанавливаются OCI-метки org.opencontainers.image.source и org.opencontainers.image.revision. Путь registry переводится в нижний регистр, поскольку имя владельца GitHub содержит прописные буквы.

Публикация использует GITHUB_TOKEN текущего запуска с packages:write. Дополнительный PAT или repository secret для этого не требуется. При первой публикации пакет по умолчанию закрытый; публичный репозиторий сам по себе не делает пакет публичным. Права пакета и загрузка из другого окружения проверяются отдельно. Основание: [GitHub Container Registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).

## 2. События и зависимости

| Событие | verify | image | publish |
|---|---|---|---|
| pull_request | Форматирование, сборка, тесты и отчёты | Сборка и метаданные | Пропущено |
| push в main, в том числе после слияния PR | Те же проверки | Сборка, метаданные и архив образа | После успеха image |
| workflow_dispatch на main, без контрольного отказа | Те же проверки | Сборка и архив | После успеха image |
| workflow_dispatch в другой ветке, без контрольного отказа | Те же проверки | Только сборка и метаданные | Пропущено |
| workflow_dispatch на main с verify_publication_gate=true | Временный падающий JUnit-тест | Должно быть skipped | Должно быть skipped |

Цепочка зависимостей: verify → image → publish. У publish нет always() или continue-on-error. Условие публикации не исключает verify_publication_gate: в контрольном запуске публикацию должен остановить именно провал теста, а не отдельный запрет по входному параметру. Если контрольный режим выбран не на main, запуск отклоняется до создания теста и не засчитывается как проверка блокировки.

Новый запуск PR отменяет предыдущий для того же PR. Уже выполняющийся запуск main не прерывается новым коммитом. Права verify и image остаются contents:read, packages:write выдан только publish. Docker credentials сохраняются во временной папке runner, токен передаётся через stdin, завершение сеанса registry выполняется через always().

Workflow реагирует на любой push в main. Для ограничения изменения main слияниями PR нужно отдельно настроить правила ветки; один YAML-файл не запрещает прямой push.

## 3. Передача и публикация образа

В image образ собирается один раз под локальным тегом support-service:ci-<SHA>. Для main выполняется docker save с gzip, а архив и image.json передаются в артефакте container-image-<SHA>. Он хранится один день и не создаётся для PR. Архив уже сжат, повторное сжатие upload-artifact отключено.

Задание publish выполняет следующие действия:

1. Скачивает артефакт текущего запуска через actions/download-artifact v8.0.2, закреплённый SHA.
2. Выполняет docker load и сверяет ID с image.json, затем проверяет OCI-метку revision по GITHUB_SHA.
3. Входит в ghcr.io с GITHUB_TOKEN и присваивает уже загруженному образу тег полного SHA.
4. Выполняет docker push, получает RepoDigests и формирует release.json.
5. Загружает образ из registry по digest и повторно сверяет его ID.
6. Сохраняет release.json и published-image.json в артефакте release-<SHA> на 14 дней.

Повторной сборки в publish нет. В output publish.image передаётся ссылка ghcr.io/vasilevyuv/support-ticket-service@sha256:..., которую можно использовать для развёртывания конкретного содержимого. Формат release.json:

```json
{
  "tag": "ghcr.io/vasilevyuv/support-ticket-service:<commit-sha>",
  "image": "ghcr.io/vasilevyuv/support-ticket-service@sha256:<registry-digest>",
  "imageId": "sha256:<image-id>",
  "commit": "<commit-sha>"
}
```

SHA-тег обозначает версию исходников, digest фиксирует содержимое образа. Повторный обычный ручной запуск на том же коммите может пересобрать и обновить этот тег; для стенда и отката нужно сохранять ссылку по digest. ID локального Docker-образа и digest registry не следует считать одним значением.

Если push прошёл, а последующая проверка или сохранение артефакта завершились ошибкой, образ уже может присутствовать в registry. Такой запуск не считается успешной публикацией всего pipeline; причину нужно проверить в логах и registry. Публикация образа не подтверждает его работу на стенде.

## 4. Проверка после коммита и слияния

Сначала отправить изменения в PR и дождаться успеха verify/image. Publish на PR должен быть skipped. После слияния в main автоматически начнётся запуск push; он должен завершить все три задания успешно.

Скачать release-<SHA>, сверить commit с SHA основной ветки, tag с полным SHA и image с digest опубликованного пакета. В логах проверить загрузку по digest и сравнение ID. Сохранить URL запуска, tag, digest, ID и артефакт release.json. Не использовать для этого служебный merge-SHA старого PR-запуска вместо SHA фактического коммита main.

Для проверки блокировки открыть Actions → CI → Run workflow, выбрать main и включить verify_publication_gate. В рабочей копии runner будет создан PublicationGateProbeTest с явным AssertionFailedError. Этот класс отсутствует в src репозитория, не требует изменения истории и не попадает в образ: image получает отдельный checkout, а при отказе verify вообще не запускается.

Ожидаемый результат контрольного запуска:

- verify завершается failure именно на JUnit-тесте, а не на checkout, оформлении или недоступном Docker;
- в test-reports-<SHA> есть XML с отказом PublicationGateProbeTest;
- image и publish имеют skipped;
- новые container-image-<SHA> и release-<SHA> в этом запуске отсутствуют;
- digest ранее опубликованного SHA-тега не изменился.

После этого повторить ручной запуск на main без контрольного режима и подтвердить возвращение нормальных проверок. Падающий класс создаётся только в отдельной рабочей копии контрольного запуска; постоянно падающий тест в проект не добавляется. Это эксплуатационная проверка, а не найденный дефект бизнес-логики.

Пока задания развёртывания нет, проверяется только блокировка публикации. OPS-07 и AC-12.3 полностью закрываются после добавления и проверки блокировки доставки в BL-26.

## 5. Текущие результаты BL-25

Подготовлено 11.11.2026 поверх коммита 883007fec36f8f0f6c063bb68b057f10ca793cf1 прежней опубликованной истории. Этот коммит с результатами BL-24 отправлен в [PR #1](https://github.com/VasilevYuV/support-ticket-service/pull/1), оба задания повторного [CI #2](https://github.com/VasilevYuV/support-ticket-service/actions/runs/43092583552) прошли успешно. После переоформления истории 12.11.2026 ему соответствует коммит 817b4b983f6460078f91fbebfa8d7347e41e101a. CI #2 подтверждает прежнюю ревизию и workflow без публикации. Переписанная история опубликована; настройки BL-25 зафиксированы коммитом b0f1d17b82f873355d1a4283f64940b2ac55c18a и отправлены в [PR #2](https://github.com/VasilevYuV/support-ticket-service/pull/2).

Следующие результаты получены до переоформления истории и относятся к сохранённым проверочным материалам прежней ревизии.

| Локальная проверка | Результат |
|---|---|
| actionlint 1.7.12 | Workflow прошёл проверку |
| actions/download-artifact | v8.0.2, SHA 9000827ccba6bdab643e8b6fd33ac0654aef8333 и параметры сверены с GitHub |
| Команды image/save/load из YAML | Выполнены без изменения в отдельной копии; после удаления проверочного тега архив восстановил тот же ID и revision |
| Неверный ожидаемый ID | Загрузка выполнена, проверка ID остановила шаг с кодом 1 до входа в registry |
| Контрольный JUnit-тест | Форматирование прошло; выбранный тест дал ровно один ожидаемый отказ, ошибок инфраструктуры нет |

Проверочный образ имеет ID sha256:9c21049fd9f5f194e409c7a67211e092c0ac36c01be557ba19fe703bbfd06026. При локальной проверке использованы Git Bash, Docker Desktop 28.4.0 и Temurin 25.0.5 в Linux-контейнере; для команды python3 в Git Bash использован локальный Python. Проверочный контейнер удалён после копирования JUnit XML. Основные исходники и тесты приложения не менялись.

Материалы: build/bl25-image-transfer.log, bl25-image-mismatch.log, bl25-gate-probe.log, bl25-gate-test-results, bl25-actionlint.log и bl25-verification.json. Архив образа находится в build/bl25-check/build/ci/image.tar.gz. Эти локальные файлы игнорируются Git и удаляются clean.

На момент локальной подготовки реальная публикация в GHCR, загрузка через download-artifact и отрицательный запуск на GitHub ещё не выполнялись. Локальный отказ JUnit проверяет подготовку контрольного теста, но не доказывает статус skipped заданий GitHub Actions. Последующая проверка опубликованной ревизии фиксируется отдельно ниже.

## 6. Проверка PR после переоформления истории

12.11.2026 [PR #2](https://github.com/VasilevYuV/support-ticket-service/pull/2) проверен запуском [CI #5](https://github.com/VasilevYuV/support-ticket-service/actions/runs/43260359554). Коммит ветки - b0f1d17b82f873355d1a4283f64940b2ac55c18a; проверенный merge-SHA - f67d1233436ceb7685c40b5b5c486e1a9dc35ac9. PR слит в main коммитом 8b458ffa1998f36d658e4ca2acbb333bd208058e.

| Проверка | Результат |
|---|---|
| verify | success; оформление, сборка и тесты прошли |
| JUnit XML | 25 файлов, 507 тестов, 0 отказов, 0 ошибок, 0 пропусков; пакет ru.vasilev.support |
| JaCoCo | Строки 488/499, ветви 204/214; HTML и XML присутствуют |
| Boot JAR | Один исполняемый JAR; Start-Class ru.vasilev.support.SupportApplication; старого пакета нет; OpenAPI совпадает с docs/openapi.yaml |
| image | success; образ собран после verify |
| publish | skipped; публикация на pull_request запрещена |
| Метаданные образа | revision совпадает с merge-SHA; автор Васильев Юрий, 4П1.23; пользователь 10001:10001 |

ID проверочного образа PR: sha256:5f77cdc03ea0693843fd955dc37b1fc206c29c45a79d8e994e8fdd5987821b2a. SHA-256 скачанных ZIP отчётов, приложения и метаданных совпали с digest GitHub. SHA-256 JAR: 18352d8807554d11b31c4e261a0307e642a50a00f78ef099e9ca2b590e1cc30c. Файлы проверки сохранены в build/bl25-pr-test-reports.zip, bl25-pr-application.zip, bl25-pr-image-metadata.zip, bl25-pr-evidence.json и bl25-pr-verification.json. Они игнорируются Git и удаляются clean.

Этот запуск подтверждает проверки PR и запрет публикации для PR. Проверка блокировки после ошибки теста требует отдельного запуска на main, где публикация иначе разрешена. OPS-07 и AC-12.3 остаются незавершёнными до проверки публикации и доставки.

## 7. Публикация после слияния

12.11.2026 [CI #6](https://github.com/VasilevYuV/support-ticket-service/actions/runs/43261561108) на push в main завершился успешно для коммита 8b458ffa1998f36d658e4ca2acbb333bd208058e. Все три задания verify, image и publish имеют success. Скачанные JUnit XML подтверждают 507 тестов без отказов, ошибок и пропусков.

Проверены передача архива через download-artifact, загрузка образа, сравнение ID и revision, вход с GITHUB_TOKEN, публикация и загрузка по digest. В release.json сохранены:

```text
tag: ghcr.io/vasilevyuv/support-ticket-service:8b458ffa1998f36d658e4ca2acbb333bd208058e
image: ghcr.io/vasilevyuv/support-ticket-service@sha256:023aa4003d0b96ba0eba3dcf4aa519631d1552f0afd49ca7463e58f5630148cc
imageId: sha256:0e1fedd79193c8ab53dd248e5ff815f227f8eee8ad8d40a3f829905fe9fcc40e
```

Manifest SHA-тега отдельно прочитан через GHCR Registry API без учётных данных; digest и config.digest совпали с image и imageId из release.json. Полная загрузка образа по digest проверена заданием publish с GITHUB_TOKEN. Чтение через GitHub Packages REST API текущей локальной учётной записью вернуло 403; для сверки использован Registry API, права пакета не менялись.

SHA-256 ZIP release-артефакта: a4a71b61880b136b32e0bd6ad7dc73cb6001878c7a4e5cc0ae79973b226a6f29, совпадает с digest GitHub. Материалы: build/bl25-main-release.zip, bl25-main-release.json, bl25-main-test-reports.zip, bl25-main-evidence.json, bl25-main-verification.json и bl25-package-before-gate.json. Архив container-image-<SHA> хранится на GitHub один день; release и отчёты хранятся 14 дней. Локальные материалы удаляются clean.

Публикация подтверждена. Развёртывание приложения на стенде этим запуском не выполнялось.

## 8. Контрольный отказ

12.11.2026 [CI #7](https://github.com/VasilevYuV/support-ticket-service/actions/runs/43262916822) запущен вручную на той же main с verify_publication_gate=true. Verify завершился failure на шаге тестирования, после успешного оформления и подготовки временного класса.

Скачанные JUnit XML содержат 508 тестов: 507 успешных и ровно один отказ ru.vasilev.support.ci.PublicationGateProbeTest с AssertionFailedError и сообщением о контрольном отказе. Ошибок инфраструктуры и пропусков нет. Класс создан только внутри рабочей копии runner и отсутствует в исходниках репозитория.

Image и publish имеют skipped. Единственный артефакт запуска - test-reports-8b458ffa1998f36d658e4ca2acbb333bd208058e; container-image и release отсутствуют. После завершения запуска manifest SHA-тега повторно прочитан из GHCR: digest sha256:023aa4003d0b96ba0eba3dcf4aa519631d1552f0afd49ca7463e58f5630148cc и весь manifest совпали со снимком перед отказом.

Таким образом, публикацию остановил отказ теста на разрешённом для публикации пути main. Это проверка защиты pipeline, а не дефект бизнес-логики. Блокировка развёртывания требует отдельной проверки после добавления задания доставки в BL-26.

Материалы: build/bl25-gate-test-reports.zip, bl25-gate-verification.json, bl25-gate-evidence.json и bl25-package-after-gate.json. SHA-256 ZIP отчётов 1d13d59fbaa1aefb44b190ef03b48fdc840d472d6e6017158a8664cf35a1c517 совпал с digest GitHub.

## 9. Восстановление обычного запуска

12.11.2026 [CI #8](https://github.com/VasilevYuV/support-ticket-service/actions/runs/43263479995) выполнен на той же main с verify_publication_gate=false. Verify, image и publish завершились success. Скачанные 25 JUnit XML подтверждают 507 успешных тестов без ошибок и пропусков; PublicationGateProbeTest в артефакте отсутствует.

Образ повторно собран и опубликован. Его загрузка по digest и сравнение ID прошли в CI; отдельное чтение manifest из GHCR подтвердило release.json. Актуальный результат этого запуска:

```text
tag: ghcr.io/vasilevyuv/support-ticket-service:8b458ffa1998f36d658e4ca2acbb333bd208058e
image: ghcr.io/vasilevyuv/support-ticket-service@sha256:b22e61410deb1d2a0fb3ac5e00283fc5b63591b4d57f63cf93162a57aa68fc95
imageId: sha256:bee7264d410ce846d3938337b41fc07b4cb947821d9b8269dfcbd61704ac769f
```

Повторный обычный запуск обновил SHA-тег новым digest; неизменность предыдущего digest проверялась отдельно между CI #6 и контрольным CI #7, до этого повторного запуска. Для воспроизводимого развёртывания нужно использовать image с digest из выбранного release.json, а не полагаться только на SHA-тег.

Материалы: build/bl25-recovery-test-reports.zip, bl25-recovery-release.zip, bl25-recovery-release.json, bl25-recovery-evidence.json, bl25-recovery-verification.json и bl25-package-after-recovery.json. SHA-256 ZIP отчётов e5359765d08039354fd067c41b577132562d82e117ece53f715f6207b0f3be8a и ZIP release b8af3b3afbc03fbf8fc9f8be63cdcc9a1bbcd1fbe4b37cbaa8fa93279c58f3ef совпали с digest GitHub.

BL-25 завершён: публикация, блокировка после отказа теста и восстановление обычного запуска проверены. OPS-07 и AC-12.3 подтверждены в части публикации; их часть, связанная с доставкой, остаётся BL-26. На 12.11.2026 учебный стенд не предоставлен, локальная виртуальная машина с runner не подготовлена. Работа приложения на стенде и откат не объявляются проверенными.
