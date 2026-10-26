#Requires -Version 7.0
<#
.SYNOPSIS
    Package, deploy, and publish Inkleaf plugins from one cross-platform entry point.
.DESCRIPTION
    Plugin source lives at plugin-sources/<name>/src/main.js. Optional
    plugin.build.json data affects only the build; every ZIP exposes the same
    root-level manifest.json and main.js contract to the Android host.
.EXAMPLE
    ./.scripts/plugin.ps1 package --all
.EXAMPLE
    ./.scripts/plugin.ps1 deploy jmcomic --serial 192.168.1.5:5555
.EXAMPLE
    ./.scripts/plugin.ps1 publish wnacg --push
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ScriptDirectory = $PSScriptRoot
$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $ScriptDirectory '..'))
$FixturesRoot = Join-Path $RepositoryRoot 'plugin-sources'
$DistRoot = Join-Path $FixturesRoot 'dist'
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
# Native tools (git, adb) emit UTF-8; without this the console decodes them as the ANSI code page.
try { [Console]::OutputEncoding = $Utf8NoBom } catch { }

$DebugPackageId = 'com.exio.inkleaf.debug'
$PluginNamePattern = '^[A-Za-z0-9][A-Za-z0-9._-]*$'
$PackageIdPattern = '^[A-Za-z][A-Za-z0-9_.]*$'
$PackageVersionPattern = '^[A-Za-z0-9][A-Za-z0-9._+-]*$'
$PackageNamePattern = '^[A-Za-z0-9._+-]+\.zip$'
$BroadcastResultPattern = 'Broadcast completed: result=(?<code>-?\d+)(?:, data="(?<data>[^"]*)")?'

# Releases live in a separate repository, and the app reads only its main branch
# through raw.githubusercontent.com, so publishing must land on origin/main.
$PluginRepositorySlug = 'nuttycc/inkleaf-plugins'
$PluginRepositoryBranch = 'main'
$PluginRepositoryRawBase = "https://raw.githubusercontent.com/$PluginRepositorySlug"
$PluginRepositoryIndex = 'index.json'
$PluginRepositoryPackages = 'plugins'
$SourcePluginPrefix = 'io.inkleaf.source.'
# Mirrors PluginRepoContract.SUPPORTED_SCHEMA_VERSION in PluginRepoModels.kt.
$PluginRepositorySchemaVersion = 1
$IndexRootFields = @('schemaVersion', 'repoName', 'apiVersion')
$IndexEntryFields = @(
    'id', 'name', 'version', 'apiVersion', 'capabilities', 'description',
    'author', 'iconUrl', 'pkgUrl', 'sha256', 'sizeBytes'
)

function Show-Usage {
    @'
Usage: ./.scripts/plugin.ps1 <command> [options]

Commands:
  package [plugin...]     Build installable plugin ZIPs below plugin-sources/dist.
        --all             Package every discovered plugin.
        --output PATH     ZIP path for one plugin (must stay below plugin-sources/dist).
        --output-dir DIR  Output directory for multiple versioned ZIPs.

  deploy <plugin>         Package, install, and activate over ADB (debug app only).
        --serial SERIAL   ADB device serial; required when several are ready.
        --output PATH     ZIP path below plugin-sources/dist.
        --package-id ID   (default: com.exio.inkleaf.debug)

  publish <plugin...>     Build plugins, update index.json, optionally push.
        --repo-dir DIR    Clone of nuttycc/inkleaf-plugins.
        --message TEXT    Commit subject for the release.
        --push            Write, commit, and push; without it the plan is only printed.
        --force           Replace an already published version.
'@ | Write-Host
}

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

function Get-OutputPathInDirectory($Plugin, [string]$Directory) {
    $outputDirectory = if ([IO.Path]::IsPathRooted($Directory)) {
        $Directory
    } else {
        Join-Path $RepositoryRoot $Directory
    }
    return (Join-Path $outputDirectory (Get-VersionedPackageName $Plugin))
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

function New-PluginPackages([object[]]$Plugins, [string]$Output, [string]$OutputDirectory) {
    if ($null -eq $Plugins -or $Plugins.Count -eq 0) {
        throw 'Select at least one plugin to package'
    }
    if ($Output -and $Plugins.Count -ne 1) {
        throw '--output can only be used when packaging one plugin'
    }
    if ($OutputDirectory -and $Plugins.Count -eq 1) {
        throw '--output-dir can only be used when packaging multiple plugins'
    }

    $packages = [System.Collections.Generic.List[string]]::new()
    $failures = [System.Collections.Generic.List[string]]::new()
    foreach ($plugin in $Plugins) {
        $pluginOutput = $Output
        if ($OutputDirectory) {
            $pluginOutput = Get-OutputPathInDirectory $plugin $OutputDirectory
        }
        try {
            $packages.Add((New-PluginPackage $plugin $pluginOutput))
        } catch {
            $failures.Add("$($plugin.Name): $($_.Exception.Message)")
        }
    }
    if ($failures.Count -gt 0) {
        $succeeded = ($packages | ForEach-Object { Split-Path -Leaf $_ }) -join ', '
        if (-not $succeeded) { $succeeded = 'none' }
        throw "Some plugins failed to package (succeeded: ${succeeded}):`n  - " + ($failures -join "`n  - ")
    }
    return $packages.ToArray()
}

# --- git and the plugin repository --------------------------------------

function Invoke-Git([string]$Repository, [string[]]$GitArguments, [bool]$Check = $true) {
    $merged = & git -C $Repository @GitArguments 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $text = (($merged -join "`n").TrimEnd())
    if ($Check -and $code -ne 0) {
        $summary = if ($text) { $text } else { $GitArguments -join ' ' }
        throw "Git command failed ($code): $summary"
    }
    return [pscustomobject]@{ ExitCode = $code; Output = $text }
}

function Get-RepositorySlug([string]$RemoteUrl) {
    $match = [regex]::Match($RemoteUrl, '[/:](?<owner>[^/]+)[/:](?<repo>[^/]+?)(?:\.git)?/?$')
    if (-not $match.Success) {
        throw "Cannot read a GitHub slug from remote: $RemoteUrl"
    }
    return "$($match.Groups['owner'].Value)/$($match.Groups['repo'].Value)"
}

function Resolve-PluginRepository([string]$Value) {
    $configured = if ($Value) { $Value } else { $env:INKLEAF_PLUGIN_REPO }
    $candidate = if ($configured) {
        $configured
    } else {
        Join-Path (Split-Path -Parent $RepositoryRoot) ($PluginRepositorySlug.Split('/')[-1])
    }
    $candidate = [IO.Path]::GetFullPath($candidate)
    if (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
        throw "Plugin repository clone not found: $candidate`n" +
            "Clone it once, or point --repo-dir at an existing clone:`n" +
            "  git clone https://github.com/$PluginRepositorySlug.git `"$candidate`""
    }
    Assert-PlainDirectory $candidate
    $remote = (Invoke-Git $candidate @('remote', 'get-url', 'origin') $false).Output.Trim()
    if (-not $remote -or (Get-RepositorySlug $remote) -ne $PluginRepositorySlug) {
        throw "$candidate is not a clone of $PluginRepositorySlug (origin: $(if ($remote) { $remote } else { 'none' }))"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $candidate $PluginRepositoryIndex) -PathType Leaf)) {
        throw "Plugin repository has no ${PluginRepositoryIndex}: $candidate"
    }
    return $candidate
}

# A package published off a side branch would never be served by its raw URL.
function Initialize-PluginRepository([string]$Repository) {
    $branch = (Invoke-Git $Repository @('rev-parse', '--abbrev-ref', 'HEAD')).Output.Trim()
    if ($branch -ne $PluginRepositoryBranch) {
        throw "Plugin repository must be on $PluginRepositoryBranch, found ${branch}: $Repository"
    }
    $status = (Invoke-Git $Repository @('status', '--porcelain') $false).Output
    if ($status) {
        throw "Plugin repository has uncommitted changes: $Repository"
    }
    Invoke-Git $Repository @('fetch', 'origin', $branch) | Out-Null
    $local = (Invoke-Git $Repository @('rev-parse', 'HEAD')).Output.Trim()
    $remote = (Invoke-Git $Repository @('rev-parse', "origin/$branch")).Output.Trim()
    if ($local -ne $remote) {
        throw "Plugin repository does not match origin/${branch}; sync it first: $Repository"
    }
}

# Recorded per release: copycomic 0.4.3 shipped from a dirty tree and matched no commit.
function Assert-CommittedSource($Plugin) {
    $relative = [IO.Path]::GetRelativePath($RepositoryRoot, $Plugin.Root) -replace '\\', '/'
    $status = (Invoke-Git $RepositoryRoot @('status', '--porcelain', '--', $relative) $false).Output
    if ($status) {
        throw "${relative} has uncommitted changes; commit them before publishing $($Plugin.Name)"
    }
    return (Invoke-Git $RepositoryRoot @('rev-parse', 'HEAD')).Output.Trim()
}

function Get-VersionKey([string]$Version) {
    $parts = $Version.Split('.')
    if ($parts.Count -eq 0 -or @($parts | Where-Object { $_ -notmatch '^\d+$' }).Count -gt 0) {
        throw "Cannot compare plugin version '${Version}' (expected dotted numbers)"
    }
    return ,([long[]]@($parts | ForEach-Object { [long]$_ }))
}

function Compare-VersionKey([long[]]$Left, [long[]]$Right) {
    $shared = [Math]::Min($Left.Length, $Right.Length)
    for ($index = 0; $index -lt $shared; $index++) {
        if ($Left[$index] -ne $Right[$index]) {
            return $Left[$index].CompareTo($Right[$index])
        }
    }
    return $Left.Length.CompareTo($Right.Length)
}

# --- index ---------------------------------------------------------------

function New-IndexEntry($Plugin, [string]$PackageName, [byte[]]$Package) {
    $manifest = $Plugin.Manifest
    $displayName = if ($manifest['name']) { [string]$manifest['name'] } else { $Plugin.Name }
    $entry = [ordered]@{
        id      = [string]$manifest['id']
        name    = $displayName
        version = $Plugin.Version
    }
    foreach ($field in @('apiVersion', 'capabilities', 'description')) {
        if ($null -ne $manifest[$field]) {
            $entry[$field] = $manifest[$field]
        }
    }
    $author = $manifest['author']
    if ($author -is [System.Collections.IDictionary]) {
        $author = $author['name']
    }
    if ($author -is [string] -and -not [string]::IsNullOrWhiteSpace($author)) {
        $entry['author'] = $author
    }
    $entry['pkgUrl'] = "$PluginRepositoryRawBase/$PluginRepositoryBranch/$PluginRepositoryPackages/$PackageName"
    $entry['sha256'] = Get-Sha256Hex $Package
    $entry['sizeBytes'] = [long]$Package.Length
    return $entry
}

function Read-PluginIndex([string]$Repository) {
    $path = Join-Path $Repository $PluginRepositoryIndex
    try {
        $raw = Get-Content -LiteralPath $path -Raw -Encoding utf8
        $index = $raw | ConvertFrom-Json -AsHashtable
    } catch {
        throw "Invalid plugin repository index at ${path}: $($_.Exception.Message)"
    }
    if ($index -isnot [System.Collections.IDictionary]) {
        throw "Plugin repository index must be a JSON object: $path"
    }
    if ($index['schemaVersion'] -ne $PluginRepositorySchemaVersion) {
        throw "Unsupported plugin index schemaVersion: $($index['schemaVersion'])"
    }
    $pluginsNode = $index['plugins']
    if ($null -eq $pluginsNode -or $pluginsNode -is [System.Collections.IDictionary] -or @($pluginsNode).Count -eq 0) {
        throw "Plugin repository index lists no plugins: $path"
    }
    return [pscustomobject]@{ Raw = $raw; Index = $index }
}

function Get-IndexEntries($Index) {
    $entries = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in @($Index['plugins'])) {
        if ($entry -isnot [System.Collections.IDictionary]) {
            throw 'Every plugin index entry must be a JSON object'
        }
        $entries.Add($entry)
    }
    return ,$entries
}

function Assert-IndexPackages([string]$Repository, $Entries, [System.Collections.IDictionary]$Pending = @{}) {
    $seen = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($entry in $Entries) {
        $pluginId = [string]$entry['id']
        $packageUrl = [string]$entry['pkgUrl']
        $required = @($pluginId, [string]$entry['name'], [string]$entry['version'])
        if (@($required | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -gt 0 -or
            -not $packageUrl.StartsWith('https://')) {
            throw "Plugin index entry is missing a required field: $(if ($pluginId) { $pluginId } else { '<unnamed>' })"
        }
        if (-not $seen.Add($pluginId)) {
            throw "Duplicate plugin index entry: $pluginId"
        }
        $packageName = ($packageUrl -split '/')[-1]
        if ($packageName -notmatch $PackageNamePattern) {
            throw "Plugin index entry has an unusable package name: $packageUrl"
        }
        if ($Pending.Contains($packageName)) {
            $data = $Pending[$packageName]
        } else {
            $path = Join-Path $Repository $PluginRepositoryPackages $packageName
            if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
                throw "Plugin index entry has no package file: $path"
            }
            $data = [IO.File]::ReadAllBytes($path)
        }
        if ([string]$entry['sha256'] -ne (Get-Sha256Hex $data)) {
            throw "Plugin index sha256 does not match $packageName"
        }
        if ([long]$entry['sizeBytes'] -ne [long]$data.Length) {
            throw "Plugin index sizeBytes does not match $packageName"
        }
    }
}

function Add-IndexEntry([System.Collections.Generic.List[object]]$Entries, $Entry) {
    for ($position = 0; $position -lt $Entries.Count; $position++) {
        if ($Entries[$position]['id'] -eq $Entry['id']) {
            $Entries[$position] = $Entry
            return 'updated'
        }
    }
    # The fixture source is a development tool, so it stays below the real sources.
    $insertAt = -1
    for ($position = 0; $position -lt $Entries.Count; $position++) {
        if (-not ([string]$Entries[$position]['id']).StartsWith($SourcePluginPrefix)) {
            $insertAt = $position
            break
        }
    }
    if ($insertAt -lt 0) { $insertAt = $Entries.Count }
    $Entries.Insert($insertAt, $Entry)
    return 'added'
}

function ConvertTo-JsonScalar($Value) {
    if ($null -eq $Value) { return 'null' }
    if ($Value -is [bool]) { if ($Value) { return 'true' } else { return 'false' } }
    if ($Value -is [string]) { return (ConvertTo-Json -Compress -InputObject $Value) }
    if ($Value -is [System.Collections.IDictionary]) {
        return (ConvertTo-Json -Compress -Depth 20 -InputObject $Value)
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        $parts = @($Value | ForEach-Object { ConvertTo-JsonScalar $_ })
        return '[' + ($parts -join ', ') + ']'
    }
    return (ConvertTo-Json -Compress -InputObject $Value)
}

function Format-IndexEntry($Entry) {
    $fields = [System.Collections.Generic.List[string]]::new()
    foreach ($field in $IndexEntryFields) {
        if ($Entry.Contains($field)) { $fields.Add($field) }
    }
    foreach ($field in $Entry.Keys) {
        if ($IndexEntryFields -notcontains $field) { $fields.Add($field) }
    }
    $lines = foreach ($field in $fields) {
        '      ' + (ConvertTo-JsonScalar $field) + ': ' + (ConvertTo-JsonScalar $Entry[$field])
    }
    return "    {`n" + ($lines -join ",`n") + "`n    }"
}

function Format-PluginIndex($Index, $Entries) {
    if ($Entries.Count -eq 0) {
        throw 'The plugin index must list at least one plugin'
    }
    $fields = [System.Collections.Generic.List[string]]::new()
    foreach ($field in $IndexRootFields) {
        if ($Index.Contains($field)) { $fields.Add($field) }
    }
    foreach ($field in $Index.Keys) {
        if ($field -ne 'plugins' -and $IndexRootFields -notcontains $field) { $fields.Add($field) }
    }
    $chunks = foreach ($field in $fields) {
        '  ' + (ConvertTo-JsonScalar $field) + ': ' + (ConvertTo-JsonScalar $Index[$field])
    }
    $renderedEntries = ($Entries | ForEach-Object { Format-IndexEntry $_ }) -join ",`n"
    $body = @($chunks) + @("  `"plugins`": [`n$renderedEntries`n  ]")
    return "{`n" + ($body -join ",`n") + "`n}`n"
}

function Show-IndexDiff([string]$Original, [string]$Rendered) {
    if ($Original -eq $Rendered) {
        Write-Host "[info] $PluginRepositoryIndex is already up to date"
        return
    }
    $tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('inkleaf-index-' + [guid]::NewGuid().ToString('N'))
    try {
        $null = [IO.Directory]::CreateDirectory((Join-Path $tempRoot 'a'))
        $null = [IO.Directory]::CreateDirectory((Join-Path $tempRoot 'b'))
        Write-TextFile (Join-Path $tempRoot 'a/index.json') $Original
        Write-TextFile (Join-Path $tempRoot 'b/index.json') $Rendered
        $diff = & git -C $tempRoot diff --no-index --no-prefix --unified=3 -- a/index.json b/index.json 2>&1 |
            ForEach-Object { "$_" } |
            Where-Object { -not $_.StartsWith('diff --git ') -and -not $_.StartsWith('index ') }
        $text = (($diff -join "`n").TrimEnd())
        if ($text) { Write-Host $text }
    } finally {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# --- hashing and HTTP ----------------------------------------------------

function Get-Sha256Hex([byte[]]$Data) {
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Data)).ToLowerInvariant()
}

function Get-HttpsBytes([string]$Url) {
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(30)
    try {
        $null = $client.DefaultRequestHeaders.Add('User-Agent', 'inkleaf-plugin-tool')
        $bytes = $client.GetByteArrayAsync($Url).GetAwaiter().GetResult()
        return ,$bytes
    } catch {
        throw "Download failed: $Url ($($_.Exception.GetBaseException().Message))"
    } finally {
        $client.Dispose()
    }
}

# raw.githubusercontent.com and the app both cache, so a stale read is a note, not a failure.
function Test-PublishedPackages($Entries) {
    foreach ($entry in $Entries) {
        $url = [string]$entry['pkgUrl']
        try {
            $served = Get-HttpsBytes $url
        } catch {
            Write-Host "[warn] $($_.Exception.Message)" -ForegroundColor Yellow
            continue
        }
        if ((Get-Sha256Hex $served) -eq [string]$entry['sha256']) {
            Write-Host "[ok] $($entry['id'])@$($entry['version']) is live ($($served.Length) bytes)" -ForegroundColor Green
        } else {
            Write-Host "[warn] $url is not yet served with the published bytes." -ForegroundColor Yellow
        }
    }
    $servedIds = @()
    try {
        $indexBytes = Get-HttpsBytes "$PluginRepositoryRawBase/$PluginRepositoryBranch/$PluginRepositoryIndex"
        $index = [Text.Encoding]::UTF8.GetString($indexBytes) | ConvertFrom-Json -AsHashtable
        $servedIds = @(@($index['plugins']) | ForEach-Object { [string]$_['id'] })
    } catch {
        $servedIds = @()
    }
    $missing = @($Entries | Where-Object { $servedIds -notcontains [string]$_['id'] } |
        ForEach-Object { [string]$_['id'] })
    if ($missing.Count -gt 0) {
        Write-Host "[info] The index still reads the previous copy (GitHub CDN ~5 minutes, app cache 4 hours): $($missing -join ', ')"
    }
}

# --- publish -------------------------------------------------------------

function Publish-Plugins([object[]]$Plugins, [string]$RepositoryValue, [string]$Message, [bool]$Push, [bool]$Force) {
    if ($null -eq $Plugins -or $Plugins.Count -eq 0) {
        throw 'Select at least one plugin to publish'
    }
    $repository = Resolve-PluginRepository $RepositoryValue
    Initialize-PluginRepository $repository
    $loaded = Read-PluginIndex $repository
    $original = $loaded.Raw
    $index = $loaded.Index
    $entries = Get-IndexEntries $index
    Assert-IndexPackages $repository $entries

    $pending = [ordered]@{}
    $published = [System.Collections.Generic.List[object]]::new()
    $sourceCommit = ''
    foreach ($plugin in $Plugins) {
        $sourceCommit = Assert-CommittedSource $plugin
        $packagePath = New-PluginPackage $plugin $null
        $packageName = Split-Path -Leaf $packagePath
        $data = [IO.File]::ReadAllBytes($packagePath)
        $entry = New-IndexEntry $plugin $packageName $data
        $previous = $entries | Where-Object { $_['id'] -eq $entry['id'] } | Select-Object -First 1
        if ($null -ne $previous -and -not $Force) {
            $publishedVersion = [string]$previous['version']
            if ((Compare-VersionKey (Get-VersionKey $publishedVersion) (Get-VersionKey $plugin.Version)) -gt 0) {
                throw "$($plugin.Name): the index already advertises the newer $publishedVersion"
            }
            if ($publishedVersion -eq $plugin.Version) {
                if ([string]$previous['sha256'] -ne $entry['sha256']) {
                    throw "$($plugin.Name) $($plugin.Version) is already published with different content; bump the version so installs can tell them apart"
                }
                throw "$($plugin.Name) $($plugin.Version) is already published and unchanged"
            }
        }
        $staged = Join-Path $repository $PluginRepositoryPackages $packageName
        if ((Test-Path -LiteralPath $staged -PathType Leaf) -and -not $Force) {
            if ((Get-Sha256Hex ([IO.File]::ReadAllBytes($staged))) -ne (Get-Sha256Hex $data)) {
                throw "$staged already holds different bytes; pass --force to replace it"
            }
        }

        $action = Add-IndexEntry $entries $entry
        $pending[$packageName] = $data
        $published.Add($entry)
        Write-Host "[info] $($entry['id'])@$($entry['version']) $action ($($data.Length) bytes)"
    }

    Assert-IndexPackages $repository $entries $pending
    $rendered = Format-PluginIndex $index $entries
    Show-IndexDiff $original $rendered
    if (-not $Push) {
        Write-Host '[info] Prepared only. Re-run with --push to commit and publish.'
        return
    }

    Write-TextFile (Join-Path $repository $PluginRepositoryIndex) $rendered
    foreach ($packageName in $pending.Keys) {
        [IO.File]::WriteAllBytes((Join-Path $repository $PluginRepositoryPackages $packageName), $pending[$packageName])
    }
    $paths = @($PluginRepositoryIndex) + @($pending.Keys | ForEach-Object { "$PluginRepositoryPackages/$_" })
    Invoke-Git $repository (@('add', '--') + $paths) | Out-Null
    if (-not $Message) {
        $Message = 'feat: publish ' + (($published | ForEach-Object { "$($_['name']) $($_['version'])" }) -join ', ')
    }
    Invoke-Git $repository @('commit', '-m', $Message, '-m', "Built from Inkleaf $sourceCommit.") | Out-Null
    Write-Host (Invoke-Git $repository @('push', 'origin', $PluginRepositoryBranch)).Output
    Test-PublishedPackages $published
}

# --- adb deploy ----------------------------------------------------------

function Get-AdbPath {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $executableName = if ($IsWindows) { 'adb.exe' } else { 'adb' }
    $candidates = [System.Collections.Generic.List[string]]::new()
    foreach ($variable in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        $value = [Environment]::GetEnvironmentVariable($variable)
        if ($value) {
            $candidates.Add((Join-Path $value 'platform-tools' $executableName))
        }
    }
    $localProperties = Join-Path $RepositoryRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        foreach ($line in [IO.File]::ReadAllLines($localProperties)) {
            if ($line -match '^\s*sdk\.dir\s*=') {
                $value = ($line -replace '^\s*sdk\.dir\s*=', '').Trim().Replace('\:', ':').Replace('\\', '\')
                $candidates.Add((Join-Path $value 'platform-tools' $executableName))
                break
            }
        }
    }
    $home = [Environment]::GetFolderPath('UserProfile')
    $candidates.Add((Join-Path $home 'AppData/Local/Android/Sdk/platform-tools' $executableName))
    $candidates.Add((Join-Path $home 'Library/Android/sdk/platform-tools' $executableName))
    $candidates.Add((Join-Path $home 'Android/Sdk/platform-tools' $executableName))
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw 'adb not found. Install Android platform-tools or configure the SDK path.'
}

function Invoke-Adb([string]$Adb, [string[]]$AdbArguments, [bool]$Check = $true) {
    $merged = & $Adb @AdbArguments 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $text = ($merged -join "`n")
    if ($Check -and $code -ne 0) {
        throw "ADB command failed ($code): $($text.Trim())"
    }
    return [pscustomobject]@{ ExitCode = $code; Output = $text }
}

function Get-AdbDevices([string]$Adb) {
    Invoke-Adb $Adb @('start-server') | Out-Null
    $result = Invoke-Adb $Adb @('devices')
    $devices = [System.Collections.Generic.List[object]]::new()
    $lines = $result.Output -split "`n"
    for ($index = 1; $index -lt $lines.Count; $index++) {
        if ($lines[$index].Trim() -match '^(\S+)\s+(\S+)') {
            $devices.Add([pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] })
        }
    }
    return $devices.ToArray()
}

function Select-AdbDevice([string]$Adb, [string]$Preferred) {
    $devices = Get-AdbDevices $Adb
    $ready = @($devices | Where-Object { $_.State -eq 'device' })
    if ($Preferred) {
        if (@($ready | Where-Object { $_.Serial -eq $Preferred }).Count -gt 0) { return $Preferred }
        $known = ($ready | ForEach-Object { $_.Serial }) -join ', '
        if (-not $known) { $known = 'none' }
        throw "ADB device '$Preferred' is not ready. Ready devices: $known"
    }
    if ($ready.Count -eq 0) {
        $seen = ($devices | ForEach-Object { "$($_.Serial)=$($_.State)" }) -join ', '
        if (-not $seen) { $seen = 'none' }
        throw "No ready ADB device. Seen devices: $seen"
    }
    if ($ready.Count -eq 1) { return $ready[0].Serial }
    $serials = ($ready | ForEach-Object { $_.Serial }) -join ', '
    throw "Multiple ADB devices are ready. Re-run with --serial. Devices: $serials"
}

function Get-ReceiverComponent([string]$PackageId) {
    return "$PackageId/com.exio.inkleaf.plugin.AdbPluginInstallReceiver"
}

function Test-InstallReceiver([string]$Adb, [string]$Device, [string]$PackageId) {
    $component = Get-ReceiverComponent $PackageId
    $result = Invoke-Adb $Adb @(
        '-s', $Device, 'shell', 'cmd', 'package', 'query-receivers',
        '--brief', '--components', '-n', $component
    ) -Check $false
    if ($result.ExitCode -ne 0) { return $false }
    return @($result.Output -split "`n" | Where-Object { $_.Trim() -eq $component }).Count -gt 0
}

function Assert-InstallReceiver([string]$Adb, [string]$Device, [string]$PackageId) {
    $package = Invoke-Adb $Adb @('-s', $Device, 'shell', 'pm', 'path', $PackageId) -Check $false
    $installed = $package.ExitCode -eq 0 -and
        @($package.Output -split "`n" | Where-Object { $_.StartsWith('package:') }).Count -gt 0
    if (-not $installed) {
        throw "Inkleaf package is not installed on the device: $PackageId"
    }
    if (Test-InstallReceiver $Adb $Device $PackageId) { return }
    $hint = ' Install a current Inkleaf build before deploying plugins.'
    if ($PackageId -ne $DebugPackageId -and (Test-InstallReceiver $Adb $Device $DebugPackageId)) {
        $hint += " The debug app is ready; re-run with --package-id $DebugPackageId."
    }
    throw "Installed Inkleaf package '$PackageId' does not expose the ADB install receiver.$hint"
}

function Start-InkleafApp([string]$Adb, [string]$Device, [string]$PackageId) {
    Write-Host "[info] Waking $PackageId so it can receive the install broadcast"
    $resolved = Invoke-Adb $Adb @(
        '-s', $Device, 'shell', 'cmd', 'package', 'resolve-activity', '--brief', $PackageId
    ) -Check $false
    $component = $null
    foreach ($line in ($resolved.Output -split "`n")) {
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith("$PackageId/")) { $component = $trimmed; break }
    }
    if ($component) {
        Invoke-Adb $Adb @('-s', $Device, 'shell', 'am', 'start', '-W', '-n', $component) | Out-Null
    } else {
        Invoke-Adb $Adb @(
            '-s', $Device, 'shell', 'monkey', '-p', $PackageId,
            '-c', 'android.intent.category.LAUNCHER', '1'
        ) | Out-Null
    }
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        Start-Sleep -Milliseconds 500
        $running = Invoke-Adb $Adb @('-s', $Device, 'shell', 'pidof', $PackageId) -Check $false
        if ($running.ExitCode -eq 0 -and $running.Output.Trim()) { return }
    }
    throw "Unable to start $PackageId; launch it manually and re-run."
}

function Send-InstallBroadcast(
    [string]$Adb, [string]$Device, [string]$PackageId, [string[]]$Extras
) {
    $result = Invoke-Adb $Adb (@(
        '-s', $Device, 'shell', 'am', 'broadcast',
        '-a', 'com.exio.inkleaf.action.ADB_INSTALL_PLUGIN',
        '-n', (Get-ReceiverComponent $PackageId),
        '--receiver-foreground', '--include-stopped-packages'
    ) + $Extras)
    $completion = [regex]::Match($result.Output, $BroadcastResultPattern)
    if (-not $completion.Success) {
        throw "Inkleaf did not return an installation result: $($result.Output.Trim())"
    }
    $detail = if ($completion.Groups['data'].Success) { $completion.Groups['data'].Value } else { '' }
    if ($completion.Groups['code'].Value -ne '-1') {
        if (-not $detail) {
            throw "The install receiver did not execute: $($result.Output.Trim())"
        }
        throw "Inkleaf rejected the plugin deployment: $detail"
    }
    return $detail
}

function Deploy-Plugin($Plugin, [string]$Serial, [string]$Output, [string]$PackageId) {
    if ($PackageId -notmatch $PackageIdPattern) {
        throw "Invalid Android package id: $PackageId"
    }
    $adb = Get-AdbPath
    Write-Host "[info] Using adb at $adb"
    $device = Select-AdbDevice $adb $Serial
    Write-Host "[info] Using ADB device $device"
    $package = New-PluginPackage $Plugin $Output

    Assert-InstallReceiver $adb $device $PackageId
    Start-InkleafApp $adb $device $PackageId

    # Command-line-safe chunks stay below both Windows and Binder limits.
    $session = [guid]::NewGuid().ToString('N')
    $data = [IO.File]::ReadAllBytes($package)
    $encoded = [Convert]::ToBase64String($data)
    $common = @('--es', 'session', $session)
    $chunkSize = 12 * 1024
    $chunkCount = [int][Math]::Ceiling($encoded.Length / [double]$chunkSize)
    Write-Host "[info] Starting ADB installation in $PackageId"
    Write-Host "[info] Sending payload in $chunkCount chunk(s)"
    Send-InstallBroadcast $adb $device $PackageId (@($common) + @('--es', 'operation', 'begin')) | Out-Null
    for ($offset = 0; $offset -lt $encoded.Length; $offset += $chunkSize) {
        $length = [Math]::Min($chunkSize, $encoded.Length - $offset)
        Send-InstallBroadcast $adb $device $PackageId (@($common) + @(
            '--es', 'operation', 'append', '--es', 'payload', $encoded.Substring($offset, $length)
        )) | Out-Null
    }
    $installResult = Send-InstallBroadcast $adb $device $PackageId (@($common) + @(
        '--es', 'operation', 'commit',
        '--es', 'expectedPluginId', $Plugin.Id,
        '--es', 'expectedVersion', $Plugin.Version
    ))
    Write-Host "[ok] Plugin installed and activated: $installResult"
}

# --- entry point ---------------------------------------------------------

$ScriptAction = $null
$ScriptArguments = [string[]]@()
if ($args.Count -gt 0) {
    $ScriptAction = [string]$args[0]
    if ($args.Count -gt 1) {
        $ScriptArguments = [string[]]@($args[1..($args.Count - 1)])
    }
}

$OptionValues = @{
    'package' = @('output', 'output-dir')
    'deploy'  = @('serial', 'output', 'package-id')
    'publish' = @('repo-dir', 'message')
}
$OptionFlags = @{
    'package' = @('all')
    'deploy'  = @()
    'publish' = @('push', 'force')
}

function Invoke-Main {
    if (-not $ScriptAction -or $ScriptAction -in @('-h', '--help', 'help')) {
        Show-Usage
        return
    }
    if (-not $OptionValues.ContainsKey($ScriptAction)) {
        throw "unrecognized action: $ScriptAction"
    }

    $options = @{}
    $positional = [System.Collections.Generic.List[string]]::new()
    $index = 0
    while ($index -lt $ScriptArguments.Count) {
        $token = [string]$ScriptArguments[$index]
        if ($token -in @('-h', '--help')) {
            Show-Usage
            return
        }
        if ($token.StartsWith('-') -and $token.Length -gt 1) {
            $name = $token.TrimStart('-')
            $inlineValue = $null
            if ($name.Contains('=')) {
                $parts = $name.Split('=', 2)
                $name = $parts[0]
                $inlineValue = $parts[1]
            }
            if ($OptionFlags[$ScriptAction] -contains $name) {
                if ($null -ne $inlineValue) { throw "option --$name does not take a value" }
                $options[$name] = $true
            } elseif ($OptionValues[$ScriptAction] -contains $name) {
                if ($null -ne $inlineValue) {
                    $options[$name] = $inlineValue
                } else {
                    $index++
                    if ($index -ge $ScriptArguments.Count) { throw "option --$name requires a value" }
                    $options[$name] = [string]$ScriptArguments[$index]
                }
            } else {
                throw "unrecognized argument: $token"
            }
        } else {
            $positional.Add($token)
        }
        $index++
    }

    switch ($ScriptAction) {
        'package' {
            $available = Get-Plugins
            $all = [bool]$options['all']
            if ($all -and $positional.Count -gt 0) {
                throw 'Plugin names and --all cannot be used together'
            }
            if (-not $all -and $positional.Count -eq 0) {
                throw 'Specify one or more plugins, or use --all'
            }
            $selected = if ($all) { $available } else { Select-Plugins $positional.ToArray() $available }
            New-PluginPackages $selected $options['output'] $options['output-dir'] | Out-Null
        }
        'publish' {
            if ($positional.Count -eq 0) {
                throw 'Specify one or more plugins'
            }
            $available = Get-Plugins
            Publish-Plugins `
                (Select-Plugins $positional.ToArray() $available) `
                $options['repo-dir'] `
                $options['message'] `
                ([bool]$options['push']) `
                ([bool]$options['force'])
        }
        'deploy' {
            if ($positional.Count -eq 0) {
                throw 'a plugin name is required'
            }
            $plugin = Find-Plugin $positional[0]
            $packageId = if ($options.ContainsKey('package-id')) {
                $options['package-id']
            } else {
                $DebugPackageId
            }
            Deploy-Plugin $plugin $options['serial'] $options['output'] $packageId
        }
    }
}

try {
    Invoke-Main
} catch {
    [Console]::Error.WriteLine("error: $($_.Exception.Message)")
    exit 1
}
