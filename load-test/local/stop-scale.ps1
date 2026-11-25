param(
    [switch]$RemoveVolumes
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$composeFile = Join-Path $repoRoot 'compose.scale.yaml'
$arguments = @(
    'compose',
    '--project-name', 'cinema-phase2-scale',
    '--file', $composeFile,
    'down',
    '--remove-orphans'
)
if ($RemoveVolumes) {
    $arguments += '--volumes'
}

& docker @arguments
if ($LASTEXITCODE -ne 0) {
    throw "docker compose down failed with exit code $LASTEXITCODE"
}

if ($RemoveVolumes) {
    Write-Host 'Scale demo stopped and its PostgreSQL volume was removed.'
} else {
    Write-Host 'Scale demo stopped; its PostgreSQL volume was preserved.'
}
