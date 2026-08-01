# adb-pair-qr.ps1 — re-pair this PC to an Android phone for wireless debugging
# using the host-initiated QR flow, so no pairing code has to be read off the
# phone screen.
#
# Why this exists:
#   The normal `adb pair <ip>:<port> <code>` flow requires reading a 6-digit
#   code off the phone, and the pairing dialog may show an address on a
#   VPN/secondary interface that the PC cannot actually reach (attempts fail
#   with "protocol fault"). The QR flow inverts this: the PC generates the
#   code itself and renders it as a QR (`WIFI:T:ADB;S:<service>;P:<code>;;`).
#   The phone's "Pair device with QR code" scanner parses it and starts its
#   OWN pairing server, advertised over mDNS on the Wi-Fi interface. We find
#   that service via `adb mdns services` and run `adb pair` against the mDNS
#   endpoint — no typing, and no unreachable-address trap.
#
# Flow:
#   1) Exit early if a device is already attached (nothing to pair).
#   2) Ensure the adb server + mDNS daemon are up; snapshot baseline pairing
#      services so stale entries from old dialogs are never picked.
#   3) Generate the QR (Python + qrcode, bootstrapped into a local venv on
#      first run) and open it in the default image viewer.
#   4) Poll mDNS for the phone's pairing service — prefer an exact instance
#      name match, otherwise any endpoint that wasn't in the baseline.
#   5) Run `adb pair`, print the result, clean up the QR image.
#
# Usage:  .\scripts\adb-pair-qr.ps1 [-TimeoutSeconds 120]

param(
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'

function Write-Step([string]$Message) {
    Write-Host $Message -ForegroundColor Cyan
}

function Get-PairingServices {
    # Returns [PSCustomObject]@{ Instance; Endpoint } for every advertised
    # _adb-tls-pairing._tcp service. Handles the "List of discovered mdns
    # services" header and any stderr noise.
    $result = @()
    foreach ($line in @(adb mdns services 2>&1 | ForEach-Object { $_.ToString() })) {
        if ($line -match '^(\S+)\s+_adb-tls-pairing\._tcp\s+(\d{1,3}(\.\d{1,3}){3}:\d+)$') {
            $result += [PSCustomObject]@{
                Instance = $Matches[1]
                Endpoint = $Matches[2]
            }
        }
    }
    return $result
}

# --- 0) Nothing to do when a device is already attached ----------------------
$existing = @(adb devices 2>&1 | Select-Object -Skip 1 | Where-Object { $_ -match '^\S+\s+(device|offline|unauthorized)' })
if ($existing.Count -gt 0) {
    Write-Host "A device is already attached — nothing to pair." -ForegroundColor Green
    adb devices -l
    exit 0
}

# --- 1) adb server + mDNS daemon ----------------------------------------------
$null = adb start-server 2>&1
Start-Sleep -Milliseconds 500

# --- 2) Baseline pairing services (pre-scan) ----------------------------------
$baseline = @(Get-PairingServices | ForEach-Object { $_.Endpoint })
Write-Step "Scanning for stale pairing services... (baseline: $($baseline -join ', '))"

# --- 3) QR generation ----------------------------------------------------------
# Python + qrcode, installed once into a per-user venv (first run downloads
# qrcode + pillow from PyPI; needs network that one time).
$venvRoot = Join-Path $env:LOCALAPPDATA 'Inkleaf\adbqr-venv'
$venvPython = Join-Path $venvRoot 'Scripts\python.exe'
if (-not (Test-Path $venvPython)) {
    $pythonCmd = Get-Command python -ErrorAction SilentlyContinue
    if (-not $pythonCmd) {
        Write-Error "python was not found on PATH — required once to render the QR."
        exit 1
    }
    Write-Step "First run: creating Python venv at $venvRoot ..."
    & python -m venv $venvRoot
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $venvPython)) {
        Write-Error "Failed to create the Python venv."
        exit 1
    }
    & $venvPython -m pip install --quiet 'qrcode[pil]'
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Failed to install 'qrcode[pil]'. Check network access and retry."
        exit 1
    }
}
& $venvPython -c 'import qrcode' 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Error "qrcode is not importable in $venvRoot. Delete that folder and re-run to reinstall."
    exit 1
}

$pngPath = Join-Path $env:TEMP 'adb-pair-qr.png'
$genScript = Join-Path $env:TEMP 'adbqr-gen.py'
@'
import qrcode, secrets, sys
name = "adbqr-" + secrets.token_hex(4)
code = secrets.randbelow(900000) + 100000
payload = f"WIFI:T:ADB;S:{name};P:{code};;"
qr = qrcode.QRCode(version=None, box_size=12, border=4)
qr.add_data(payload)
qr.make(fit=True)
img = qr.make_image(fill_color="black", back_color="white")
img.save(sys.argv[1])
print(f"NAME={name}")
print(f"CODE={code}")
print(f"PAYLOAD={payload}")
'@ | Set-Content -Path $genScript -Encoding utf8

$genOutput = & $venvPython $genScript $pngPath 2>&1 | Out-String
$name = ($genOutput | Select-String -Pattern '^NAME=(.+)$').Matches.Groups[1].Value
$code = ($genOutput | Select-String -Pattern '^CODE=(.+)$').Matches.Groups[1].Value
if (-not $name -or -not $code -or -not (Test-Path $pngPath)) {
    Write-Error "QR generation failed. Output: $genOutput"
    exit 1
}

try {
    Start-Process $pngPath
    Write-Step "QR opened in the image viewer (service: $name, code: $code)."
    Write-Host ""
    Write-Host "On the phone: Developer options > Wireless debugging >" -ForegroundColor Yellow
    Write-Host "              \"Pair device with QR code\" and scan the QR." -ForegroundColor Yellow
    Write-Host ""

    # --- 4) Poll for the phone's pairing service -------------------------------
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $endpoint = $null
    $lastNotice = Get-Date
    while ((Get-Date) -lt $deadline) {
        $services = Get-PairingServices
        # Prefer an exact instance-name match (our QR's S: field).
        $match = $services | Where-Object { $_.Instance -eq $name } | Select-Object -First 1
        if (-not $match) {
            # Fall back to any endpoint that wasn't advertised before the scan.
            $match = $services | Where-Object { $_.Endpoint -notin $baseline } | Select-Object -First 1
        }
        if ($match) {
            $endpoint = $match.Endpoint
            break
        }
        if (((Get-Date) - $lastNotice).TotalSeconds -ge 15) {
            $lastNotice = Get-Date
            Write-Host "Still waiting for the pairing service over mDNS..." -ForegroundColor DarkGray
        }
        Start-Sleep -Seconds 2
    }

    if (-not $endpoint) {
        Write-Error "No pairing service appeared within ${TimeoutSeconds}s. Make sure the phone scanned the QR and is on the same Wi-Fi as this PC."
        exit 1
    }

    # --- 5) Pair ----------------------------------------------------------------
    Write-Step "Pairing with $endpoint ..."
    $pairOutput = (adb pair $endpoint $code 2>&1 | Out-String).Trim()
    Write-Host $pairOutput
    if ($pairOutput -match 'Successfully paired') {
        Write-Host ""
        Write-Host "Paired! The device should auto-connect via mDNS within a few seconds." -ForegroundColor Green
        Start-Sleep -Seconds 4
        adb devices -l
        Write-Host ""
        Write-Host "Run .\scripts\adb-connect.ps1 to verify." -ForegroundColor Green
        exit 0
    }
    else {
        Write-Host ""
        Write-Host "Pairing failed. If the error is 'protocol fault', the phone may be" -ForegroundColor Yellow
        Write-Host "advertising the pairing service on an unreachable interface — disable" -ForegroundColor Yellow
        Write-Host "VPN / hotspot / dual-Wi-Fi on the phone and retry." -ForegroundColor Yellow
        exit 1
    }
}
finally {
    Remove-Item $pngPath -ErrorAction SilentlyContinue
}
