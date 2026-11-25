param(
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$composeFile = Join-Path $repoRoot 'compose.scale.yaml'
$projectName = 'cinema-phase2-scale'

function Invoke-ScaleCompose {
    & docker compose --project-name $projectName --file $composeFile @args
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed with exit code $LASTEXITCODE"
    }
}

function Wait-Ready {
    param([string]$Url, [string]$Name)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing
            if ($response.StatusCode -eq 200) {
                Write-Host "$Name ready at $Url"
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    Invoke-ScaleCompose logs --tail 120
    throw "$Name did not become ready at $Url"
}

$api1Port = if ($env:SCALE_API1_PORT) { $env:SCALE_API1_PORT } else { '8081' }
$api2Port = if ($env:SCALE_API2_PORT) { $env:SCALE_API2_PORT } else { '8082' }
$nginxPort = if ($env:SCALE_NGINX_PORT) { $env:SCALE_NGINX_PORT } else { '8080' }

Write-Host 'Starting PostgreSQL, Redis and API 1...'
Invoke-ScaleCompose up --detach --build db redis api1
Wait-Ready -Url "http://127.0.0.1:$api1Port/actuator/health/readiness" -Name 'API 1'

Write-Host 'Starting API 2 after API 1 completed Flyway...'
Invoke-ScaleCompose up --detach api2
Wait-Ready -Url "http://127.0.0.1:$api2Port/actuator/health/readiness" -Name 'API 2'

Write-Host 'Starting nginx...'
Invoke-ScaleCompose up --detach nginx
Wait-Ready -Url "http://127.0.0.1:$nginxPort/nginx-health" -Name 'nginx'

$redisReply = (& docker compose --project-name $projectName --file $composeFile exec -T redis redis-cli ping).Trim()
if ($LASTEXITCODE -ne 0 -or $redisReply -ne 'PONG') {
    throw "Redis health check failed: $redisReply"
}

Write-Host 'Scale demo is ready:'
Write-Host "  nginx: http://127.0.0.1:$nginxPort"
Write-Host "  API 1: http://127.0.0.1:$api1Port"
Write-Host "  API 2: http://127.0.0.1:$api2Port"
Write-Host '  Redis: PONG'
Invoke-ScaleCompose ps
