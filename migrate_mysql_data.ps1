# ============================================================
# MySQL 5.7 Data Directory Migration Script
# From: C:\ProgramData\MySQL\MySQL Server 5.7\Data
# To:   D:\AppData\MySQLData
#
# Run with ADMIN PowerShell:
#   powershell -ExecutionPolicy Bypass -File "D:\Demo\YunPingTai\migrate_mysql_data.ps1"
# ============================================================

$ErrorActionPreference = "Stop"

Write-Host "========== MySQL Data Migration ==========" -ForegroundColor Cyan

# ---- 1. Auto-detect MySQL service ----
$svc = Get-Service | Where-Object { $_.Name -like 'mysql*' } | Select-Object -First 1
if (-not $svc) {
    Write-Host "[ERROR] No MySQL service found." -ForegroundColor Red
    exit 1
}
$svcName = $svc.Name
Write-Host "[1] MySQL service: $svcName (status: $($svc.Status))"

# ---- 2. Define paths ----
$base = "C:\ProgramData\MySQL\MySQL Server 5.7"
$src  = Join-Path $base "Data"
$myIni = Join-Path $base "my.ini"
$dst  = "D:\AppData\MySQLData"

if (-not (Test-Path $src))  { Write-Host "[ERROR] Data dir not found: $src" -ForegroundColor Red;  exit 1 }
if (-not (Test-Path $myIni)){ Write-Host "[ERROR] my.ini not found: $myIni" -ForegroundColor Red; exit 1 }
if (Test-Path $dst) {
    $itemCount = (Get-ChildItem $dst -Force | Measure-Object).Count
    if ($itemCount -gt 0) {
        Write-Host "[ERROR] Target dir not empty: $dst" -ForegroundColor Red
        exit 1
    }
}

# ---- 3. Backup my.ini ----
$bak = "$myIni.bak_$(Get-Date -Format yyyyMMddHHmmss)"
Copy-Item $myIni $bak -Force
Write-Host "[3] Config backed up to: $bak"

# ---- 4. Stop MySQL service ----
if ($svc.Status -eq 'Running') {
    Stop-Service $svcName -Force
    Start-Sleep 3
    $svc.Refresh()
    if ($svc.Status -ne 'Stopped') {
        Write-Host "[ERROR] Failed to stop service $svcName" -ForegroundColor Red
        exit 1
    }
    Write-Host "[4] Service stopped."
} else {
    Write-Host "[4] Service was not running, skip stop."
}

# ---- 5. Create target dir and copy data (robocopy keeps attrs/timestamps) ----
New-Item -ItemType Directory -Path $dst -Force | Out-Null
robocopy $src $dst /E /COPY:DAT /DCOPY:T /R:1 /W:1 /NFL /NDL /NP | Out-Null
if ($LASTEXITCODE -ge 8) {
    Write-Host "[ERROR] robocopy failed, exit code $LASTEXITCODE" -ForegroundColor Red
    exit 1
}
Write-Host "[5] Data copied to $dst (robocopy code $LASTEXITCODE, 0-7 = OK)"

# ---- 6. Grant access to NETWORK SERVICE (default MySQL service account) ----
icacls $dst /grant "*S-1-5-20:(OI)(CI)F" /T | Out-Null
Write-Host "[6] Target dir granted to service account."

# ---- 7. Update datadir in my.ini ----
$newDir = $dst -replace '\\', '/'
$content = Get-Content $myIni -Raw
$content = $content -replace '(?im)^\s*datadir\s*=.*$', "datadir=`"$newDir`""
Set-Content -Path $myIni -Value $content -Encoding ASCII
Write-Host "[7] my.ini datadir updated to: $newDir"

# ---- 8. Start service ----
Start-Service $svcName
Start-Sleep 5
$svc.Refresh()
Write-Host "[8] Service status after start: $($svc.Status)"

# ---- 9. Verify ----
if ($svc.Status -eq 'Running') {
    Write-Host "======================================================" -ForegroundColor Green
    Write-Host "MIGRATION OK. Service is RUNNING." -ForegroundColor Green
    Write-Host "Verify in Navicat: SELECT VERSION(); SHOW VARIABLES LIKE 'datadir';" -ForegroundColor Green
    Write-Host "datadir should show: D:/AppData/MySQLData" -ForegroundColor Green
    Write-Host "======================================================" -ForegroundColor Green
} else {
    Write-Host "[ERROR] Service failed to start. Check Windows Event Log." -ForegroundColor Red
    Write-Host "Recovery: restore my.ini from backup ($bak), copy data back, start service." -ForegroundColor Yellow
    exit 1
}

Write-Host "========== Done ==========" -ForegroundColor Cyan
