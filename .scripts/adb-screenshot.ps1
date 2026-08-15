<#
.SYNOPSIS
    Captures an Android screen via ADB and saves it under .tmp/<dir>/.

.DESCRIPTION
    Uses `adb exec-out screencap -p` (with fallback to adb shell screencap + pull)
    to quickly capture the screen of a connected Android device and save it in
    the project's `.tmp/<Dir>` directory.

.PARAMETER Dir
    The subdirectory under .tmp/ to save the screenshot. Defaults to "screenshots".

.PARAMETER Name
    Optional file name for the screenshot (without or with .png extension).
    If omitted, a timestamped name like `screenshot_yyyyMMdd_HHmmss.png` will be used.

.PARAMETER Serial
    Specific device serial to target if multiple devices are connected.

.PARAMETER Open
    If specified, opens the captured screenshot in the default image viewer.

.EXAMPLE
    .\.scripts\adb-screenshot.ps1
    # Saves to .tmp/screenshots/screenshot_20260815_165000.png

.EXAMPLE
    .\.scripts\adb-screenshot.ps1 -Dir "ui-review" -Name "homepage" -Open
    # Saves to .tmp/ui-review/homepage.png and opens it
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Dir = "screenshots",

    [Parameter(Position = 1)]
    [string]$Name,

    [Parameter()]
    [string]$Serial,

    [switch]$Open
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# 1. Locate project root & ensure target directory exists
$ProjectRoot = (Resolve-Path "$PSScriptRoot/..").Path
$TargetDir = if ([System.IO.Path]::IsPathRooted($Dir)) {
    $Dir
} else {
    Join-Path $ProjectRoot (Join-Path ".tmp" $Dir)
}

if (-not (Test-Path -LiteralPath $TargetDir)) {
    $null = New-Item -ItemType Directory -Path $TargetDir -Force
}

# 2. Check ADB availability
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Error "adb command not found. Please ensure Android SDK platform-tools is in your PATH."
    exit 1
}

# 3. Detect connected Android devices
$deviceLines = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S+' }
$onlineDevices = @()
foreach ($line in $deviceLines) {
    if ($line -match '^\s*(\S+)\s+device$') {
        $onlineDevices += $Matches[1]
    }
}

if ($onlineDevices.Count -eq 0) {
    Write-Error "No connected Android device found in 'device' state. Run '.scripts/adb-connect.ps1' or check USB/Wireless debugging."
    exit 1
}

$targetSerial = $Serial
if ([string]::IsNullOrWhiteSpace($targetSerial)) {
    if ($onlineDevices.Count -eq 1) {
        $targetSerial = $onlineDevices[0]
    } else {
        $targetSerial = $onlineDevices[0]
        Write-Warning "Multiple devices detected: $($onlineDevices -join ', '). Using '$targetSerial'. Use -Serial to specify."
    }
} else {
    if ($onlineDevices -notcontains $targetSerial) {
        Write-Warning "Specified serial '$targetSerial' is not in online device list ($($onlineDevices -join ', ')). Trying anyway..."
    }
}

# 4. Determine file name
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$fileName = if ([string]::IsNullOrWhiteSpace($Name)) {
    "screenshot_$timestamp.png"
} else {
    if ($Name.EndsWith(".png", [System.StringComparison]::OrdinalIgnoreCase)) {
        $Name
    } else {
        "$Name.png"
    }
}

$destinationFile = Join-Path $TargetDir $fileName
$adbSerialArgs = if ($targetSerial) { @("-s", $targetSerial) } else { @() }

Write-Host "Capturing screen from device [$targetSerial]..." -ForegroundColor Cyan

# 5. Capture screenshot directly via binary stream (fast & no temp file on device flash)
$success = $false
try {
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = "adb"
    if ($targetSerial) {
        $psi.Arguments = "-s $targetSerial exec-out screencap -p"
    } else {
        $psi.Arguments = "exec-out screencap -p"
    }
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::Start($psi)
    $fileStream = [System.IO.File]::Create($destinationFile)
    
    $process.StandardOutput.BaseStream.CopyTo($fileStream)
    $fileStream.Flush()
    $fileStream.Close()
    $process.WaitForExit()

    if ($process.ExitCode -eq 0 -and (Test-Path -LiteralPath $destinationFile) -and ((Get-Item -LiteralPath $destinationFile).Length -gt 0)) {
        $success = $true
    }
} catch {
    Write-Verbose "exec-out direct stream failed: $_. Falling back to adb shell screencap + pull..."
}

# Fallback method: screencap to /data/local/tmp + pull
if (-not $success) {
    Write-Host "Using fallback screencap method (device tmp -> adb pull)..." -ForegroundColor Yellow
    $remoteTmp = "/data/local/tmp/screencap_$timestamp.png"
    & adb @adbSerialArgs shell screencap -p $remoteTmp
    & adb @adbSerialArgs pull $remoteTmp $destinationFile
    & adb @adbSerialArgs shell rm -f $remoteTmp
}

if ((Test-Path -LiteralPath $destinationFile) -and ((Get-Item -LiteralPath $destinationFile).Length -gt 0)) {
    $fileInfo = Get-Item -LiteralPath $destinationFile
    $sizeKb = [math]::Round($fileInfo.Length / 1KB, 2)
    Write-Host "Screenshot saved successfully:" -ForegroundColor Green
    Write-Host "  File: $($fileInfo.FullName)" -ForegroundColor White
    Write-Host "  Size: $sizeKb KB" -ForegroundColor Gray

    if ($Open) {
        Invoke-Item -LiteralPath $destinationFile
    }

    return $fileInfo
} else {
    Write-Error "Failed to capture screenshot."
    exit 1
}
