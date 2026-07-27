[CmdletBinding()]
param(
    [string]$OutputPath = "plugin-fixtures/dist/inkleaf-fixture-plugin.zip"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "plugin-package-common.ps1")

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$source = Join-Path $repoRoot "plugin-fixtures/inkleaf-fixture"
$output = Resolve-SafePluginPackageOutputPath -RepoRoot $repoRoot -OutputPath $OutputPath

if (-not (Test-Path (Join-Path $source "manifest.json")) -or
    -not (Test-Path (Join-Path $source "main.js"))) {
    throw "Fixture source is incomplete: $source"
}

if (Test-Path -LiteralPath $output) { Remove-Item -LiteralPath $output -Force }

Push-Location $source
try {
    Compress-Archive -Path "manifest.json", "main.js", "assets" -DestinationPath $output -CompressionLevel Optimal
}
finally {
    Pop-Location
}

Write-Output "Created $output"
