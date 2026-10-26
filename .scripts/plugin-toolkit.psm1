#Requires -Version 7.0
<#
.SYNOPSIS
    Shared discovery and packaging helpers for the plugin scripts.
.DESCRIPTION
    Imported by install-plugins.ps1 and publish-plugins.ps1. Not meant to run on its own.

    Plugin source lives at plugin-sources/<name>/src/main.js. Optional
    plugin.build.json data affects only the build; every ZIP exposes the same
    root-level manifest.json and main.js contract to the Android host.
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ScriptDirectory = $PSScriptRoot
$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $ScriptDirectory '..'))
$FixturesRoot = Join-Path $RepositoryRoot 'plugin-sources'
$DistRoot = Join-Path $FixturesRoot 'dist'
$Utf8NoBom = [Text.UTF8Encoding]::new($false)

$PluginNamePattern = '^[A-Za-z0-9][A-Za-z0-9._-]*$'
$PackageVersionPattern = '^[A-Za-z0-9][A-Za-z0-9._+-]*$'

# --- paths ---------------------------------------------------------------

function Test-IsLink([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    if ($null -eq $item) { return $false }
    return [bool]($item.Attributes -band [IO.FileAttributes]::ReparsePoint)
}

function Test-PathBelow([string]$Path, [string]$Root) {
    $relative = [IO.Path]::GetRelativePath($Root, $Path)
    if ([IO.Path]::IsPathRooted($relative)) { return $false }
    $normalized = $relative.Replace('\', '/')
    return $normalized -ne '..' -and -not $normalized.StartsWith('../')
}

function Get-PathSegments([string]$Path, [string]$Root) {
    $relative = [IO.Path]::GetRelativePath($Root, $Path)
    if ($relative -eq '.') { return @() }
    return @($relative.Split([char[]]@('\', '/'), [StringSplitOptions]::RemoveEmptyEntries))
}

function Assert-PlainDirectory([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container) -or (Test-IsLink $Path)) {
        throw "Directory must exist and must not be a link: $Path"
    }
}

function Assert-SafeInput([string]$Path, [string]$Root, [switch]$Directory) {
    $rootAbsolute = [IO.Path]::GetFullPath($Root)
    $pathAbsolute = [IO.Path]::GetFullPath($Path)
    if (-not (Test-PathBelow $pathAbsolute $rootAbsolute)) {
        throw "Input path must remain below ${rootAbsolute}: $Path"
    }
    $current = $rootAbsolute
    foreach ($segment in (Get-PathSegments $pathAbsolute $rootAbsolute)) {
        $current = Join-Path $current $segment
        if (Test-IsLink $current) {
            throw "Plugin input path must not contain a link: $current"
        }
    }
    if ($Directory) {
        if (-not (Test-Path -LiteralPath $pathAbsolute -PathType Container)) {
            throw "Plugin input directory does not exist: $Path"
        }
    } elseif (-not (Test-Path -LiteralPath $pathAbsolute -PathType Leaf)) {
        throw "Plugin input file does not exist: $Path"
    }
}

function Ensure-SafeDirectory([string]$Path, [string]$Root) {
    $null = [IO.Directory]::CreateDirectory($Root)
    if (-not (Test-Path -LiteralPath $Root -PathType Container) -or (Test-IsLink $Root)) {
        throw "Output root must be a plain directory: $Root"
    }
    $rootAbsolute = [IO.Path]::GetFullPath($Root)
    $pathAbsolute = [IO.Path]::GetFullPath($Path)
    if (-not (Test-PathBelow $pathAbsolute $rootAbsolute)) {
        throw "Path must remain below ${rootAbsolute}: $Path"
    }
    $current = $rootAbsolute
    foreach ($segment in (Get-PathSegments $pathAbsolute $rootAbsolute)) {
        $current = Join-Path $current $segment
        if (Test-Path -LiteralPath $current) {
            if (-not (Test-Path -LiteralPath $current -PathType Container) -or (Test-IsLink $current)) {
                throw "Output directory must not contain a link: $current"
            }
        } else {
            $null = [IO.Directory]::CreateDirectory($current)
        }
    }
}

function Write-TextFile([string]$Path, [string]$Text) {
    $normalized = $Text.Replace("`r`n", "`n").Replace("`r", "`n")
    [IO.File]::WriteAllText($Path, $normalized, $Utf8NoBom)
}

function Read-JsonObject([string]$Path, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing ${Label}: $Path"
    }
    try {
        $raw = Get-Content -LiteralPath $Path -Raw -Encoding utf8
        if ([string]::IsNullOrWhiteSpace($raw)) { throw 'file is empty' }
        $value = $raw | ConvertFrom-Json -AsHashtable
    } catch {
        throw "Invalid ${Label} at ${Path}: $($_.Exception.Message)"
    }
    if ($value -isnot [System.Collections.IDictionary]) {
        throw "${Label} must contain a JSON object: $Path"
    }
    return $value
}

# --- plugin discovery ----------------------------------------------------

function Get-BuildStrategy($Plugin) {
    if ($Plugin.Runtime) { return 'shared runtime + source' }
    return 'standalone source'
}

function Get-VersionedPackageName($Plugin) {
    return "$($Plugin.Name)-plugin-v$($Plugin.Version).zip"
}

function Get-Plugin([string]$Directory) {
    $name = Split-Path -Leaf $Directory
    if ($name -notmatch $PluginNamePattern) {
        throw "Plugin directory has an unsupported name: $name"
    }
    Assert-PlainDirectory $Directory

    $manifestPath = Join-Path $Directory 'manifest.json'
    $source = Join-Path $Directory 'src' 'main.js'
    Assert-SafeInput $manifestPath $FixturesRoot
    Assert-SafeInput $source $FixturesRoot
    $manifest = Read-JsonObject $manifestPath 'plugin manifest'
    foreach ($field in @('id', 'version')) {
        $value = $manifest[$field]
        if (-not ($value -is [string]) -or [string]::IsNullOrWhiteSpace($value)) {
            throw "Manifest must declare a non-empty string '${field}': $manifestPath"
        }
    }
    if ($manifest['version'] -notmatch $PackageVersionPattern) {
        throw "Manifest version contains characters that are unsafe in a package filename: $manifestPath"
    }

    $buildPath = Join-Path $Directory 'plugin.build.json'
    $runtime = $null
    if (Test-Path -LiteralPath $buildPath -PathType Leaf) {
        Assert-SafeInput $buildPath $FixturesRoot
        $build = Read-JsonObject $buildPath 'plugin build configuration'
        $unknown = @($build.Keys | Where-Object { $_ -ne 'runtime' })
        if ($unknown.Count -gt 0) {
            $fields = ($unknown | Sort-Object) -join ', '
            throw "Unsupported plugin.build.json fields for ${name}: $fields"
        }
        $runtimeValue = $build['runtime']
        if (-not ($runtimeValue -is [string]) -or [string]::IsNullOrWhiteSpace($runtimeValue)) {
            throw "plugin.build.json must declare a non-empty 'runtime': $buildPath"
        }
        $runtime = [IO.Path]::GetFullPath((Join-Path $Directory $runtimeValue))
        Assert-SafeInput $runtime $FixturesRoot
    }

    return [pscustomobject]@{
        Name         = $name
        Root         = $Directory
        Source       = $source
        ManifestPath = $manifestPath
        Manifest     = $manifest
        Runtime      = $runtime
        Id           = [string]$manifest['id']
        Version      = [string]$manifest['version']
    }
}

function Get-Plugins {
    Assert-PlainDirectory $FixturesRoot
    $plugins = [System.Collections.Generic.List[object]]::new()
    $failures = [System.Collections.Generic.List[string]]::new()
    $directories = Get-ChildItem -LiteralPath $FixturesRoot -Directory -Force |
        Sort-Object { $_.Name.ToLowerInvariant() }
    foreach ($directory in $directories) {
        if (-not (Test-Path -LiteralPath (Join-Path $directory.FullName 'manifest.json'))) {
            continue
        }
        try {
            $plugins.Add((Get-Plugin $directory.FullName))
        } catch {
            $failures.Add($_.Exception.Message)
        }
    }
    if ($failures.Count -gt 0) {
        throw "Invalid plugin directories:`n  - " + ($failures -join "`n  - ")
    }
    if ($plugins.Count -eq 0) {
        throw "No plugins found below $FixturesRoot"
    }
    return $plugins.ToArray()
}

function Find-Plugin([string]$Name, [object[]]$Plugins = $null) {
    if ($Name -notmatch $PluginNamePattern) {
        throw "Plugin must be a simple directory name: $Name"
    }
    $available = if ($null -ne $Plugins) { $Plugins } else { Get-Plugins }
    foreach ($plugin in $available) {
        if ($plugin.Name -eq $Name) { return $plugin }
    }
    $names = ($available | ForEach-Object { $_.Name }) -join ', '
    throw "Unknown plugin '${Name}'. Available plugins: $names"
}

function Select-Plugins([string[]]$Names, [object[]]$Available) {
    $selected = [System.Collections.Generic.List[object]]::new()
    $seen = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($name in $Names) {
        if ($seen.Add($name)) {
            $selected.Add((Find-Plugin $name $Available))
        }
    }
    return $selected.ToArray()
}

# --- packaging -----------------------------------------------------------

function Resolve-OutputPath($Plugin, [string]$Value) {
    $candidate = if ([string]::IsNullOrEmpty($Value)) {
        Join-Path 'plugin-sources/dist' (Get-VersionedPackageName $Plugin)
    } else {
        $Value
    }
    if (-not [IO.Path]::IsPathRooted($candidate)) {
        $candidate = Join-Path $RepositoryRoot $candidate
    }
    $candidate = [IO.Path]::GetFullPath($candidate)
    if (-not $candidate.EndsWith('.zip', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Output path must use the .zip extension'
    }
    if (-not (Test-PathBelow $candidate $DistRoot)) {
        throw "Output path must remain below $DistRoot"
    }
    $buildRoot = [IO.Path]::GetFullPath((Join-Path $DistRoot 'build'))
    if ($candidate -eq $buildRoot -or (Test-PathBelow $candidate $buildRoot)) {
        throw "Output path must not be inside the staging directory: $buildRoot"
    }
    Ensure-SafeDirectory (Split-Path -Parent $candidate) $DistRoot
    if ((Test-Path -LiteralPath $candidate -PathType Container) -or (Test-IsLink $candidate)) {
        throw "Output file must not be a directory or link: $candidate"
    }
    return $candidate
}

function Copy-Assets([string]$Source, [string]$Destination) {
    foreach ($item in (Get-ChildItem -LiteralPath $Source -Recurse -Force)) {
        if (Test-IsLink $item.FullName) {
            throw "Plugin assets must not contain links: $($item.FullName)"
        }
        $relative = [IO.Path]::GetRelativePath($Source, $item.FullName)
        $target = Join-Path $Destination $relative
        if ($item.PSIsContainer) {
            $null = [IO.Directory]::CreateDirectory($target)
        } else {
            $null = [IO.Directory]::CreateDirectory((Split-Path -Parent $target))
            Copy-Item -LiteralPath $item.FullName -Destination $target -Force
        }
    }
}

function Stage-Plugin($Plugin) {
    $buildRoot = Join-Path $DistRoot 'build'
    Ensure-SafeDirectory $buildRoot $DistRoot
    $staging = Join-Path $buildRoot $Plugin.Name
    if (Test-Path -LiteralPath $staging) {
        if ((Test-IsLink $staging) -or -not (Test-Path -LiteralPath $staging -PathType Container)) {
            throw "Build directory must be a plain directory: $staging"
        }
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
    $null = [IO.Directory]::CreateDirectory($staging)

    Copy-Item -LiteralPath $Plugin.ManifestPath -Destination (Join-Path $staging 'manifest.json') -Force
    $sourceText = [IO.File]::ReadAllText($Plugin.Source)
    if ($Plugin.Runtime) {
        $runtimeText = [IO.File]::ReadAllText($Plugin.Runtime)
        $generated = "// GENERATED FILE -- do not edit.`n" +
            "// Built from plugin-sources/$($Plugin.Name)/src/main.js.`n" +
            "(function () {`n" +
            "  `"use strict`";`n" +
            "$runtimeText`n$sourceText`n" +
            "})();`n"
        Write-TextFile (Join-Path $staging 'main.js') $generated
    } else {
        Copy-Item -LiteralPath $Plugin.Source -Destination (Join-Path $staging 'main.js') -Force
    }

    $assets = Join-Path $Plugin.Root 'assets'
    if (Test-Path -LiteralPath $assets -PathType Container) {
        Assert-SafeInput $assets $FixturesRoot -Directory
        Copy-Assets $assets (Join-Path $staging 'assets')
    }
    return $staging
}

function New-PluginPackage($Plugin, [string]$OutputValue) {
    Write-Host "[info] Packaging $($Plugin.Name) v$($Plugin.Version) ($(Get-BuildStrategy $Plugin))"
    $output = Resolve-OutputPath $Plugin $OutputValue
    $staging = Stage-Plugin $Plugin
    $temporary = Join-Path (Split-Path -Parent $output) ('.' + (Split-Path -Leaf $output) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $names = [System.Collections.Generic.List[string]]::new()
        $names.Add('manifest.json')
        $names.Add('main.js')
        $assets = Join-Path $staging 'assets'
        if (Test-Path -LiteralPath $assets -PathType Container) {
            foreach ($file in (Get-ChildItem -LiteralPath $assets -Recurse -Force -File)) {
                $names.Add(([IO.Path]::GetRelativePath($staging, $file.FullName) -replace '\\', '/'))
            }
        }
        $names.Sort([StringComparer]::Ordinal)

        $archive = [IO.Compression.ZipFile]::Open($temporary, [IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($name in $names) {
                $entry = $archive.CreateEntry($name, [IO.Compression.CompressionLevel]::Optimal)
                # Fixed timestamps keep a rebuild of the same source byte-identical, which is
                # what lets an index sha256 assert something about plugin content at all.
                $entry.LastWriteTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
                $entry.ExternalAttributes = 0x1A40000
                $stream = $entry.Open()
                try {
                    $bytes = [IO.File]::ReadAllBytes((Join-Path $staging $name))
                    $stream.Write($bytes, 0, $bytes.Length)
                } finally {
                    $stream.Dispose()
                }
            }
        } finally {
            $archive.Dispose()
        }
        Move-Item -LiteralPath $temporary -Destination $output -Force
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
    Write-Host "[ok] Created $output"
    return $output
}

function Get-Sha256Hex([byte[]]$Data) {
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Data)).ToLowerInvariant()
}

Export-ModuleMember -Function Get-Plugins, Find-Plugin, Select-Plugins, New-PluginPackage, Get-Sha256Hex, Write-TextFile, Assert-PlainDirectory
