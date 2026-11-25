param(
    [int]$RequestCount = 10,
    [string]$FixturePath = (Join-Path $PSScriptRoot '..\fixtures.e7-local.json'),
    [switch]$KeepBooking
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

if ($RequestCount -lt 2) {
    throw 'RequestCount must be at least 2 to verify multiple API instances.'
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$composeFile = Join-Path $repoRoot 'compose.scale.yaml'
$projectName = 'cinema-phase2-scale'
$nginxPort = if ($env:SCALE_NGINX_PORT) { $env:SCALE_NGINX_PORT } else { '8080' }
$resolvedFixture = (Resolve-Path -LiteralPath $FixturePath).Path
$fixture = Get-Content -Raw -LiteralPath $resolvedFixture | ConvertFrom-Json
$user = @($fixture.loadUsers)[0]

if (-not $fixture.showtimeId -or -not $user.userId -or -not $user.token -or -not $user.seatId) {
    throw "Fixture is incomplete: $resolvedFixture"
}

$key = [guid]::NewGuid().ToString()
$payload = [ordered]@{
    showtimeId = [string]$fixture.showtimeId
    seatIds = @([string]$user.seatId)
} | ConvertTo-Json -Compress
$baseUri = "http://127.0.0.1:$nginxPort"

$clients = @()
$requests = @()
$tasks = @()
for ($index = 1; $index -le $RequestCount; $index++) {
    $client = [System.Net.Http.HttpClient]::new()
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post,
        "$baseUri/api/v1/bookings"
    )
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', [string]$user.token)
    $request.Headers.Add('Idempotency-Key', $key)
    $request.Content = [System.Net.Http.StringContent]::new(
        $payload,
        [System.Text.Encoding]::UTF8,
        'application/json'
    )
    $clients += $client
    $requests += $request
    $tasks += $client.SendAsync($request)
}

$results = @()
try {
    for ($index = 0; $index -lt $tasks.Count; $index++) {
        $response = $tasks[$index].GetAwaiter().GetResult()
        try {
            $bodyText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $body = $bodyText | ConvertFrom-Json
            $upstream = if ($response.Headers.Contains('X-Cinema-Upstream')) {
                @($response.Headers.GetValues('X-Cinema-Upstream'))[0]
            } else {
                $null
            }
            $replayed = if ($response.Headers.Contains('Idempotency-Replayed')) {
                @($response.Headers.GetValues('Idempotency-Replayed'))[0]
            } else {
                $null
            }
            $results += [pscustomobject]@{
                Request = $index + 1
                Status = [int]$response.StatusCode
                BookingId = [string]$body.data.bookingId
                Upstream = $upstream
                Replayed = $replayed
            }
        } finally {
            $response.Dispose()
        }
    }
} finally {
    $requests | ForEach-Object { $_.Dispose() }
    $clients | ForEach-Object { $_.Dispose() }
}

$results | Format-Table Request, Status, BookingId, Upstream, Replayed -AutoSize

$created = @($results | Where-Object { $_.Status -eq 201 })
$replayed = @($results | Where-Object { $_.Status -eq 200 -and $_.Replayed -eq 'true' })
$bookingIds = @($results.BookingId | Where-Object { $_ } | Sort-Object -Unique)
$upstreams = @($results.Upstream | Where-Object { $_ } | Sort-Object -Unique)

if ($created.Count -ne 1 -or $replayed.Count -ne ($RequestCount - 1)) {
    throw "Expected one 201 and $($RequestCount - 1) replayed 200 responses."
}
if ($bookingIds.Count -ne 1) {
    throw "Expected every response to reference one booking ID, observed: $($bookingIds -join ', ')"
}
if ($upstreams.Count -lt 2) {
    throw "Expected requests through both API instances, observed: $($upstreams -join ', ')"
}

$recordCount = (& docker compose `
    --project-name $projectName `
    --file $composeFile `
    exec -T db psql -U cinema -d cinema -tA -c "SELECT count(*) FROM booking_idempotency WHERE user_id = '$($user.userId)' AND idempotency_key = '$key' AND booking_id IS NOT NULL;").Trim()
if ($LASTEXITCODE -ne 0 -or $recordCount -ne '1') {
    throw "Expected one committed booking_idempotency record, observed: $recordCount"
}

if (-not $KeepBooking) {
    $client = [System.Net.Http.HttpClient]::new()
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Delete,
        "$baseUri/api/v1/bookings/$($bookingIds[0])"
    )
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', [string]$user.token)
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            if ([int]$response.StatusCode -ne 204) {
                throw "Cleanup cancellation returned HTTP $([int]$response.StatusCode)"
            }
        } finally {
            $response.Dispose()
        }
    } finally {
        $request.Dispose()
        $client.Dispose()
    }
}

Write-Host "E7 multi-instance verification passed: key=$key, bookingId=$($bookingIds[0]), upstreams=$($upstreams -join ', ')."
