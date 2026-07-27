[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$Plugin,
    [string]$OutputPath
)

# Builds a plugin whose source is split across shared/runtime.js and <plugin>/source.js.
#
# A plugin package may contain exactly one main.js at its root -- the archive validator rejects
# anything else -- so sharing code between plugins has to happen at build time. This concatenates
# the shared helpers with the source's own file and wraps the result in a single IIFE.
#
# The generated main.js is written under dist/build/ rather than into the source directory, so it
# is never mistaken for hand-written code and never drifts out of sync in a commit.

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "plugin-package-common.ps1")

if ($Plugin -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') {
    throw "Plugin name must be a simple directory name: $Plugin"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$source = Join-Path $repoRoot "plugin-fixtures/$Plugin"
$runtimeFile = Join-Path $repoRoot "plugin-fixtures/shared/runtime.js"
$sourceFile = Join-Path $source "source.js"
$manifestFile = Join-Path $source "manifest.json"

foreach ($required in @($runtimeFile, $sourceFile, $manifestFile)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Missing required input: $required"
    }
}

if (-not $OutputPath) { $OutputPath = "plugin-fixtures/dist/$Plugin-plugin.zip" }

$distRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot "plugin-fixtures/dist"))
$output = Resolve-SafePluginPackageOutputPath -RepoRoot $repoRoot -OutputPath $OutputPath

$buildDirectory = Join-Path $distRoot "build/$Plugin"
Initialize-SafeOutputDirectory -Root $distRoot -TargetDirectory $buildDirectory

$runtimeText = Get-Content -LiteralPath $runtimeFile -Raw
$sourceText = Get-Content -LiteralPath $sourceFile -Raw

$builder = [Text.StringBuilder]::new()
[void]$builder.AppendLine("// GENERATED FILE -- do not edit.")
[void]$builder.AppendLine("// Built by .scripts/build-plugin.ps1 from:")
[void]$builder.AppendLine("//   plugin-fixtures/shared/runtime.js")
[void]$builder.AppendLine("//   plugin-fixtures/$Plugin/source.js")
[void]$builder.AppendLine("(function () {")
[void]$builder.AppendLine('  "use strict";')
[void]$builder.AppendLine($runtimeText)
[void]$builder.AppendLine($sourceText)
[void]$builder.AppendLine("})();")

$mainFile = Join-Path $buildDirectory "main.js"
# UTF-8 without a BOM: the host reads main.js as text and a BOM would land inside the eval'd script.
[IO.File]::WriteAllText($mainFile, $builder.ToString(), [Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath $manifestFile -Destination (Join-Path $buildDirectory "manifest.json") -Force

$assets = Join-Path $source "assets"
$entries = @("manifest.json", "main.js")
if (Test-Path -LiteralPath $assets) {
    Copy-Item -LiteralPath $assets -Destination $buildDirectory -Recurse -Force
    $entries += "assets"
}

if (Test-Path -LiteralPath $output) { Remove-Item -LiteralPath $output -Force }

Push-Location $buildDirectory
try {
    Compress-Archive -Path $entries -DestinationPath $output -CompressionLevel Optimal
}
finally {
    Pop-Location
}

Write-Output "Created $output"
