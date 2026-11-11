param(
    [ValidateRange(1, 65535)]
    [int]$DbPort = 5432,
    [ValidateRange(1, 65535)]
    [int]$AppPort = 8080,
    [switch]$Test
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Uses the running local PostgreSQL server. Database setup is a separate step.
Get-Command mvn -ErrorAction Stop | Out-Null
Get-Command java -ErrorAction Stop | Out-Null

$database = 'event_planner'
$databaseUser = 'planner'
if ($Test) {
    $database = 'event_planner_test'
    $databaseUser = 'planner_test'
}

$savedEnvironment = @{}
$environmentNames = @(
    'DB_URL', 'DB_USER', 'DB_PASSWORD', 'PORT',
    'TEST_DATABASE_URL', 'TEST_DATABASE_USER', 'TEST_DATABASE_PASSWORD'
)
foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$password = Read-Host "PostgreSQL password for $databaseUser" -AsSecureString
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $connectionPassword = [System.Net.NetworkCredential]::new('', $password).Password
    $databaseUrl = "jdbc:postgresql://localhost:${DbPort}/$database"
    if ($Test) {
        # The integration tests write data; they must use their own database.
        $env:TEST_DATABASE_URL = $databaseUrl
        $env:TEST_DATABASE_USER = $databaseUser
        $env:TEST_DATABASE_PASSWORD = $connectionPassword
        & mvn -B verify
        if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
    } else {
        $env:DB_URL = $databaseUrl
        $env:DB_USER = $databaseUser
        $env:DB_PASSWORD = $connectionPassword
        $env:PORT = [string]$AppPort
        & mvn -B package
        if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
        Write-Host "Starting application: http://localhost:$AppPort (Ctrl+C to stop)"
        & java -jar target/event-planner.jar
        if ($LASTEXITCODE -ne 0) { throw 'Application exited with an error.' }
    }
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    $connectionPassword = $null
    $password.Dispose()
    Pop-Location
}
