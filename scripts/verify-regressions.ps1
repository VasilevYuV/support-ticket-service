# Проверка регрессии DEF-01 и DEF-02. Васильев Юрий, 4П1.23.
#Requires -Version 7.0

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectRoot = Split-Path -Parent $PSScriptRoot
$revision = (& git -C $projectRoot rev-parse --verify HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Не удалось определить коммит.' }

$runRoot = Join-Path $projectRoot ('build/regression-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$copyRoot = Join-Path $runRoot 'project'
$archive = Join-Path $runRoot 'source.zip'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$results = [System.Collections.Generic.List[object]]::new()
New-Item -ItemType Directory -Path $runRoot | Out-Null
& git -C $projectRoot archive --format=zip --output $archive $revision
if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать копию коммита.' }
Expand-Archive -LiteralPath $archive -DestinationPath $copyRoot

function Invoke-RegressionRun {
    param($Defect, [string]$Variant)

    $resultDirectory = Join-Path $runRoot ($Defect.Id + '-' + $Variant)
    New-Item -ItemType Directory -Path $resultDirectory | Out-Null
    $arguments = @('test', '--no-daemon', '--console=plain', '--rerun-tasks')
    foreach ($filter in $Defect.Tests) { $arguments += @('--tests', $filter) }
    $startedAt = [DateTime]::UtcNow
    Write-Host ($Defect.Id + ': ' + $Variant)
    Push-Location $copyRoot
    try {
        & .\gradlew.bat @arguments *> (Join-Path $resultDirectory 'gradle.log')
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    $reports = @(Get-ChildItem -LiteralPath (Join-Path $copyRoot 'build/test-results/test') -Filter 'TEST-*.xml')
    if ($reports.Count -eq 0) { throw 'JUnit XML не создан; результат тестов не подтверждён.' }
    $total = 0
    $failures = 0
    $errors = 0
    $skipped = 0
    $cases = [System.Collections.Generic.List[object]]::new()
    foreach ($report in $reports) {
        if ($report.LastWriteTimeUtc -lt $startedAt) { throw 'Обнаружен XML от предыдущего запуска.' }
        Copy-Item -LiteralPath $report.FullName -Destination $resultDirectory
        [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw -Encoding utf8
        $total += [int]$xml.testsuite.tests
        $failures += [int]$xml.testsuite.failures
        $errors += [int]$xml.testsuite.errors
        $skipped += [int]$xml.testsuite.skipped
        foreach ($case in $xml.testsuite.testcase) {
            $messages = @($case.SelectNodes('./failure|./error') | ForEach-Object { $_.InnerText })
            $cases.Add([ordered]@{ name = [string]$case.name; failures = $messages })
        }
    }

    $row = [ordered]@{
        defect = $Defect.Id
        variant = $Variant
        startedAtUtc = $startedAt.ToString('o')
        exitCode = $exitCode
        tests = $total
        failures = $failures
        errors = $errors
        skipped = $skipped
        cases = $cases.ToArray()
    }
    $results.Add($row)
    [ordered]@{ revision = $revision; runs = $results.ToArray() } |
        ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath (Join-Path $runRoot 'summary.json') -Encoding utf8

    if ($total -ne $Defect.Count -or $skipped -ne 0 -or $errors -ne 0) {
        throw 'Состав выполненных проверок не совпадает с ожидаемым.'
    }
    if ($Variant -eq 'without-fix') {
        $knownFailure = @($cases | Where-Object {
            $_.name -like $Defect.FailedCase -and ($_.failures -join "`n") -match $Defect.FailurePattern
        })
        if ($exitCode -eq 0 -or $failures -eq 0 -or $knownFailure.Count -eq 0) {
            throw ('Не воспроизведено исходное проявление ' + $Defect.Id)
        }
    } elseif ($exitCode -ne 0 -or $failures -ne 0) {
        throw ('Регрессия не прошла с исправлением ' + $Defect.Id)
    }
    Write-Host ('Проверено: {0}, выполнений {1}, падений {2}.' -f $Variant, $total, $failures)
}

$defects = @(
    @{
        Id = 'DEF-01'
        File = 'src/main/java/ru/vasilev/support/common/validation/ApiUuid.java'
        Original = '!FORMAT.matcher(value).matches()'
        Replacement = 'false'
        Tests = @('*RequestValidationApiTest.rejectsNonCanonicalUuid')
        Count = 4
        FailedCase = '*1-1-1-1-1*'
        FailurePattern = '(?s)expected: 400.*but was: 404'
    },
    @{
        Id = 'DEF-02'
        File = 'src/main/resources/application.yaml'
        Original = '      hibernate.type.preferred_instant_jdbc_type: TIMESTAMP_WITH_TIMEZONE'
        Replacement = ''
        Tests = @(
            '*SummaryReportBoundaryApiTest.preservesLargeDurationWithoutOverflowOrDecimalLoss',
            '*SummaryReportBoundaryApiTest.preservesCalendarDateInDatabaseAndReport'
        )
        Count = 6
        FailedCase = 'preservesLargeDurationWithoutOverflowOrDecimalLoss*'
        FailurePattern = '(?s)expected: 1L.*but was: 0L'
    }
)

foreach ($defect in $defects) {
    $target = Join-Path $copyRoot $defect.File
    $originalBytes = [System.IO.File]::ReadAllBytes($target)
    $source = [System.IO.File]::ReadAllText($target, $utf8)
    if ([regex]::Matches($source, [regex]::Escape($defect.Original)).Count -ne 1) {
        throw ('Исправление не найдено однозначно: ' + $defect.Id)
    }
    try {
        [System.IO.File]::WriteAllText($target, $source.Replace($defect.Original, $defect.Replacement), $utf8)
        Invoke-RegressionRun $defect 'without-fix'
    } finally {
        [System.IO.File]::WriteAllBytes($target, $originalBytes)
    }
    Invoke-RegressionRun $defect 'with-fix'
}

Write-Host ('Обе регрессии подтверждены. Коммит: ' + $revision)
Write-Host ('Результаты: ' + $runRoot)
