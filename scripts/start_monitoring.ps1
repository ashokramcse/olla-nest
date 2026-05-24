# =============================================================================
# Olla Nest — Grafana + Loki Monitoring Stack  (Windows PowerShell)
# =============================================================================
# Supports: Windows 10/11, Windows Server 2016 / 2019 / 2022
#           Run as normal user — no admin required.
#
# Usage:
#   .\scripts\start_monitoring.ps1             # install (once) + start
#   .\scripts\start_monitoring.ps1 -Stop       # stop both services
#   .\scripts\start_monitoring.ps1 -Status     # show running status
#   .\scripts\start_monitoring.ps1 -Restart    # stop then start
# =============================================================================
param(
    [switch]$Stop,
    [switch]$Status,
    [switch]$Restart
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptDir      = Split-Path -Parent $MyInvocation.MyCommand.Path
$MonitoringDir  = Join-Path $ScriptDir "monitoring"
$LokiDir        = Join-Path $MonitoringDir "loki-bin"
$LokiData       = Join-Path $MonitoringDir "loki-data"
$GrafanaDir     = Join-Path $MonitoringDir "grafana"
$PidsDir        = Join-Path $MonitoringDir "pids"

$LokiVersion    = "3.3.2"
$GrafanaVersion = "11.4.0"
$LokiPort       = 3100
$GrafanaPort    = 3200

$LokiBin        = Join-Path $LokiDir    "loki.exe"
$GrafanaBin     = Join-Path $GrafanaDir "bin\grafana.exe"

# ── Helpers ──────────────────────────────────────────────────────────────────

function Log   { param($msg) Write-Host "[monitoring] $msg" }
function Warn  { param($msg) Write-Host "[monitoring] WARNING: $msg" -ForegroundColor Yellow }
function Err   { param($msg) Write-Host "[monitoring] ERROR: $msg" -ForegroundColor Red; exit 1 }

function Port-InUse { param($port)
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    return $null -ne $conn
}

function Download { param($url, $dest)
    Log "Downloading: $url"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
}

function Loki-Ready {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$LokiPort/ready" -UseBasicParsing -TimeoutSec 2
        return $r.Content -match "^ready"
    } catch { return $false }
}

function Grafana-Ready {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$GrafanaPort/api/health" -UseBasicParsing -TimeoutSec 2
        return $r.StatusCode -eq 200
    } catch { return $false }
}

# ── Detect architecture ───────────────────────────────────────────────────────

$Arch = if ([System.Environment]::GetEnvironmentVariable("PROCESSOR_ARCHITECTURE") -match "ARM") {
    "arm64"
} else {
    "amd64"
}

# ── --Stop ────────────────────────────────────────────────────────────────────

function Stop-All {
    Log "Stopping Loki and Grafana..."
    @("loki", "grafana") | ForEach-Object {
        $proc = Get-Process -Name $_ -ErrorAction SilentlyContinue
        if ($proc) {
            $proc | Stop-Process -Force
            Log "  Stopped $_ (PID $($proc.Id))"
        } else {
            Log "  $_ not running"
        }
        $pidFile = Join-Path $PidsDir "$_.pid"
        if (Test-Path $pidFile) { Remove-Item $pidFile -Force }
    }
}

# ── --Status ──────────────────────────────────────────────────────────────────

function Show-Status {
    Write-Host ""
    Write-Host "  Olla Nest — Monitoring Status" -ForegroundColor Cyan
    Write-Host "  ─────────────────────────────"
    if (Loki-Ready) {
        Write-Host "  ● Loki     RUNNING  →  http://localhost:$LokiPort" -ForegroundColor Green
    } elseif (Port-InUse $LokiPort) {
        Write-Host "  ◑ Loki     STARTING →  http://localhost:$LokiPort (warming up)" -ForegroundColor Yellow
    } else {
        Write-Host "  ○ Loki     stopped" -ForegroundColor Gray
    }
    if (Grafana-Ready) {
        Write-Host "  ● Grafana  RUNNING  →  http://localhost:$GrafanaPort  (admin / CHANGE_ME_ON_FIRST_BOOT)" -ForegroundColor Green
    } elseif (Port-InUse $GrafanaPort) {
        Write-Host "  ◑ Grafana  STARTING →  http://localhost:$GrafanaPort (warming up)" -ForegroundColor Yellow
    } else {
        Write-Host "  ○ Grafana  stopped" -ForegroundColor Gray
    }
    Write-Host ""
}

# ── Entry point ───────────────────────────────────────────────────────────────

if ($Stop)    { Stop-All;    exit 0 }
if ($Status)  { Show-Status; exit 0 }
if ($Restart) { Stop-All; Start-Sleep 2 }

# ── Directory setup ───────────────────────────────────────────────────────────

@($LokiDir, "$LokiData\chunks", "$LokiData\rules", "$LokiData\compactor", $PidsDir) | ForEach-Object {
    if (-not (Test-Path $_)) { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
}

# ── Install Loki ─────────────────────────────────────────────────────────────

if (-not (Test-Path $LokiBin)) {
    Log "Installing Loki v$LokiVersion (windows/$Arch)..."
    $tmpZip  = Join-Path $LokiDir "loki.zip"
    $lokiUrl = "https://github.com/grafana/loki/releases/download/v$LokiVersion/loki-windows-$Arch.exe.zip"
    Download $lokiUrl $tmpZip
    Expand-Archive -Path $tmpZip -DestinationPath $LokiDir -Force
    Remove-Item $tmpZip -Force
    # Rename loki-windows-<arch>.exe → loki.exe
    Get-ChildItem $LokiDir -Filter "loki-windows-*.exe" | Rename-Item -NewName "loki.exe"
    Log "Loki installed: $LokiBin"
} else {
    Log "Loki already installed — skipping download"
}

# ── Install Grafana ───────────────────────────────────────────────────────────

if (-not (Test-Path $GrafanaBin)) {
    Log "Installing Grafana v$GrafanaVersion (windows/$Arch)..."
    $tmpZip      = Join-Path $env:TEMP "olla-nest-grafana.zip"
    $grafanaUrl  = "https://dl.grafana.com/oss/release/grafana-$GrafanaVersion.windows-$Arch.zip"
    Download $grafanaUrl $tmpZip
    # Extract to temp then move (zip contains "grafana-<ver>.windows-<arch>" folder)
    $tmpExtract  = Join-Path $env:TEMP "olla-nest-grafana-extract"
    if (Test-Path $tmpExtract) { Remove-Item $tmpExtract -Recurse -Force }
    Expand-Archive -Path $tmpZip -DestinationPath $tmpExtract -Force
    Remove-Item $tmpZip -Force
    $extracted = Get-ChildItem $tmpExtract -Directory | Select-Object -First 1
    Move-Item $extracted.FullName $GrafanaDir
    Remove-Item $tmpExtract -Recurse -Force
    Log "Grafana installed: $GrafanaDir"
} else {
    Log "Grafana already installed — skipping download"
}

# ── Sync provisioning files ───────────────────────────────────────────────────

$ProvSrc = Join-Path $MonitoringDir "grafana-provisioning"
$ProvDst = Join-Path $GrafanaDir   "provisioning"

@("datasources", "dashboards") | ForEach-Object {
    $dst = Join-Path $ProvDst $_
    if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
    Copy-Item (Join-Path $ProvSrc $_) $dst -Recurse
}
Log "Grafana provisioning synced → $ProvDst"

# ── Start Loki ────────────────────────────────────────────────────────────────

if (Port-InUse $LokiPort) {
    Log "Loki already running on port $LokiPort — skipping."
} else {
    Log "Starting Loki on port $LokiPort..."
    $env:LOKI_DATA_DIR = $LokiData
    $lokiLog = Join-Path $MonitoringDir "loki.log"
    $lokiProc = Start-Process -FilePath $LokiBin `
        -ArgumentList "-config.file=`"$MonitoringDir\loki-config.yml`"", "-config.expand-env=true" `
        -RedirectStandardOutput $lokiLog `
        -RedirectStandardError  $lokiLog `
        -WindowStyle Hidden -PassThru
    $lokiProc.Id | Out-File (Join-Path $PidsDir "loki.pid") -Encoding utf8

    Log "Waiting for Loki ingester to become ready (up to 35s)..."
    $ready = $false
    for ($i = 0; $i -lt 18; $i++) {
        Start-Sleep 2
        if (Loki-Ready) { Log "Loki ready (PID $($lokiProc.Id))  →  http://localhost:$LokiPort"; $ready = $true; break }
    }
    if (-not $ready) { Warn "Loki not ready — check $lokiLog" }
}

# ── Start Grafana ─────────────────────────────────────────────────────────────

if (Port-InUse $GrafanaPort) {
    Log "Grafana already running on port $GrafanaPort — skipping."
} else {
    Log "Starting Grafana on port $GrafanaPort..."
    $grafanaLog = Join-Path $MonitoringDir "grafana.log"
    $grafanaArgs = @(
        "server",
        "--homepath=`"$GrafanaDir`"",
        "cfg:server.http_port=$GrafanaPort",
        "cfg:server.domain=localhost",
        "cfg:security.admin_user=admin",
        "cfg:security.admin_password=CHANGE_ME_ON_FIRST_BOOT",
        "cfg:analytics.reporting_enabled=false",
        "cfg:paths.provisioning=`"$ProvDst`""
    )
    $grafanaProc = Start-Process -FilePath $GrafanaBin `
        -ArgumentList $grafanaArgs `
        -RedirectStandardOutput $grafanaLog `
        -RedirectStandardError  $grafanaLog `
        -WindowStyle Hidden -PassThru
    $grafanaProc.Id | Out-File (Join-Path $PidsDir "grafana.pid") -Encoding utf8

    Log "Waiting for Grafana to become ready (up to 20s)..."
    $ready = $false
    for ($i = 0; $i -lt 10; $i++) {
        Start-Sleep 2
        if (Grafana-Ready) { Log "Grafana ready (PID $($grafanaProc.Id))  →  http://localhost:$GrafanaPort"; $ready = $true; break }
    }
    if (-not $ready) { Warn "Grafana not ready — check $grafanaLog" }
}

# ── Summary ───────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "  +------------------------------------------------------------------+" -ForegroundColor Cyan
Write-Host "  |           Olla Nest Monitoring — Ready                           |" -ForegroundColor Cyan
Write-Host "  +------------------------------------------------------------------+" -ForegroundColor Cyan
Write-Host "  |  Loki API    ->  http://localhost:$LokiPort" -ForegroundColor White
Write-Host "  |  Grafana UI  ->  http://localhost:$GrafanaPort  (admin / CHANGE_ME_ON_FIRST_BOOT)" -ForegroundColor White
Write-Host "  |  Dashboard   ->  Olla Nest - Logs (auto-provisioned)" -ForegroundColor White
Write-Host "  +------------------------------------------------------------------+" -ForegroundColor Cyan
Write-Host "  |  Stop:     .\scripts\start_monitoring.ps1 -Stop" -ForegroundColor Gray
Write-Host "  |  Status:   .\scripts\start_monitoring.ps1 -Status" -ForegroundColor Gray
Write-Host "  +------------------------------------------------------------------+" -ForegroundColor Cyan
Write-Host ""
