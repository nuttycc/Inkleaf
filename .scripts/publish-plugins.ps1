#Requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$Plugins,

    [Parameter()]
    [string]$RepoDir,

    [Parameter()]
    [string]$Message,

    [Parameter()]
    [switch]$Push,

    [Parameter()]
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false) } catch { }
Import-Module (Join-Path $PSScriptRoot 'plugin-toolkit.psm1') -Force -DisableNameChecking

$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$PackageNamePattern = '^[A-Za-z0-9._+-]+\.zip$'

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
            "Clone it once, or point -RepoDir at an existing clone:`n" +
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

# --- HTTP ----------------------------------------------------------------

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

function Publish-Plugins([object[]]$Plugins, [string]$RepositoryValue, [string]$CommitMessage, [bool]$PushRelease, [bool]$Replace) {
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
        if ($null -ne $previous -and -not $Replace) {
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
        if ((Test-Path -LiteralPath $staged -PathType Leaf) -and -not $Replace) {
            if ((Get-Sha256Hex ([IO.File]::ReadAllBytes($staged))) -ne (Get-Sha256Hex $data)) {
                throw "$staged already holds different bytes; pass -Force to replace it"
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
    if (-not $PushRelease) {
        Write-Host '[info] Prepared only. Re-run with -Push to commit and publish.'
        return
    }

    Write-TextFile (Join-Path $repository $PluginRepositoryIndex) $rendered
    foreach ($packageName in $pending.Keys) {
        [IO.File]::WriteAllBytes((Join-Path $repository $PluginRepositoryPackages $packageName), $pending[$packageName])
    }
    $paths = @($PluginRepositoryIndex) + @($pending.Keys | ForEach-Object { "$PluginRepositoryPackages/$_" })
    Invoke-Git $repository (@('add', '--') + $paths) | Out-Null
    if (-not $CommitMessage) {
        $CommitMessage = 'feat: publish ' + (($published | ForEach-Object { "$($_['name']) $($_['version'])" }) -join ', ')
    }
    Invoke-Git $repository @('commit', '-m', $CommitMessage, '-m', "Built from Inkleaf $sourceCommit.") | Out-Null
    Write-Host (Invoke-Git $repository @('push', 'origin', $PluginRepositoryBranch)).Output
    Test-PublishedPackages $published
}

try {
    if ($null -eq $Plugins -or $Plugins.Count -eq 0) {
        throw 'Specify one or more plugins, for example: ./.scripts/publish-plugins.ps1 wnacg -Push'
    }
    $available = Get-Plugins
    Publish-Plugins `
        (Select-Plugins $Plugins $available) `
        $RepoDir `
        $Message `
        ([bool]$Push) `
        ([bool]$Force)
} catch {
    [Console]::Error.WriteLine("error: $($_.Exception.Message)")
    exit 1
}

<#
.SYNOPSIS
    Build plugins, update the plugin repository index, and optionally publish.
.DESCRIPTION
    Packages each plugin with plugin-toolkit.psm1, rewrites index.json in the
    nuttycc/inkleaf-plugins clone, and refuses to publish a version that already
    exists with different content. Without -Push the plan is only printed.
.PARAMETER Plugins
    Directory names below plugin-sources.
.PARAMETER RepoDir
    Clone of nuttycc/inkleaf-plugins. Defaults to $env:INKLEAF_PLUGIN_REPO or the
    sibling of this repository.
.PARAMETER Message
    Commit subject for the release.
.PARAMETER Push
    Write, commit, and push the release; without this the plan is only printed.
.PARAMETER Force
    Replace an already published version instead of refusing it.
.EXAMPLE
    ./.scripts/publish-plugins.ps1 wnacg
.EXAMPLE
    ./.scripts/publish-plugins.ps1 wnacg jmcomic -Push
#>
