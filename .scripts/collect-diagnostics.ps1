# Collect read-only Android diagnostics for a connected Inkleaf install.
#
# Usage:
#   .\.scripts\collect-diagnostics.ps1
#   .\.scripts\collect-diagnostics.ps1 -Serial emulator-5554 -Package com.exio.inkleaf
#   .\.scripts\collect-diagnostics.ps1 -IncludeBugreport -IncludePerfetto

[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$Package = "com.exio.inkleaf.debug",
    [switch]$IncludeBugreport,
    [switch]$IncludePerfetto
)

$ErrorActionPreference = "Stop"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDirectory = Join-Path $env:TEMP "inkleaf-diagnostics-$timestamp"
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

function Get-OnlineDevices {
    @(& adb devices | Select-Object -Skip 1 | ForEach-Object {
        if ($_ -match '^\s*(\S+)\s+device\s*$') { $Matches[1] }
    })
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb was not found in PATH."
}
if ([string]::IsNullOrWhiteSpace($Serial)) {
    $devices = @(Get-OnlineDevices)
    if ($devices.Count -ne 1) {
        throw "Expected exactly one online device; pass -Serial when adb lists multiple devices."
    }
    $Serial = $devices[0]
}

function Invoke-AdbCapture {
    param([string]$Name, [string[]]$Arguments)
    $destination = Join-Path $outputDirectory $Name
    & adb -s $Serial @Arguments 2>&1 | Out-File -LiteralPath $destination -Encoding utf8
    if ($LASTEXITCODE -ne 0) { Write-Warning "adb $($Arguments -join ' ') failed; see $Name" }
}

$pid = (& adb -s $Serial shell pidof $Package 2>$null).Trim()
if ($LASTEXITCODE -ne 0) { $pid = "" }

Invoke-AdbCapture -Name "device.txt" -Arguments @("shell", "getprop")
Invoke-AdbCapture -Name "exit-info.txt" -Arguments @("shell", "dumpsys", "activity", "exit-info", $Package)
Invoke-AdbCapture -Name "meminfo.txt" -Arguments @("shell", "dumpsys", "meminfo", $Package)
Invoke-AdbCapture -Name "gfxinfo.txt" -Arguments @("shell", "dumpsys", "gfxinfo", $Package)
Invoke-AdbCapture -Name "crash-logcat.txt" -Arguments @("logcat", "-b", "crash", "-d", "-v", "threadtime")
if ($pid) {
    Invoke-AdbCapture -Name "process-logcat.txt" -Arguments @("logcat", "-d", "-v", "threadtime", "--pid=$pid")
} else {
    "No running process found for $Package." | Out-File (Join-Path $outputDirectory "process-logcat.txt") -Encoding utf8
}

if ($IncludeBugreport) {
    & adb -s $Serial bugreport (Join-Path $outputDirectory "bugreport.zip")
    if ($LASTEXITCODE -ne 0) { Write-Warning "adb bugreport failed." }
}
if ($IncludePerfetto) {
    Invoke-AdbCapture -Name "perfetto-processes.txt" -Arguments @("shell", "perfetto", "--query-raw")
}

Write-Host "Collected read-only diagnostics in: $outputDirectory" -ForegroundColor Green
