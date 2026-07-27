#Requires -Version 5.1
<#
.SYNOPSIS
  Package, push, install, and activate the CopyComic plugin over ADB.

.DESCRIPTION
  Reuses package-copycomic-plugin.ps1 so the archive layout has one source of
  truth, copies the versioned ZIP to /sdcard/Download/Inkleaf, and sends it to
  Inkleaf's DUMP-protected ADB install receiver in command-line-safe chunks.

.PARAMETER Serial
  ADB device serial. Required when more than one ready device is connected.

.PARAMETER DeviceDirectory
  Destination directory on the device.

.PARAMETER OutputPath
  Repository-relative path for the local package.

.PARAMETER PackageId
  Installed Inkleaf application id. Use com.exio.inkleaf.debug for the debug app.

.EXAMPLE
  .\.scripts\package-and-push-copycomic-plugin.ps1

.EXAMPLE
  .\.scripts\package-and-push-copycomic-plugin.ps1 -Serial emulator-5554
#>
[CmdletBinding()]
param(
    [string] $Serial = "",
    [string] $DeviceDirectory = "/sdcard/Download/Inkleaf",
    [string] $OutputPath = "plugin-fixtures/dist/copycomic-plugin.zip",
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

$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$manifestPath = Join-Path $script:RepoRoot "plugin-fixtures\copycomic\manifest.json"
$packageScript = Join-Path $PSScriptRoot "package-copycomic-plugin.ps1"
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

if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "CopyComic manifest not found: $manifestPath"
}
if (-not (Test-Path -LiteralPath $packageScript)) {
    throw "Packaging script not found: $packageScript"
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
if (-not $manifest.version -or -not $manifest.id) {
    throw "CopyComic manifest must declare id and version."
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
& $packageScript -OutputPath $OutputPath
if (-not (Test-Path -LiteralPath $localPackage)) {
    throw "CopyComic packaging failed."
}

$adb = Resolve-AdbPath
$device = Select-AdbDevice -Adb $adb -PreferredSerial $Serial
$deviceFile = "copycomic-plugin-v$($manifest.version).zip"
$devicePath = "$($DeviceDirectory.TrimEnd('/'))/$deviceFile"

Write-Info "Using ADB device $device"
& $adb -s $device shell mkdir -p $DeviceDirectory
if ($LASTEXITCODE -ne 0) { throw "Unable to create device directory: $DeviceDirectory" }

& $adb -s $device push $localPackage $devicePath
if ($LASTEXITCODE -ne 0) { throw "adb push failed: $devicePath" }

$packagePath = @(& $adb -s $device shell pm path $PackageId 2>&1)
if ($LASTEXITCODE -ne 0 -or -not ($packagePath -match '^package:')) {
    throw "Inkleaf package is not installed on the device: $PackageId"
}

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
