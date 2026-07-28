# Connect to Android wireless debugging devices discovered via mDNS.
#
# Why the same phone used to show up twice:
#   Modern platform-tools auto-connects `_adb-tls-connect` (ADB_MDNS_AUTO_CONNECT
#   defaults to "adb-tls-connect"). That appears as serial:
#     adb-<id>._adb-tls-connect._tcp
#   Calling `adb connect host:port` on the same endpoint adds another serial:
#     host:port
#   One phone, two transports → every adb command needs -s.
#
# This script:
#   1) Discovers wireless devices via `adb mdns services`
#   2) Skips `adb connect` if either serial is already online
#   3) Falls back to `adb connect host:port` only when nothing is online
#   4) Drops a host:port twin when the mDNS serial is also present

Write-Host "Scanning for wireless debugging devices via mDNS..." -ForegroundColor Cyan

function Get-AdbDeviceSerials {
    adb devices | Select-Object -Skip 1 | ForEach-Object {
        if ($_ -match '^\s*(\S+)\s+(device|offline|unauthorized)') {
            [PSCustomObject]@{
                Serial = $Matches[1]
                State  = $Matches[2]
            }
        }
    }
}

function Test-AdbSerialPresent {
    param(
        [string]$Serial,
        [object[]]$Devices
    )
    return $null -ne ($Devices | Where-Object { $_.Serial -eq $Serial })
}

# mdns line shape:
#   adb-73f28198-Lwb56E    _adb-tls-connect._tcp    192.168.1.4:38515
# device serial shape (auto-connect):
#   adb-73f28198-Lwb56E._adb-tls-connect._tcp
$serviceLines = adb mdns services 2>&1 |
    Select-String -Pattern '_adb-tls-connect\._tcp\s+\d{1,3}(\.\d{1,3}){3}:\d+'

if (-not $serviceLines) {
    Write-Error "No active wireless debugging device found. Turn on Wireless Debugging in developer options (and pair once if needed)."
    exit 1
}

$found = 0
foreach ($line in $serviceLines) {
    $parts = @(($line.ToString().Trim() -split '\s+') | Where-Object { $_ })
    if ($parts.Count -lt 3) { continue }

    $instance = $parts[0]
    $serviceType = $parts[1]
    $ipPort = $parts[-1]
    if ($serviceType -notmatch '_adb-tls-connect\._tcp') { continue }
    if ($ipPort -notmatch '^\d{1,3}(\.\d{1,3}){3}:\d+$') {
        Write-Host "Skip unparseable service line: $line" -ForegroundColor DarkYellow
        continue
    }

    # Match the serial adb actually uses for mDNS auto-connect.
    $mdnsSerial = "$instance.$serviceType"
    $found++

    $devices = @(Get-AdbDeviceSerials)
    $hasMdns = Test-AdbSerialPresent -Serial $mdnsSerial -Devices $devices
    $hasIp = Test-AdbSerialPresent -Serial $ipPort -Devices $devices

    if ($hasMdns -or $hasIp) {
        Write-Host "Already connected ($ipPort) — skip adb connect to avoid a duplicate transport." -ForegroundColor Green
    }
    else {
        Write-Host "Found $mdnsSerial at $ipPort. Connecting..." -ForegroundColor Green
        adb connect $ipPort | Write-Host
        # Auto-connect may attach the mDNS serial shortly after.
        Start-Sleep -Milliseconds 400
        $devices = @(Get-AdbDeviceSerials)
        $hasMdns = Test-AdbSerialPresent -Serial $mdnsSerial -Devices $devices
        $hasIp = Test-AdbSerialPresent -Serial $ipPort -Devices $devices
    }

    # Prefer the mDNS auto-connect serial; drop host:port twin.
    if ($hasMdns -and $hasIp) {
        Write-Host "Duplicate transport — disconnecting $ipPort, keeping $mdnsSerial" -ForegroundColor Yellow
        adb disconnect $ipPort | Write-Host
    }
}

if ($found -eq 0) {
    Write-Error "mDNS listed services but none had a usable host:port."
    exit 1
}

Write-Host "`nCurrent ADB devices:" -ForegroundColor Yellow
adb devices -l
