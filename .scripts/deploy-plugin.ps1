#Requires -Version 5.1
<#
.SYNOPSIS
  Package, push, install, and activate a plugin over ADB.

.DESCRIPTION
  Generic replacement for the per-plugin deploy scripts. Packaging is delegated so
  the archive layout keeps one source of truth per plugin:

    - plugins with a source.js are built by build-plugin.ps1 (shared runtime + concat)
    - plugins with a hand-written main.js use their own package-<name>-plugin.ps1

  The resulting ZIP is copied to /sdcard/Download/Inkleaf and sent to Inkleaf's
  DUMP-protected ADB install receiver in command-line-safe chunks.

.PARAMETER Plugin
  Directory name under plugin-fixtures, e.g. zaimanhua or copycomic.

.PARAMETER Serial
  ADB device serial. Required when more than one ready device is connected.

.PARAMETER DeviceDirectory
  Destination directory on the device.

.PARAMETER OutputPath
  Repository-relative path for the local package. Defaults to the plugin's usual name.

.PARAMETER PackageId
  Installed Inkleaf application id. Use com.exio.inkleaf.debug for the debug app.

.EXAMPLE
  .\.scripts\deploy-plugin.ps1 -Plugin zaimanhua

.EXAMPLE
  .\.scripts\deploy-plugin.ps1 -Plugin zaimanhua -Serial emulator-5554 -PackageId com.exio.inkleaf.debug
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Plugin,
    [string] $Serial = "",
    [string] $DeviceDirectory = "/sdcard/Download/Inkleaf",
    [string] $OutputPath = "",
    [string] $PackageId = "com.exio.inkleaf"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Info([string] $Message) { Write-Host "[info] $Message" -ForegroundColor Cyan }
function Write-Ok([string] $Message) { Write-Host "[ok]   $Message" -ForegroundColor Green }

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

function Resolve-AdbPath {
    $fromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($fromPath) { return $fromPath.Source }

    $candidates = @()
    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
    }

    $localProperties = Join-Path $script:RepoRoot "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine =
            Get-Content -LiteralPath $localProperties |
                Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
                Select-Object -First 1
        if ($sdkLine) {
            $sdk = ($sdkLine -replace '^\s*sdk\.dir\s*=\s*', '').Trim()
            $sdk = $sdk -replace '\\\\', '\' -replace '\\:', ':'
            $candidates += (Join-Path $sdk "platform-tools\adb.exe")
        }
    }

    $candidates += @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
        (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk\platform-tools\adb.exe")
    )

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw "adb not found. Install Android platform-tools or configure the Android SDK path."
}

function Select-AdbDevice([string] $Adb, [string] $PreferredSerial) {
    & $Adb start-server | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to start the adb server." }

    $devices = @(
        & $Adb devices |
            Select-Object -Skip 1 |
            ForEach-Object {
                if ($_ -match '^(?<serial>\S+)\s+(?<state>\S+)') {
                    [pscustomobject]@{ Serial = $Matches.serial; State = $Matches.state }
                }
            }
    )
    $ready = @($devices | Where-Object { $_.State -eq "device" })

    if ($PreferredSerial) {
        $selected = $ready | Where-Object { $_.Serial -eq $PreferredSerial } | Select-Object -First 1
        if (-not $selected) {
            $known = if ($ready.Count -gt 0) { $ready.Serial -join ", " } else { "none" }
            throw "ADB device '$PreferredSerial' is not ready. Ready devices: $known"
        }
        return $selected.Serial
    }

    if ($ready.Count -eq 0) {
        $seen = if ($devices.Count -gt 0) {
            ($devices | ForEach-Object { "$($_.Serial)=$($_.State)" }) -join ", "
        } else {
            "none"
        }
        throw "No ready ADB device. Seen devices: $seen"
    }
    if ($ready.Count -gt 1) {
        throw "Multiple ADB devices are ready. Re-run with -Serial. Devices: $($ready.Serial -join ', ')"
    }
    return $ready[0].Serial
}

function Test-AdbInstallReceiver {
    param(
        [Parameter(Mandatory)] [string] $Adb,
        [Parameter(Mandatory)] [string] $Device,
        [Parameter(Mandatory)] [string] $AppId
    )

    $component = "$AppId/com.exio.inkleaf.plugin.AdbPluginInstallReceiver"
    $output = @(
        & $Adb -s $Device shell cmd package query-receivers --brief --components -n $component 2>&1
    )
    return $LASTEXITCODE -eq 0 -and @($output | Where-Object { $_.Trim() -eq $component }).Count -gt 0
}

function Assert-AdbInstallReceiver {
    param(
        [Parameter(Mandatory)] [string] $Adb,
        [Parameter(Mandatory)] [string] $Device,
        [Parameter(Mandatory)] [string] $AppId
    )

    $packagePath = @(& $Adb -s $Device shell pm path $AppId 2>&1)
    if ($LASTEXITCODE -ne 0 -or -not ($packagePath -match '^package:')) {
        throw "Inkleaf package is not installed on the device: $AppId"
    }
    if (Test-AdbInstallReceiver -Adb $Adb -Device $Device -AppId $AppId) { return }

    $hint = " Install a current Inkleaf build before deploying plugins."
    $debugAppId = "com.exio.inkleaf.debug"
    if ($AppId -ne $debugAppId -and
        (Test-AdbInstallReceiver -Adb $Adb -Device $Device -AppId $debugAppId)) {
        $hint += " The debug app is ready; rerun with -PackageId $debugAppId."
    }
    throw "Installed Inkleaf package '$AppId' does not expose the ADB plugin install receiver.$hint"
}

function Get-AppProcessId {
    param(
        [Parameter(Mandatory)] [string] $Adb,
        [Parameter(Mandatory)] [string] $Device,
        [Parameter(Mandatory)] [string] $AppId
    )

    $output = @(& $Adb -s $Device shell pidof $AppId 2>&1)
    if ($LASTEXITCODE -ne 0) { return "" }
    return ($output | Out-String).Trim()
}

function Start-AppProcess {
    param(
        [Parameter(Mandatory)] [string] $Adb,
        [Parameter(Mandatory)] [string] $Device,
        [Parameter(Mandatory)] [string] $AppId
    )

    # The install receiver only answers when the app process is already alive. Some OEM ROMs
    # (ColorOS among them) refuse to cold-start a process for this broadcast even with
    # --include-stopped-packages, and the failure is silent: the broadcast is enqueued, nothing
    # runs, and `am` reports result=0 with no data, which reads like a rejection by Inkleaf.
    if (Get-AppProcessId -Adb $Adb -Device $Device -AppId $AppId) { return }

    Write-Info "Starting $AppId so it can receive the install broadcast"
    $resolved = @(& $Adb -s $Device shell cmd package resolve-activity --brief $AppId 2>&1)
    $component =
        @($resolved | Where-Object { $_ -match "^$([regex]::Escape($AppId))/" }) |
            Select-Object -First 1
    if ($component) {
        & $Adb -s $Device shell am start -n $component.Trim() | Out-Null
    } else {
        & $Adb -s $Device shell monkey -p $AppId -c android.intent.category.LAUNCHER 1 | Out-Null
    }

    for ($attempt = 0; $attempt -lt 20; $attempt += 1) {
        Start-Sleep -Milliseconds 500
        if (Get-AppProcessId -Adb $Adb -Device $Device -AppId $AppId) { return }
    }
    throw "Unable to start $AppId on the device; launch it manually and re-run."
}

function Invoke-InstallBroadcast {
    param(
        [Parameter(Mandatory)] [string] $Adb,
        [Parameter(Mandatory)] [string] $Device,
        [Parameter(Mandatory)] [string] $AppId,
        [Parameter(Mandatory)] [string[]] $Extras
    )

    $component = "$AppId/com.exio.inkleaf.plugin.AdbPluginInstallReceiver"
    $arguments = @(
        "-s", $Device,
        "shell", "am", "broadcast",
        "-a", "com.exio.inkleaf.action.ADB_INSTALL_PLUGIN",
        "-n", $component,
        "--receiver-foreground",
        "--include-stopped-packages"
    ) + $Extras
    $output = @(& $Adb @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String).Trim()

    if ($exitCode -ne 0) {
        throw "ADB install broadcast failed (exit $exitCode): $text"
    }
    $completion = [regex]::Match($text, 'Broadcast completed: result=(?<code>-?\d+)(?:, data="(?<data>[^"]*)")?')
    if (-not $completion.Success) {
        throw "Inkleaf did not return an installation result: $text"
    }
    if ($completion.Groups["code"].Value -ne "-1") {
        $detail = $completion.Groups["data"].Value
        if (-not $detail) { $detail = $text }
        throw "Inkleaf rejected the plugin deployment: $detail"
    }
    return $completion.Groups["data"].Value
}

if ($Plugin -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*\z') {
    throw "Plugin must be a simple directory name: $Plugin"
}

$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$pluginRoot = Join-Path $script:RepoRoot "plugin-fixtures\$Plugin"
if (-not (Test-Path -LiteralPath $pluginRoot)) {
    throw "Plugin directory not found: $pluginRoot"
}
$manifestPath = Join-Path $pluginRoot "manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "Manifest not found: $manifestPath"
}

# One source of truth per archive layout: concat-built plugins go through build-plugin.ps1, and
# hand-written ones keep their existing packaging script.
$buildScript = Join-Path $PSScriptRoot "build-plugin.ps1"
$legacyPackageScript = Join-Path $PSScriptRoot "package-$Plugin-plugin.ps1"
if (Test-Path -LiteralPath (Join-Path $pluginRoot "source.js")) {
    $packageScript = $buildScript
    $packageArguments = @{ Plugin = $Plugin }
} elseif (Test-Path -LiteralPath $legacyPackageScript) {
    $packageScript = $legacyPackageScript
    $packageArguments = @{}
} else {
    throw "No packaging route for '$Plugin': expected $pluginRoot\source.js or $legacyPackageScript"
}
if (-not (Test-Path -LiteralPath $packageScript)) {
    throw "Packaging script not found: $packageScript"
}

if (-not $OutputPath) { $OutputPath = "plugin-fixtures/dist/$Plugin-plugin.zip" }

$distRoot = [IO.Path]::GetFullPath((Join-Path $script:RepoRoot "plugin-fixtures\dist"))
$outputCandidate =
    if ([IO.Path]::IsPathRooted($OutputPath)) { $OutputPath }
    else { Join-Path $script:RepoRoot $OutputPath }
$localPackage = [IO.Path]::GetFullPath($outputCandidate)
$distPrefix = $distRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $localPackage.StartsWith($distPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    [IO.Path]::GetExtension($localPackage) -ne ".zip") {
    throw "OutputPath must be a .zip file below $distRoot"
}
$outputDirectory = Split-Path -Parent $localPackage
Initialize-SafeOutputDirectory -Root $distRoot -TargetDirectory $outputDirectory
if (Test-Path -LiteralPath $localPackage) {
    $outputItem = Get-Item -Force -LiteralPath $localPackage
    if ($outputItem.PSIsContainer -or
        ($outputItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Output file must not be a directory or reparse point: $localPackage"
    }
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
if (-not $manifest.version -or -not $manifest.id) {
    throw "Manifest must declare id and version: $manifestPath"
}
if ($PackageId -notmatch '^[A-Za-z][A-Za-z0-9_.]*\z') {
    throw "Invalid Android package id: $PackageId"
}
if ($DeviceDirectory -notmatch '^/sdcard/[A-Za-z0-9._/-]+\z') {
    throw "DeviceDirectory must be an absolute path below /sdcard."
}
$devicePathSegments = $DeviceDirectory.Substring("/sdcard/".Length).Split('/')
$unsafeSegments = @($devicePathSegments | Where-Object { -not $_ -or $_ -eq "." -or $_ -eq ".." })
if ($unsafeSegments.Count -gt 0) {
    throw "DeviceDirectory cannot contain empty, current-directory, or parent-directory segments."
}

Write-Info "Packaging $($manifest.id)@$($manifest.version)"
& $packageScript @packageArguments -OutputPath $OutputPath
if (-not (Test-Path -LiteralPath $localPackage)) {
    throw "Packaging failed: $localPackage was not produced."
}

$adb = Resolve-AdbPath
$device = Select-AdbDevice -Adb $adb -PreferredSerial $Serial
$deviceFile = "$Plugin-plugin-v$($manifest.version).zip"
$devicePath = "$($DeviceDirectory.TrimEnd('/'))/$deviceFile"

Write-Info "Using ADB device $device"
Assert-AdbInstallReceiver -Adb $adb -Device $device -AppId $PackageId
Start-AppProcess -Adb $adb -Device $device -AppId $PackageId
& $adb -s $device shell mkdir -p $DeviceDirectory
if ($LASTEXITCODE -ne 0) { throw "Unable to create device directory: $DeviceDirectory" }

& $adb -s $device push $localPackage $devicePath
if ($LASTEXITCODE -ne 0) { throw "adb push failed: $devicePath" }

# Small chunks avoid the Windows command-line limit and remain well below Binder's transaction cap.
$session = [Guid]::NewGuid().ToString("N")
$encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($localPackage))
$common = @("--es", "session", $session)

Write-Info "Starting ADB installation in $PackageId"
$null = Invoke-InstallBroadcast -Adb $adb -Device $device -AppId $PackageId -Extras (
    $common + @("--es", "operation", "begin")
)

$chunkSize = 12 * 1024
for ($offset = 0; $offset -lt $encoded.Length; $offset += $chunkSize) {
    $length = [Math]::Min($chunkSize, $encoded.Length - $offset)
    $chunk = $encoded.Substring($offset, $length)
    $null = Invoke-InstallBroadcast -Adb $adb -Device $device -AppId $PackageId -Extras (
        $common + @("--es", "operation", "append", "--es", "payload", $chunk)
    )
}

$result = Invoke-InstallBroadcast -Adb $adb -Device $device -AppId $PackageId -Extras (
    $common + @(
        "--es", "operation", "commit",
        "--es", "expectedPluginId", "$($manifest.id)",
        "--es", "expectedVersion", "$($manifest.version)"
    )
)

Write-Ok "Plugin package pushed to $devicePath"
Write-Ok "Plugin installed and activated: $result"
