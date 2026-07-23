#Requires -Version 5.1
<#
.SYNOPSIS
  Download the latest GitHub Actions debug APK and install it with adb.

.DESCRIPTION
  Finds the newest non-expired Actions artifact named "inkleaf-debug-*",
  downloads the APK, installs onto a connected device/emulator, and optionally launches the app.

.PARAMETER Repo
  GitHub repo slug (owner/name). Default: remote origin or nuttycc/Inkleaf.

.PARAMETER PackageId
  Application id for launch. Default: com.exio.inkleaf.debug

.PARAMETER WorkDir
  Download/extract directory. Default: %TEMP%\inkleaf-gha-debug

.PARAMETER Serial
  adb device serial when multiple devices are attached.

.PARAMETER Launch
  Start the app after a successful install.

.PARAMETER SkipDownload
  Reuse an existing APK under WorkDir if present (no GitHub download).

.EXAMPLE
  .\scripts\install-gha-debug.ps1

.EXAMPLE
  .\scripts\install-gha-debug.ps1 -Launch -Serial emulator-5554
#>
[CmdletBinding()]
param(
    [string] $Repo = "",
    [string] $PackageId = "com.exio.inkleaf.debug",
    [string] $WorkDir = (Join-Path $env:TEMP "inkleaf-gha-debug"),
    [string] $Serial = "",
    [switch] $NoLaunch,
    [switch] $SkipDownload
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Info([string] $Message) { Write-Host "[info]  $Message" -ForegroundColor Cyan }
function Write-Ok([string] $Message)   { Write-Host "[ok]    $Message" -ForegroundColor Green }
function Write-Warn([string] $Message) { Write-Host "[warn]  $Message" -ForegroundColor Yellow }
function Fail([string] $Message, [int] $Code = 1) {
    Write-Host "[error] $Message" -ForegroundColor Red
    exit $Code
}

function Get-RepoRoot {
    $here = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
    return (Resolve-Path (Join-Path $here "..")).Path
}

function Resolve-RepoSlug([string] $Explicit) {
    if ($Explicit) { return $Explicit }
    try {
        $url = (& git -C (Get-RepoRoot) remote get-url origin 2>$null)
        if ($LASTEXITCODE -eq 0 -and $url) {
            if ($url -match 'github\.com[:/](?<owner>[^/]+)/(?<repo>[^/.]+)') {
                return "$($Matches.owner)/$($Matches.repo)"
            }
        }
    } catch { }
    return "nuttycc/Inkleaf"
}

function Assert-Command([string] $Name, [string] $Hint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        Fail "Required command not found: $Name. $Hint"
    }
}

function Resolve-AdbPath {
    $candidates = @()
    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools/adb")
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools/adb")
    }
    $root = Get-RepoRoot
    $lp = Join-Path $root "local.properties"
    if (Test-Path $lp) {
        $line = Get-Content $lp | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $sdk = ($line -replace '^\s*sdk\.dir\s*=\s*', '').Trim()
            # local.properties often escapes Windows paths: C\:\\Users\\...
            $sdk = $sdk -replace '\\\\', '\' -replace '\\:', ':'
            $candidates += (Join-Path $sdk "platform-tools\adb.exe")
            $candidates += (Join-Path $sdk "platform-tools/adb")
        }
    }
    $candidates += @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
        "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    )
    $fromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($fromPath) { return $fromPath.Source }

    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath $c)) { return (Resolve-Path -LiteralPath $c).Path }
    }
    Fail @"
adb not found.
Install Android platform-tools, or set ANDROID_HOME / ANDROID_SDK_ROOT,
or ensure local.properties has sdk.dir=...
"@
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory)] [string] $Adb,
        [string[]] $Args,
        [string] $DeviceSerial = ""
    )
    $all = @()
    if ($DeviceSerial) { $all += @("-s", $DeviceSerial) }
    $all += $Args
    & $Adb @all | Out-Host
    return $LASTEXITCODE
}

function Get-ReadyDevices([string] $Adb) {
    $null = & $Adb start-server 2>&1
    $lines = & $Adb devices 2>&1
    $devices = @()
    foreach ($line in $lines) {
        $text = "$line".Trim()
        if (-not $text -or $text -match '^List of devices') { continue }
        if ($text -match '^(?<id>\S+)\s+(?<state>\S+)') {
            $devices += [pscustomobject]@{ Id = $Matches.id; State = $Matches.state }
        }
    }
    return $devices
}

function Select-Device([string] $Adb, [string] $PreferredSerial) {
    $all = @(Get-ReadyDevices $Adb)
    if ($all.Count -eq 0) {
        Fail @"
No adb devices found.
- USB: enable Developer options + USB debugging, accept the RSA prompt
- Wireless: adb connect <ip>:<port>
- Then re-run this script
"@ 2
    }

    $unauthorized = @($all | Where-Object { $_.State -eq 'unauthorized' })
    if ($unauthorized.Count -gt 0 -and -not ($all | Where-Object { $_.State -eq 'device' })) {
        Fail ("Device unauthorized: {0}. Unlock the phone and allow USB debugging." -f ($unauthorized.Id -join ', ')) 3
    }

    $offline = @($all | Where-Object { $_.State -eq 'offline' })
    if ($offline.Count -gt 0) {
        Write-Warn ("Offline device(s): {0}" -f ($offline.Id -join ', '))
    }

    $ready = @($all | Where-Object { $_.State -eq 'device' })
    if ($ready.Count -eq 0) {
        Fail ("No device in 'device' state. Seen: {0}" -f (($all | ForEach-Object { "$($_.Id)=$($_.State)" }) -join ', ')) 2
    }

    if ($PreferredSerial) {
        $hit = $ready | Where-Object { $_.Id -eq $PreferredSerial } | Select-Object -First 1
        if (-not $hit) {
            Fail ("Serial '$PreferredSerial' not ready. Ready: {0}" -f ($ready.Id -join ', ')) 2
        }
        return $hit.Id
    }

    if ($ready.Count -gt 1) {
        Fail @"
Multiple devices ready: $($ready.Id -join ', ')
Re-run with -Serial <id>
"@ 2
    }
    return $ready[0].Id
}

function Get-LatestDebugArtifact([string] $RepoSlug) {
    Write-Info "Querying Actions artifacts for $RepoSlug ..."
    try {
        $raw = & gh api "repos/$RepoSlug/actions/artifacts?per_page=50" 2>&1
        if ($LASTEXITCODE -ne 0) { throw "$raw" }
        $json = $raw | ConvertFrom-Json
    } catch {
        Fail @"
Failed to list artifacts for $RepoSlug.
- gh auth login (need repo scope)
- check network / repo access
Details: $_
"@ 4
    }

    $latest = $json.artifacts |
        Where-Object { $_.name -like 'inkleaf-debug-*' -and -not $_.expired } |
        Sort-Object created_at -Descending |
        Select-Object -First 1

    if (-not $latest) {
        Fail @"
No non-expired artifact matching 'inkleaf-debug-*'.
Upload one via GHA Android Check with task=apk or task=full
(workflow_dispatch), then re-run.
"@ 5
    }
    return $latest
}

function Get-ApkFromArtifact {
    param(
        [string] $RepoSlug,
        $Artifact,
        [string] $Dir
    )

    if (Test-Path $Dir) {
        Get-ChildItem $Dir -Force | Where-Object { $_.Name -ne '.keep' } | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    }
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null

    $runId = $Artifact.workflow_run.id
    $name = $Artifact.name
    Write-Info "Downloading artifact '$name' (run $runId, id $($Artifact.id)) ..."

    $downloaded = $false
    if ($runId) {
        & gh run download $runId --repo $RepoSlug --name $name --dir $Dir 2>&1 | Out-Host
        if ($LASTEXITCODE -eq 0) { $downloaded = $true }
    }

    if (-not $downloaded) {
        Write-Warn "gh run download failed; trying artifact zip API ..."
        $zip = Join-Path $Dir "artifact.zip"
        $token = & gh auth token 2>$null
        if (-not $token) { Fail "gh auth token failed. Run: gh auth login" 4 }
        & curl.exe -sL `
            -H "Authorization: Bearer $token" `
            -H "Accept: application/vnd.github+json" `
            -H "X-GitHub-Api-Version: 2022-11-28" `
            -o $zip `
            "https://api.github.com/repos/$RepoSlug/actions/artifacts/$($Artifact.id)/zip"
        if (-not (Test-Path $zip) -or (Get-Item $zip).Length -lt 1000) {
            Fail "Artifact zip download failed or file too small: $zip" 6
        }
        Expand-Archive -Path $zip -DestinationPath $Dir -Force
    }

    $apk = Get-ChildItem -Path $Dir -Recurse -Filter *.apk -File -ErrorAction SilentlyContinue |
        Sort-Object Length -Descending |
        Select-Object -First 1

    if (-not $apk) {
        Fail "Download finished but no .apk found under $Dir" 6
    }
    Write-Ok ("APK ready: {0} ({1:N1} MB)" -f $apk.FullName, ($apk.Length / 1MB))
    return $apk.FullName
}

function Install-Apk([string] $Adb, [string] $Device, [string] $ApkPath) {
    Write-Info "Installing on $Device ..."
    $code = Invoke-Adb -Adb $Adb -DeviceSerial $Device -Args @("install", "-r", $ApkPath)
    if ($code -ne 0) {
        Fail @"
adb install failed (exit $code).
Common causes:
- INSTALL_FAILED_UPDATE_INCOMPATIBLE: uninstall old app first
  adb -s $Device uninstall $PackageId
- INSTALL_FAILED_INSUFFICIENT_STORAGE: free space on device
- device went offline mid-transfer: reconnect and retry
"@ 7
    }
    Write-Ok "Install succeeded"
}

function Start-App([string] $Adb, [string] $Device, [string] $AppId) {
    Write-Info "Launching $AppId ..."
    $code = Invoke-Adb -Adb $Adb -DeviceSerial $Device -Args @(
        "shell", "monkey", "-p", $AppId, "-c", "android.intent.category.LAUNCHER", "1"
    )
    if ($code -ne 0) {
        Write-Warn "Launch via monkey failed (exit $code). Try opening the app icon manually."
        return
    }
    Write-Ok "Launch requested"
}

# --- main ---
Assert-Command "gh" "Install GitHub CLI: https://cli.github.com/"
Assert-Command "git" "Install git and ensure it is on PATH."

$repoSlug = Resolve-RepoSlug $Repo
Write-Info "Repo: $repoSlug"

# Auth check (non-fatal detail; API will fail clearly)
$auth = & gh auth status 2>&1
if ($LASTEXITCODE -ne 0) {
    Fail "GitHub CLI is not authenticated. Run: gh auth login`n$auth" 4
}

$adbPath = Resolve-AdbPath
Write-Info "adb: $adbPath"

$deviceId = Select-Device -Adb $adbPath -PreferredSerial $Serial
Write-Ok "Device: $deviceId"

$apkPath = $null
if ($SkipDownload) {
    $apkPath = Get-ChildItem -Path $WorkDir -Recurse -Filter *.apk -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $apkPath) {
        Fail "SkipDownload set but no APK under $WorkDir" 6
    }
    Write-Info "Reusing $apkPath"
} else {
    $artifact = Get-LatestDebugArtifact $repoSlug
    Write-Ok ("Latest artifact: {0} @ {1} (branch {2})" -f `
        $artifact.name, $artifact.created_at, $artifact.workflow_run.head_branch)

    $marker = Join-Path $WorkDir ".last-artifact-id"
    $cachedApk = Get-ChildItem -Path $WorkDir -Recurse -Filter *.apk -File -ErrorAction SilentlyContinue |
        Sort-Object Length -Descending | Select-Object -First 1
    if ($cachedApk -and (Test-Path $marker) -and ((Get-Content $marker -Raw).Trim() -eq "$($artifact.id)")) {
        Write-Info "Artifact unchanged (id $($artifact.id)), skipping download."
        $apkPath = $cachedApk.FullName
    } else {
        $apkPath = Get-ApkFromArtifact -RepoSlug $repoSlug -Artifact $artifact -Dir $WorkDir
        New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
        Set-Content -Path $marker -Value "$($artifact.id)" -NoNewline
    }
}

Install-Apk -Adb $adbPath -Device $deviceId -ApkPath $apkPath

if (-not $NoLaunch) {
    Start-App -Adb $adbPath -Device $deviceId -AppId $PackageId
}

Write-Ok "Done."
