# Резервная копия и восстановление PostgreSQL. Васильев Юрий, 4П1.23.
#Requires -Version 7.0

param(
    [Parameter(Mandatory)]
    [ValidateSet('Backup', 'Restore')]
    [string]$Action,
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$FilePath,
    [string]$TargetDatabase,
    [string]$EnvFile,
    [string]$ProjectName
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectRoot = Split-Path -Parent $PSScriptRoot
$dockerCli = (Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$archivePath = [System.IO.Path]::GetFullPath($FilePath, $PWD.Path)
$composeArgs = @('compose', '--project-directory', $projectRoot, '-f', (Join-Path $projectRoot 'compose.yaml'))
if ($EnvFile) { $composeArgs += @('--env-file', $EnvFile) }
if ($ProjectName) { $composeArgs += @('-p', $ProjectName) }

function Invoke-Docker {
    param([string[]]$Arguments)
    & $dockerCli @Arguments
    if ($LASTEXITCODE -ne 0) { throw ('Ошибка Docker/PostgreSQL, код ' + $LASTEXITCODE) }
}

if ($Action -eq 'Backup') {
    if ($TargetDatabase) { throw 'TargetDatabase используется только при восстановлении.' }
    if (Test-Path -LiteralPath $archivePath) { throw 'Файл копии уже существует; выберите новое имя.' }
} else {
    if ($TargetDatabase -cnotmatch '^[a-z][a-z0-9_]{0,62}$') {
        throw 'Имя новой базы: от 1 до 63 строчных латинских букв, цифр и подчёркиваний, начиная с буквы.'
    }
    if (!(Test-Path -LiteralPath $archivePath -PathType Leaf)) { throw 'Файл копии не найден.' }
}

$containers = @(Invoke-Docker ($composeArgs + @('ps', '--status', 'running', '-q', 'postgres')))
if ($containers.Count -ne 1) { throw 'Нужен один работающий сервис postgres выбранного проекта Compose.' }
$containerId = $containers[0].Trim()
$database = (Invoke-Docker @('exec', $containerId, 'printenv', 'POSTGRES_DB')).Trim()
$databaseUser = (Invoke-Docker @('exec', $containerId, 'printenv', 'POSTGRES_USER')).Trim()

if ($Action -eq 'Restore') {
    if ($TargetDatabase -eq $database) { throw 'Восстановление в исходную базу запрещено.' }
    $exists = Invoke-Docker @(
        'exec', $containerId, 'psql', '-U', $databaseUser, '-d', $database, '-At', '-v', 'ON_ERROR_STOP=1',
        '-c', "SELECT 1 FROM pg_database WHERE datname = '$TargetDatabase'"
    )
    if ($exists) { throw 'Целевая база уже существует; выберите новое имя.' }
}

$operationId = [Guid]::NewGuid().ToString('N')
$containerArchive = '/tmp/support-' + $operationId + '.dump'
$partialPath = $archivePath + '.' + $operationId + '.partial'
try {
    if ($Action -eq 'Backup') {
        New-Item -ItemType Directory -Path (Split-Path -Parent $archivePath) -Force | Out-Null
        Invoke-Docker @(
            'exec', $containerId, 'pg_dump', '--username', $databaseUser, '--dbname', $database,
            '--format=custom', '--file', $containerArchive
        )
        Invoke-Docker @('exec', $containerId, 'pg_restore', '--list', $containerArchive) | Out-Null
        Invoke-Docker @('cp', "${containerId}:$containerArchive", $partialPath)
        if ((Get-Item -LiteralPath $partialPath).Length -eq 0) { throw 'Получен пустой файл копии.' }
        # Готовый архив появляется под выбранным именем только после успешного копирования.
        [System.IO.File]::Move($partialPath, $archivePath)
    } else {
        Invoke-Docker @('cp', $archivePath, "${containerId}:$containerArchive")
        Invoke-Docker @('exec', $containerId, 'pg_restore', '--list', $containerArchive) | Out-Null
        Invoke-Docker @(
            'exec', $containerId, 'createdb', '--username', $databaseUser,
            '--template=template0', '--owner', $databaseUser, $TargetDatabase
        )
        Invoke-Docker @(
            'exec', $containerId, 'pg_restore', '--username', $databaseUser, '--dbname', $TargetDatabase,
            '--no-owner', '--no-privileges', '--single-transaction', '--exit-on-error', $containerArchive
        )
    }
} finally {
    & $dockerCli exec $containerId rm -f -- $containerArchive
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Не удалось удалить $containerArchive в контейнере $containerId. Удалите файл после восстановления доступа."
    }
    if (Test-Path -LiteralPath $partialPath) { Remove-Item -LiteralPath $partialPath }
}

[ordered]@{
    action = $Action
    database = $(if ($Action -eq 'Restore') { $TargetDatabase } else { $database })
    file = $archivePath
    bytes = (Get-Item -LiteralPath $archivePath).Length
    sha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
}
