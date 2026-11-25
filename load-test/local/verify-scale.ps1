param(
    [int]$RequestCount = 6
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$composeFile = Join-Path $repoRoot 'compose.scale.yaml'
$projectName = 'cinema-phase2-scale'
$api1Port = if ($env:SCALE_API1_PORT) { $env:SCALE_API1_PORT } else { '8081' }
$api2Port = if ($env:SCALE_API2_PORT) { $env:SCALE_API2_PORT } else { '8082' }
$nginxPort = if ($env:SCALE_NGINX_PORT) { $env:SCALE_NGINX_PORT } else { '8080' }

foreach ($port in @($api1Port, $api2Port)) {
    $response = Invoke-WebRequest `
        -Uri "http://127.0.0.1:$port/actuator/health/readiness" `
        -TimeoutSec 5 `
        -UseBasicParsing
    if ($response.StatusCode -ne 200) {
        throw "API on port $port is not ready: HTTP $($response.StatusCode)"
    }
}

$upstreams = @()
Get-Command curl.exe -ErrorAction Stop | Out-Null
for ($request = 1; $request -le $RequestCount; $request++) {
    # Separate client connections make nginx round-robin observable and avoid
    # the response cache used by Windows PowerShell 5.1.
    $headers = & curl.exe `
        --silent `
        --show-error `
        --dump-header - `
        --output NUL `
        --header 'Connection: close' `
        "http://127.0.0.1:$nginxPort/api/v1/movies?page=1&pageSize=20&verify=$request"
    if ($LASTEXITCODE -ne 0) {
        throw "nginx request $request failed with curl exit code $LASTEXITCODE"
    }
    $statusLine = @($headers | Where-Object { $_ -match '^HTTP/' })[-1]
    $upstreamLine = @($headers | Where-Object { $_ -match '^(?i)X-Cinema-Upstream:' })[-1]
    $upstream = ($upstreamLine -replace '^(?i)X-Cinema-Upstream:\s*', '').Trim()
    if ($statusLine -notmatch '^HTTP/\S+ 200(?:\s|$)' -or -not $upstream) {
        throw "nginx request $request did not return HTTP 200 with X-Cinema-Upstream"
    }
    $upstreams += $upstream
    Write-Host "request $request -> $upstream"
}

$distinctUpstreams = @($upstreams | Sort-Object -Unique)
if ($distinctUpstreams.Count -lt 2) {
    throw "Expected both API instances, observed: $($distinctUpstreams -join ', ')"
}

$blockedActuatorPaths = @('/actuator', '/actuator/health')
foreach ($path in $blockedActuatorPaths) {
    $actuatorStatus = $null
    try {
        $actuatorResponse = Invoke-WebRequest `
            -Uri "http://127.0.0.1:$nginxPort$path" `
            -TimeoutSec 5 `
            -UseBasicParsing
        $actuatorStatus = $actuatorResponse.StatusCode
    } catch {
        if ($_.Exception.Response) {
            $actuatorStatus = [int]$_.Exception.Response.StatusCode
        } else {
            throw
        }
    }
    if ($actuatorStatus -ne 404) {
        throw "Expected nginx to hide $path with HTTP 404, got HTTP $actuatorStatus"
    }
}

$redisReply = (& docker compose `
    --project-name $projectName `
    --file $composeFile `
    exec -T redis redis-cli ping).Trim()
if ($LASTEXITCODE -ne 0 -or $redisReply -ne 'PONG') {
    throw "Redis health check failed: $redisReply"
}

Write-Host "Scale verification passed: $($distinctUpstreams.Count) API instances, actuator hidden, Redis PONG."
