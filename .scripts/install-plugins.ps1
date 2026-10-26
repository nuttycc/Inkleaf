#Requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Plugin,

    [Parameter()]
    [string]$Serial,

    [Parameter()]
    [string]$Output,

    [Parameter()]
    [string]$PackageId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false) } catch { }
Import-Module (Join-Path $PSScriptRoot 'plugin-toolkit.psm1') -Force -DisableNameChecking

$DebugPackageId = 'com.exio.inkleaf.debug'
$PackageIdPattern = '^[A-Za-z][A-Za-z0-9_.]*$'
$BroadcastResultPattern = 'Broadcast completed: result=(?<code>-?\d+)(?:, data="(?<data>[^"]*)")?'

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
    $localProperties = Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))) 'local.properties'
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
    throw "Multiple ADB devices are ready. Re-run with -Serial. Devices: $serials"
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
        $hint += " The debug app is ready; re-run with -PackageId $DebugPackageId."
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

try {
    if ([string]::IsNullOrWhiteSpace($Plugin)) {
        throw 'Specify a plugin name, for example: ./.scripts/install-plugins.ps1 jmcomic'
    }
    if (-not $PSBoundParameters.ContainsKey('PackageId') -or [string]::IsNullOrWhiteSpace($PackageId)) {
        $PackageId = $DebugPackageId
    }
    $selected = Find-Plugin $Plugin
    Deploy-Plugin $selected $Serial $Output $PackageId
} catch {
    [Console]::Error.WriteLine("error: $($_.Exception.Message)")
    exit 1
}

<#
.SYNOPSIS
    Package a plugin and install it on the debug app over ADB.
.DESCRIPTION
    Plugin source lives at plugin-sources/<name>/src/main.js. The ZIP is built by
    plugin-toolkit.psm1 and streamed to the debug app's ADB install receiver, which
    exists only in debug builds.

    Packaging writes below plugin-sources/dist; ADB installs go to
    com.exio.inkleaf.debug unless -PackageId says otherwise.
.PARAMETER Plugin
    Directory name below plugin-sources.
.PARAMETER Serial
    ADB device serial; required when several devices are ready.
.PARAMETER Output
    ZIP path below plugin-sources/dist. Defaults to the versioned name.
.PARAMETER PackageId
    Android package to install into. Defaults to the debug app.
.EXAMPLE
    ./.scripts/install-plugins.ps1 jmcomic
.EXAMPLE
    ./.scripts/install-plugins.ps1 wnacg -Serial 192.168.1.5:5555
#>
