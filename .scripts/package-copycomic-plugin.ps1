[CmdletBinding()]
param(
    [string]$OutputPath = "plugin-fixtures/dist/copycomic.inkleaf-plugin"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot ".."))
$source = Join-Path $repoRoot "plugin-fixtures/copycomic"
$output = Join-Path $repoRoot $OutputPath
$outputDirectory = Split-Path -Parent $output

if (-not (Test-Path (Join-Path $source "manifest.json")) -or
    -not (Test-Path (Join-Path $source "main.js"))) {
    throw "CopyComic plugin source is incomplete: $source"
}

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
if (Test-Path $output) { Remove-Item -LiteralPath $output -Force }

Push-Location $source
try {
    Compress-Archive -Path "manifest.json", "main.js" -DestinationPath $output -CompressionLevel Optimal
}
finally {
    Pop-Location
}

Write-Output "Created $output"
