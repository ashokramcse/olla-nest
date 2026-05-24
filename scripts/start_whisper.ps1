# Olla Nest — Local Whisper STT Server Setup & Launcher
# Supports: Windows 10/11, Windows Server 2016/2019/2022
#
# Usage (run in PowerShell as Administrator for first-time setup):
#   .\scripts\start_whisper.ps1
#
# Environment variables:
#   $env:WHISPER_MODEL = "small"   (default: base)
#   $env:WHISPER_PORT  = "8765"    (default: 8765)

$ErrorActionPreference = "Stop"
$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$VenvDir    = Join-Path $ScriptDir "venv"
$ServerScript = Join-Path $ScriptDir "whisper_server.py"
$Port       = if ($env:WHISPER_PORT)  { $env:WHISPER_PORT  } else { "8765" }
$Model      = if ($env:WHISPER_MODEL) { $env:WHISPER_MODEL } else { "base" }

Write-Host "[whisper] Platform: Windows (PowerShell)" -ForegroundColor Cyan
Write-Host "[whisper] Script dir: $ScriptDir"

# ── Locate Python ─────────────────────────────────────────────────────────────
$PythonBin = $null
foreach ($candidate in @("python", "python3", "py")) {
    try {
        $ver = & $candidate --version 2>&1
        if ($LASTEXITCODE -eq 0) { $PythonBin = $candidate; break }
    } catch {}
}
if (-not $PythonBin) {
    Write-Host "[whisper] ERROR: Python not found. Download from https://www.python.org/downloads/" -ForegroundColor Red
    Write-Host "[whisper]   Tick 'Add Python to PATH' during install, then re-run." -ForegroundColor Yellow
    exit 1
}
Write-Host "[whisper] Python: $(& $PythonBin --version 2>&1)"

# ── Check / install ffmpeg ────────────────────────────────────────────────────
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    Write-Host "[whisper] ffmpeg not found — trying winget..." -ForegroundColor Yellow
    try {
        winget install --id Gyan.FFmpeg -e --silent
        Write-Host "[whisper] ffmpeg installed." -ForegroundColor Green
    } catch {
        Write-Host "[whisper] WARNING: Could not auto-install ffmpeg." -ForegroundColor Yellow
        Write-Host "[whisper]   Install manually: https://ffmpeg.org/download.html" -ForegroundColor Yellow
        Write-Host "[whisper]   Add ffmpeg to PATH, then re-run." -ForegroundColor Yellow
    }
}

# ── Create venv ───────────────────────────────────────────────────────────────
$VenvPython = Join-Path $VenvDir "Scripts\python.exe"
if (-not (Test-Path $VenvPython)) {
    Write-Host "[whisper] Creating virtual environment at $VenvDir ..."
    & $PythonBin -m venv $VenvDir
}

# ── Upgrade pip ───────────────────────────────────────────────────────────────
& $VenvPython -m pip install --upgrade pip --quiet

# ── Install Python packages ───────────────────────────────────────────────────
$needsFasterWhisper = & $VenvPython -c "import faster_whisper" 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "[whisper] Installing faster-whisper..."
    & $VenvPython -m pip install faster-whisper --quiet
}

$needsMultipart = & $VenvPython -c "import multipart" 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "[whisper] Installing python-multipart..."
    & $VenvPython -m pip install python-multipart --quiet
}

# ── Launch server ─────────────────────────────────────────────────────────────
Write-Host "[whisper] Starting Whisper STT server on http://0.0.0.0:$Port" -ForegroundColor Green
$env:WHISPER_PORT  = $Port
$env:WHISPER_MODEL = $Model
& $VenvPython $ServerScript
