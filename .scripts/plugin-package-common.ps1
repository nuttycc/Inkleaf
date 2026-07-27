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

function Resolve-SafePluginPackageOutputPath {
    param(
        [Parameter(Mandatory)] [string] $RepoRoot,
        [Parameter(Mandatory)] [string] $OutputPath
    )

    $distRoot = [IO.Path]::GetFullPath((Join-Path $RepoRoot "plugin-fixtures/dist"))
    $outputCandidate =
        if ([IO.Path]::IsPathRooted($OutputPath)) { $OutputPath }
        else { Join-Path $RepoRoot $OutputPath }
    $output = [IO.Path]::GetFullPath($outputCandidate)
    $distPrefix =
        $distRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
            [IO.Path]::DirectorySeparatorChar
    if (-not $output.StartsWith($distPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetExtension($output) -ne ".zip") {
        throw "OutputPath must be a .zip file below $distRoot"
    }

    Initialize-SafeOutputDirectory -Root $distRoot -TargetDirectory (Split-Path -Parent $output)
    if (Test-Path -LiteralPath $output) {
        $outputItem = Get-Item -Force -LiteralPath $output
        if ($outputItem.PSIsContainer -or
            ($outputItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Output file must not be a directory or reparse point: $output"
        }
    }
    return $output
}
