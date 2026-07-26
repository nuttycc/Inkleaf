[CmdletBinding()]
param(
    [string]$OutputPath = "plugin-fixtures/dist/inkleaf-fixture-plugin.zip"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot ".."))
$source = Join-Path $repoRoot "plugin-fixtures/inkleaf-fixture"
$output = Join-Path $repoRoot $OutputPath
$outputDirectory = Split-Path -Parent $output

if (-not (Test-Path (Join-Path $source "manifest.json")) -or
    -not (Test-Path (Join-Path $source "main.js"))) {
    throw "Fixture source is incomplete: $source"
}

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
if (Test-Path $output) { Remove-Item -LiteralPath $output -Force }

Push-Location $source
try {
    Compress-Archive -Path "manifest.json", "main.js", "assets" -DestinationPath $output -CompressionLevel Optimal
}
finally {
    Pop-Location
}

Write-Output "Created $output"
