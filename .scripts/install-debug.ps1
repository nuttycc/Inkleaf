#Requires -Version 7.0
<#
.SYNOPSIS
    Build the debug APK with Gradle, install it on a wireless ADB device, and restart the app.
.EXAMPLE
    ./.scripts/install-debug.ps1
.EXAMPLE
    ./.scripts/install-debug.ps1 -Serial 192.168.1.5:5555
.EXAMPLE
    ./.scripts/install-debug.ps1 -Apk app/build/outputs/apk/debug/app-debug.apk
#>

[CmdletBinding()]
param(
    [Parameter()]
    [string]$Apk,

    [Parameter()]
    [double]$BuildTimeout = 600,

    [Parameter()]
    [string]$Serial,

    [Parameter()]
    [string]$Package = 'com.exio.inkleaf.debug',

    [Parameter()]
    [string]$Activity = 'com.exio.inkleaf.MainActivity',

    [Parameter()]
    [string]$Adb,

    [Parameter()]
    [double]$Timeout = 30
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$ApkDirectory = Join-Path $RepositoryRoot 'app/build/outputs/apk/debug'

function Get-LatestApk([string]$Directory) {
    $apks = @(Get-ChildItem -LiteralPath $Directory -Filter '*.apk' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime)
    if ($apks.Count -eq 0) {
        throw "no APK found in: $Directory"
    }
    return $apks[-1].FullName
}

function Resolve-AdbCommand([string]$Explicit) {
    if ($Explicit) {
        $path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Explicit)
        if (Test-Path -LiteralPath $path -PathType Leaf) { return $path }
        throw "adb executable does not exist: $path"
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $executableName = if ($IsWindows) { 'adb.exe' } else { 'adb' }
    foreach ($variable in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        $sdkRoot = [Environment]::GetEnvironmentVariable($variable)
        if (-not $sdkRoot) { continue }
        $candidate = Join-Path $sdkRoot 'platform-tools' $executableName
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw 'adb was not found on PATH or in ANDROID_HOME/ANDROID_SDK_ROOT'
}

function Build-DebugApk([string]$Root, [double]$TimeoutSeconds) {
    $gradlew = Join-Path $Root $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
    if (-not (Test-Path -LiteralPath $gradlew -PathType Leaf)) {
        throw "gradle wrapper not found: $gradlew"
    }

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    if ($IsWindows) {
        $startInfo.FileName = 'cmd.exe'
        $startInfo.ArgumentList.Add('/c')
        $startInfo.ArgumentList.Add($gradlew)
        $startInfo.ArgumentList.Add('assembleDebug')
        Write-Host "[INFO] 执行: cmd.exe /c $gradlew assembleDebug"
    } else {
        $startInfo.FileName = $gradlew
        $startInfo.ArgumentList.Add('assembleDebug')
        Write-Host "[INFO] 执行: $gradlew assembleDebug"
    }
    $startInfo.WorkingDirectory = $Root
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
    $startInfo.StandardErrorEncoding = [Text.Encoding]::UTF8

    try {
        $process = [Diagnostics.Process]::Start($startInfo)
    } catch {
        throw "could not start gradle build: $($_.Exception.Message)"
    }
    $process.add_OutputDataReceived({ param($sender, $eventArgs) if ($null -ne $eventArgs.Data) { Write-Host $eventArgs.Data } })
    $process.add_ErrorDataReceived({ param($sender, $eventArgs) if ($null -ne $eventArgs.Data) { Write-Host $eventArgs.Data } })
    $process.BeginOutputReadLine()
    $process.BeginErrorReadLine()

    $exited = $process.WaitForExit([int]($TimeoutSeconds * 1000))
    if (-not $exited) {
        try { $process.Kill($true) } catch { }
        throw "gradle build timed out after ${TimeoutSeconds}s"
    }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "gradle build failed with exit code $($process.ExitCode)"
    }
    Write-Host '[INFO] debug APK 构建成功'
}

function Invoke-AdbCommand([string]$AdbPath, [string[]]$AdbArguments, [string]$SerialNumber, [double]$TimeoutSeconds) {
    $command = [System.Collections.Generic.List[string]]::new()
    $command.Add($AdbPath)
    if ($SerialNumber) {
        $command.Add('-s')
        $command.Add($SerialNumber)
    }
    foreach ($argument in $AdbArguments) { $command.Add($argument) }
    Write-Host "[INFO] 执行: $($command -join ' ')"

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $command[0]
    for ($index = 1; $index -lt $command.Count; $index++) {
        $startInfo.ArgumentList.Add($command[$index])
    }
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
    $startInfo.StandardErrorEncoding = [Text.Encoding]::UTF8

    try {
        $process = [Diagnostics.Process]::Start($startInfo)
    } catch {
        throw "could not start adb: $($_.Exception.Message)"
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit([int]($TimeoutSeconds * 1000))) {
        try { $process.Kill($true) } catch { }
        throw "ADB command timed out after ${TimeoutSeconds}s: $($command -join ' ')"
    }
    $process.WaitForExit()
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()

    if ($process.ExitCode -ne 0) {
        $detail = if ($stderr.Trim()) { $stderr.Trim() } else { $stdout.Trim() }
        if (-not $detail) { $detail = 'no error output' }
        throw "ADB command failed with exit code $($process.ExitCode): $detail"
    }
    return $stdout.Trim()
}

function Get-DeviceStates([string]$Output) {
    $states = [System.Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    foreach ($line in ($Output -split "`n")) {
        if ($line -match '^(\S+)\s+(\S+)') {
            $states[$Matches[1]] = $Matches[2]
        }
    }
    return ,$states
}

function Test-WirelessSerial([string]$SerialNumber) {
    # Android wireless debugging uses an IP:port or an mDNS adb-* serial.
    return $SerialNumber.Contains(':') -or $SerialNumber.StartsWith('adb-')
}

function Select-WirelessDevice([string]$AdbPath, [string]$RequestedSerial, [double]$TimeoutSeconds) {
    $output = Invoke-AdbCommand $AdbPath @('devices') $null $TimeoutSeconds
    $states = Get-DeviceStates $output

    if ($RequestedSerial) {
        $state = $null
        if ($states.ContainsKey($RequestedSerial)) { $state = $states[$RequestedSerial] }
        if ($state -ne 'device') {
            $available = (@($states.Keys | ForEach-Object { "$_ ($($states[$_]))" }) -join ', ')
            if (-not $available) { $available = 'none' }
            throw "requested device '$RequestedSerial' is not ready; available devices: $available"
        }
        return $RequestedSerial
    }

    $candidates = @($states.Keys | Where-Object { $states[$_] -eq 'device' -and (Test-WirelessSerial $_) })
    if ($candidates.Count -eq 1) { return $candidates[0] }
    if ($candidates.Count -eq 0) {
        throw "no ready wireless device found; pair/connect the phone first or pass -Serial. ADB devices output: $(if ($output) { $output } else { '<empty>' })"
    }
    throw "multiple ready wireless devices found ($($candidates -join ', ')); pass -Serial to choose one"
}

function Restart-App([string]$AdbPath, [string]$SerialNumber, [string]$PackageId, [string]$ActivityName, [double]$TimeoutSeconds) {
    Invoke-AdbCommand $AdbPath @('shell', 'am', 'force-stop', $PackageId) $SerialNumber $TimeoutSeconds | Out-Null
    $component = if ($ActivityName.Contains('/')) { $ActivityName } else { "$PackageId/$ActivityName" }
    Invoke-AdbCommand $AdbPath @('shell', 'am', 'start', '-n', $component) $SerialNumber $TimeoutSeconds | Out-Null
}

function Invoke-Install {
    if ($Timeout -le 0) { throw '-Timeout must be greater than zero' }
    if ($BuildTimeout -le 0) { throw '-BuildTimeout must be greater than zero' }

    if (-not $Apk) {
        Build-DebugApk $RepositoryRoot $BuildTimeout
        $apkPath = Get-LatestApk $ApkDirectory
        Write-Host "[INFO] 使用最新构建产物: $(Split-Path -Leaf $apkPath)"
    } else {
        $apkPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk)
    }
    if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
        throw "debug APK does not exist: $apkPath"
    }

    $adbPath = Resolve-AdbCommand $Adb
    Write-Host "[INFO] APK: $apkPath"
    $serialNumber = Select-WirelessDevice $adbPath $Serial $Timeout
    Write-Host "[INFO] 目标设备: $serialNumber"

    Invoke-AdbCommand $adbPath @('install', '-r', '-d', $apkPath) $serialNumber $Timeout | Out-Null
    Write-Host '[INFO] debug 包安装成功'
    Restart-App $adbPath $serialNumber $Package $Activity $Timeout
    Write-Host "[INFO] 应用已重启: $Package"
}

try {
    Invoke-Install
} catch {
    [Console]::Error.WriteLine("[ERROR] $($_.Exception.Message)")
    exit 1
}
