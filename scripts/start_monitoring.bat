@echo off
:: =============================================================================
:: Olla Nest — Grafana + Loki Monitoring Stack  (Windows CMD)
:: =============================================================================
:: Supports: Windows 10/11, Windows Server 2016/2019/2022
::
:: Usage:
::   scripts\start_monitoring.bat           install (once) + start
::   scripts\start_monitoring.bat --stop    stop both services
::   scripts\start_monitoring.bat --status  show running status
::
:: All data stored inside the project — no admin rights required:
::   scripts\monitoring\loki-bin\     Loki binary
::   scripts\monitoring\grafana\      Grafana installation
::   scripts\monitoring\loki-data\    Loki log storage
::   scripts\monitoring\pids\         PID files
:: =============================================================================
setlocal EnableDelayedExpansion

set SCRIPT_DIR=%~dp0
set MONITORING_DIR=%SCRIPT_DIR%monitoring
set LOKI_DIR=%MONITORING_DIR%\loki-bin
set LOKI_DATA=%MONITORING_DIR%\loki-data
set GRAFANA_DIR=%MONITORING_DIR%\grafana
set PIDS_DIR=%MONITORING_DIR%\pids

set LOKI_VERSION=3.3.2
set GRAFANA_VERSION=11.4.0
set LOKI_PORT=3100
set GRAFANA_PORT=3200

set LOKI_BIN=%LOKI_DIR%\loki.exe
set GRAFANA_BIN=%GRAFANA_DIR%\bin\grafana.exe

:: ── Argument handling ────────────────────────────────────────────────────────

if "%1"=="--stop"   goto :STOP_ALL
if "%1"=="--status" goto :SHOW_STATUS

:: ── Directory setup ──────────────────────────────────────────────────────────

if not exist "%LOKI_DIR%"              mkdir "%LOKI_DIR%"
if not exist "%LOKI_DATA%\chunks"      mkdir "%LOKI_DATA%\chunks"
if not exist "%LOKI_DATA%\rules"       mkdir "%LOKI_DATA%\rules"
if not exist "%LOKI_DATA%\compactor"   mkdir "%LOKI_DATA%\compactor"
if not exist "%PIDS_DIR%"              mkdir "%PIDS_DIR%"

:: ── Detect architecture ──────────────────────────────────────────────────────

set ARCH=amd64
for /f "tokens=*" %%a in ('wmic os get osarchitecture /value 2^>nul') do (
  echo %%a | findstr /i "ARM" >nul && set ARCH=arm64
)

:: ── Install Loki ─────────────────────────────────────────────────────────────

if not exist "%LOKI_BIN%" (
  echo [monitoring] Installing Loki v%LOKI_VERSION% (windows/%ARCH%)...
  set LOKI_URL=https://github.com/grafana/loki/releases/download/v%LOKI_VERSION%/loki-windows-%ARCH%.exe.zip
  set TMP_ZIP=%LOKI_DIR%\loki.zip

  powershell -Command "Invoke-WebRequest -Uri '!LOKI_URL!' -OutFile '!TMP_ZIP!' -UseBasicParsing"
  powershell -Command "Expand-Archive -Path '!TMP_ZIP!' -DestinationPath '!LOKI_DIR!' -Force"
  del /f /q "!TMP_ZIP!"

  :: Rename loki-windows-<arch>.exe → loki.exe
  for %%f in ("%LOKI_DIR%\loki-windows-*.exe") do (
    if not "%%f"=="%LOKI_BIN%" ren "%%f" "loki.exe"
  )
  echo [monitoring] Loki installed: %LOKI_BIN%
) else (
  echo [monitoring] Loki already installed — skipping download
)

:: ── Install Grafana ───────────────────────────────────────────────────────────

if not exist "%GRAFANA_BIN%" (
  echo [monitoring] Installing Grafana v%GRAFANA_VERSION% (windows/%ARCH%)...
  set GRAFANA_URL=https://dl.grafana.com/oss/release/grafana-%GRAFANA_VERSION%.windows-%ARCH%.zip
  set TMP_ZIP=%TEMP%\olla-nest-grafana.zip

  powershell -Command "Invoke-WebRequest -Uri '!GRAFANA_URL!' -OutFile '!TMP_ZIP!' -UseBasicParsing"
  powershell -Command "Expand-Archive -Path '!TMP_ZIP!' -DestinationPath '%MONITORING_DIR%' -Force"
  del /f /q "!TMP_ZIP!"

  :: Grafana zip extracts to "grafana-<version>.windows-<arch>" — rename to "grafana"
  for /d %%d in ("%MONITORING_DIR%\grafana-*") do (
    ren "%%d" "grafana"
  )
  echo [monitoring] Grafana installed: %GRAFANA_DIR%
) else (
  echo [monitoring] Grafana already installed — skipping download
)

:: ── Sync provisioning files ───────────────────────────────────────────────────

set PROV_SRC=%MONITORING_DIR%\grafana-provisioning
set PROV_DST=%GRAFANA_DIR%\provisioning

if exist "%PROV_DST%\datasources" rd /s /q "%PROV_DST%\datasources"
if exist "%PROV_DST%\dashboards"  rd /s /q "%PROV_DST%\dashboards"
xcopy /e /i /q "%PROV_SRC%\datasources" "%PROV_DST%\datasources" >nul
xcopy /e /i /q "%PROV_SRC%\dashboards"  "%PROV_DST%\dashboards"  >nul
echo [monitoring] Grafana provisioning synced

:: ── Start Loki ────────────────────────────────────────────────────────────────

netstat -ano | findstr ":%LOKI_PORT% " | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 (
  echo [monitoring] Loki already running on port %LOKI_PORT% — skipping
) else (
  echo [monitoring] Starting Loki on port %LOKI_PORT%...
  set LOKI_DATA_DIR=%LOKI_DATA%
  start /b "" "%LOKI_BIN%" -config.file="%MONITORING_DIR%\loki-config.yml" -config.expand-env=true >> "%MONITORING_DIR%\loki.log" 2>&1
  :: Store PID via PowerShell (tasklist by image name)
  timeout /t 3 /nobreak >nul
  for /f "tokens=2" %%p in ('tasklist /fi "imagename eq loki.exe" /fo csv /nh 2^>nul') do (
    set LOKI_PID=%%~p
  )
  echo !LOKI_PID! > "%PIDS_DIR%\loki.pid"
  echo [monitoring] Loki starting (PID !LOKI_PID!) — warming up for ~15s...
  timeout /t 18 /nobreak >nul
  echo [monitoring] Loki should be ready at http://localhost:%LOKI_PORT%
)

:: ── Start Grafana ─────────────────────────────────────────────────────────────

netstat -ano | findstr ":%GRAFANA_PORT% " | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 (
  echo [monitoring] Grafana already running on port %GRAFANA_PORT% — skipping
) else (
  echo [monitoring] Starting Grafana on port %GRAFANA_PORT%...
  start /b "" "%GRAFANA_BIN%" server ^
    --homepath="%GRAFANA_DIR%" ^
    cfg:server.http_port=%GRAFANA_PORT% ^
    cfg:server.domain=localhost ^
    cfg:security.admin_user=admin ^
    "cfg:security.admin_password=CHANGE_ME_ON_FIRST_BOOT" ^
    cfg:analytics.reporting_enabled=false ^
    "cfg:paths.provisioning=%PROV_DST%" ^
    >> "%MONITORING_DIR%\grafana.log" 2>&1
  timeout /t 6 /nobreak >nul
  for /f "tokens=2" %%p in ('tasklist /fi "imagename eq grafana.exe" /fo csv /nh 2^>nul') do (
    set GRAFANA_PID=%%~p
  )
  echo !GRAFANA_PID! > "%PIDS_DIR%\grafana.pid"
  echo [monitoring] Grafana ready (PID !GRAFANA_PID!) — http://localhost:%GRAFANA_PORT%
)

:: ── Summary ───────────────────────────────────────────────────────────────────

echo.
echo   +------------------------------------------------------------------+
echo   ^|           Olla Nest Monitoring — Ready                           ^|
echo   +------------------------------------------------------------------+
echo   ^|  Loki API    -^>  http://localhost:%LOKI_PORT%                       ^|
echo   ^|  Grafana UI  -^>  http://localhost:%GRAFANA_PORT%                       ^|
echo   ^|  Login       -^>  admin  /  CHANGE_ME_ON_FIRST_BOOT                         ^|
echo   ^|  Dashboard   -^>  Olla Nest - Logs  (auto-provisioned)            ^|
echo   +------------------------------------------------------------------+
echo   ^|  Stop:   scripts\start_monitoring.bat --stop                     ^|
echo   +------------------------------------------------------------------+
echo.
goto :EOF

:: ── --stop ───────────────────────────────────────────────────────────────────
:STOP_ALL
echo [monitoring] Stopping Loki and Grafana...
taskkill /f /im loki.exe     >nul 2>&1 && echo [monitoring] Loki stopped    || echo [monitoring] Loki not running
taskkill /f /im grafana.exe  >nul 2>&1 && echo [monitoring] Grafana stopped || echo [monitoring] Grafana not running
if exist "%PIDS_DIR%\loki.pid"    del /f /q "%PIDS_DIR%\loki.pid"
if exist "%PIDS_DIR%\grafana.pid" del /f /q "%PIDS_DIR%\grafana.pid"
goto :EOF

:: ── --status ─────────────────────────────────────────────────────────────────
:SHOW_STATUS
echo.
echo   Olla Nest — Monitoring Status
tasklist /fi "imagename eq loki.exe"    /fo csv /nh 2>nul | findstr "loki"    >nul && echo   * Loki    RUNNING  http://localhost:%LOKI_PORT%    || echo   o Loki    stopped
tasklist /fi "imagename eq grafana.exe" /fo csv /nh 2>nul | findstr "grafana" >nul && echo   * Grafana RUNNING  http://localhost:%GRAFANA_PORT%    || echo   o Grafana stopped
echo.
goto :EOF
