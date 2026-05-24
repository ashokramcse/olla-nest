@echo off
REM Olla Nest — Local Whisper STT Server Setup ^& Launcher
REM Supports: Windows 10/11, Windows Server 2016/2019/2022
REM
REM Usage:
REM   scripts\start_whisper.bat
REM
REM Environment variables:
REM   set WHISPER_MODEL=small   (default: base)
REM   set WHISPER_PORT=8765     (default: 8765)

setlocal EnableDelayedExpansion

set SCRIPT_DIR=%~dp0
set VENV=%SCRIPT_DIR%venv
if not defined WHISPER_PORT set WHISPER_PORT=8765
if not defined WHISPER_MODEL set WHISPER_MODEL=base

echo [whisper] Platform: Windows
echo [whisper] Script dir: %SCRIPT_DIR%

REM ── Locate Python 3.11 or 3.12 (faster-whisper needs <= 3.12) ───────────────
REM  faster-whisper's 'av' dependency has no wheels for Python 3.13+.
set PYTHON_BIN=
for %%c in (py python3.11 python3.12 python3.10 python3 python) do (
    if "%PYTHON_BIN%"=="" (
        where %%c >nul 2>&1
        if not errorlevel 1 (
            REM Check version is 3.9–3.12
            for /f "tokens=2 delims=." %%m in ('%%c --version 2^>^&1') do (
                if "%%m"=="9"  set PYTHON_BIN=%%c
                if "%%m"=="10" set PYTHON_BIN=%%c
                if "%%m"=="11" set PYTHON_BIN=%%c
                if "%%m"=="12" set PYTHON_BIN=%%c
            )
        )
    )
)

if "%PYTHON_BIN%"=="" (
    echo [whisper] Python 3.9-3.12 not found. Trying winget to install Python 3.11...
    winget install --id Python.Python.3.11 -e --silent
    set PYTHON_BIN=python3.11
)

if "%PYTHON_BIN%"=="" (
    echo [whisper] ERROR: Python 3.11 or 3.12 required.
    echo [whisper] Download from https://www.python.org/downloads/release/python-3119/
    echo [whisper] Tick "Add Python to PATH" during install, then re-run.
    pause
    exit /b 1
)

for /f "tokens=*" %%v in ('"%PYTHON_BIN%" --version 2^>^&1') do echo [whisper] %%v

REM ── Check ffmpeg ─────────────────────────────────────────────────────────────
where ffmpeg >nul 2>&1
if errorlevel 1 (
    echo [whisper] ffmpeg not found — attempting install via winget...
    winget install --id Gyan.FFmpeg -e --silent >nul 2>&1
    if errorlevel 1 (
        echo [whisper] WARNING: Could not auto-install ffmpeg.
        echo [whisper] Install manually from https://ffmpeg.org/download.html
        echo [whisper] and add it to your PATH, then re-run this script.
    ) else (
        echo [whisper] ffmpeg installed via winget.
        REM Refresh PATH for this session
        for /f "tokens=*" %%p in ('where ffmpeg 2^>nul') do set FFMPEG_PATH=%%p
    )
)

REM ── Create venv ───────────────────────────────────────────────────────────────
if not exist "%VENV%\Scripts\python.exe" (
    echo [whisper] Creating virtual environment at %VENV% ...
    %PYTHON_BIN% -m venv "%VENV%"
    if errorlevel 1 (
        echo [whisper] ERROR: Failed to create virtual environment.
        echo [whisper] Make sure 'python3-venv' is installed.
        pause
        exit /b 1
    )
)

set PY="%VENV%\Scripts\python.exe"
set PIP="%VENV%\Scripts\pip.exe"

REM ── Upgrade pip ───────────────────────────────────────────────────────────────
%PY% -m pip install --upgrade pip --quiet

REM ── Install Python packages ───────────────────────────────────────────────────
%PY% -c "import faster_whisper" >nul 2>&1
if errorlevel 1 (
    echo [whisper] Installing faster-whisper...
    %PIP% install faster-whisper --quiet
)

%PY% -c "import multipart" >nul 2>&1
if errorlevel 1 (
    echo [whisper] Installing python-multipart...
    %PIP% install python-multipart --quiet
)

REM ── Launch server ─────────────────────────────────────────────────────────────
echo [whisper] Starting Whisper STT server on http://0.0.0.0:%WHISPER_PORT%
set WHISPER_PORT=%WHISPER_PORT%
set WHISPER_MODEL=%WHISPER_MODEL%
%PY% "%SCRIPT_DIR%whisper_server.py"

pause
