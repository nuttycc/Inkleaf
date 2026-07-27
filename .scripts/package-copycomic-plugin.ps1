[CmdletBinding()]
param(
    [string]$OutputPath = "plugin-fixtures/dist/copycomic-plugin.zip"
)

$ErrorActionPreference = "Stop"

function Initialize-SafeOutputDirectory {
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $TargetDirectory
    )

    if (-not (Test-Path -LiteralPath $Root)) {
        New-Item -ItemType Directory -Path $Root | Out-Null
    }
    $current = $Root
    $relative = $TargetDirectory.Substring($Root.Length).TrimStart('\', '/')
    $segments = @($relative -split '[\\/]' | Where-Object { $_ })
    foreach ($segment in @('') + $segments) {
        if ($segment) { $current = Join-Path $current $segment }
        if (-not (Test-Path -LiteralPath $current)) {
            New-Item -ItemType Directory -Path $current | Out-Null
        }
        $item = Get-Item -Force -LiteralPath $current
        if (-not $item.PSIsContainer -or
            ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Output directory must not contain a reparse point: $current"
        }
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$source = Join-Path $repoRoot "plugin-fixtures/copycomic"
$distRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot "plugin-fixtures/dist"))
$outputCandidate =
    if ([IO.Path]::IsPathRooted($OutputPath)) { $OutputPath }
    else { Join-Path $repoRoot $OutputPath }
$output = [IO.Path]::GetFullPath($outputCandidate)
$distPrefix = $distRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($distPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    [IO.Path]::GetExtension($output) -ne ".zip") {
    throw "OutputPath must be a .zip file below $distRoot"
}
$outputDirectory = Split-Path -Parent $output
Initialize-SafeOutputDirectory -Root $distRoot -TargetDirectory $outputDirectory
if (Test-Path -LiteralPath $output) {
    $outputItem = Get-Item -Force -LiteralPath $output
    if ($outputItem.PSIsContainer -or
        ($outputItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Output file must not be a directory or reparse point: $output"
    }
}

if (-not (Test-Path (Join-Path $source "manifest.json")) -or
    -not (Test-Path (Join-Path $source "main.js"))) {
    throw "CopyComic plugin source is incomplete: $source"
}

if (Test-Path $output) { Remove-Item -LiteralPath $output -Force }

Push-Location $source
try {
    Compress-Archive -Path "manifest.json", "main.js" -DestinationPath $output -CompressionLevel Optimal
}
finally {
    Pop-Location
}

Write-Output "Created $output"
