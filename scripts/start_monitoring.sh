#!/bin/bash
# =============================================================================
# Olla Nest — Grafana + Loki Monitoring Stack
# =============================================================================
# Supports: macOS (Intel + Apple Silicon)
#           Linux — Ubuntu/Debian, RHEL/CentOS/Rocky, Alpine, Arch, Amazon Linux
#
# Installs (once) and starts:
#   • Loki    3.3.2   — log aggregation   → http://localhost:${LOKI_PORT:-3100}
#   • Grafana 11.4.0  — standalone log UI → http://localhost:${GRAFANA_PORT:-8082}
#
# Ports are configurable via the project .env (LOKI_PORT / GRAFANA_PORT) or the
# environment. Grafana is the standalone logs UI; it is independent of the admin
# and user apps and runs on its own port (default 8082).
#
# Usage:
#   bash scripts/start_monitoring.sh            # install (once) + start
#   bash scripts/start_monitoring.sh --stop     # stop both services
#   bash scripts/start_monitoring.sh --status   # show running status
#   bash scripts/start_monitoring.sh --restart  # stop then start
#
# All data stored inside the project — no sudo required:
#   scripts/monitoring/loki-bin/      ← Loki binary
#   scripts/monitoring/grafana/       ← Grafana installation
#   scripts/monitoring/loki-data/     ← Loki log storage (30-day retention)
#   scripts/monitoring/pids/          ← PID files
#   scripts/monitoring/loki.log       ← Loki stdout
#   scripts/monitoring/grafana.log    ← Grafana stdout
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONITORING_DIR="$SCRIPT_DIR/monitoring"
LOKI_DIR="$MONITORING_DIR/loki-bin"
LOKI_DATA="$MONITORING_DIR/loki-data"
GRAFANA_DIR="$MONITORING_DIR/grafana"
PIDS_DIR="$MONITORING_DIR/pids"

LOKI_VERSION="3.3.2"
GRAFANA_VERSION="11.4.0"

# ── Ports (configurable via the project .env or the environment) ──────────────
# The standalone log UI (Grafana) defaults to 8082; Loki's ingest/query API to
# 3100. Both are read from the project .env if present, then the environment,
# so they live in the same config file as the rest of Olla Nest.
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
if [[ -f "$PROJECT_ROOT/.env" ]]; then
  # Export only the monitoring keys we care about — avoid clobbering the shell.
  set -a
  # shellcheck disable=SC1091
  . "$PROJECT_ROOT/.env"
  set +a
fi
LOKI_PORT="${LOKI_PORT:-3100}"
GRAFANA_PORT="${GRAFANA_PORT:-8082}"
# Exported so Loki's -config.expand-env and Grafana's provisioning interpolation
# can pick up the chosen Loki port.
export LOKI_PORT GRAFANA_PORT

# ── Grafana admin password ────────────────────────────────────────────────────
# Generated once on first run, stored locally — never committed to git.
GRAFANA_PASS_FILE="$MONITORING_DIR/.grafana-password"

load_or_create_grafana_password() {
  if [[ -f "$GRAFANA_PASS_FILE" ]]; then
    GRAFANA_PASS=$(cat "$GRAFANA_PASS_FILE")
  else
    # Generate a 24-char random password (alphanumeric + symbols, no shell-special chars)
    GRAFANA_PASS=$(LC_ALL=C tr -dc 'A-Za-z0-9@#%^&*-_+=' </dev/urandom 2>/dev/null | head -c 24 || \
                   python3 -c "import secrets,string; print(secrets.token_urlsafe(18))")
    mkdir -p "$MONITORING_DIR"
    echo "$GRAFANA_PASS" > "$GRAFANA_PASS_FILE"
    chmod 600 "$GRAFANA_PASS_FILE"
    log "Grafana admin password generated and saved to $GRAFANA_PASS_FILE"
  fi
}

# ── Helpers ──────────────────────────────────────────────────────────────────

log()  { echo "[monitoring] $*"; }
warn() { echo "[monitoring] WARNING: $*" >&2; }
err()  { echo "[monitoring] ERROR: $*" >&2; exit 1; }

port_in_use() {
  if command -v lsof &>/dev/null; then
    lsof -ti :"$1" &>/dev/null
  else
    # Alpine/minimal Linux may not have lsof — use /proc or ss
    ss -tlnp 2>/dev/null | grep -q ":$1 " || \
    cat /proc/net/tcp6 /proc/net/tcp 2>/dev/null | \
      awk '{print $2}' | grep -qi "$(printf '%04X' "$1")$"
  fi
}

download() {
  local url="$1" dest="$2"
  if command -v curl &>/dev/null; then
    curl -fsSL --progress-bar "$url" -o "$dest"
  elif command -v wget &>/dev/null; then
    wget -q --show-progress "$url" -O "$dest"
  else
    err "Neither curl nor wget found. Install one and retry."
  fi
}

loki_ready() {
  curl -s "http://localhost:$LOKI_PORT/ready" 2>/dev/null | grep -q "^ready"
}

grafana_ready() {
  curl -s -o /dev/null -w "%{http_code}" "http://localhost:$GRAFANA_PORT/api/health" 2>/dev/null | grep -q "200"
}

# ── OS / Architecture detection ───────────────────────────────────────────────

OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
  Darwin)
    LOKI_OS="darwin"; GRAFANA_OS="darwin"
    GRAFANA_PKG="darwin"
    [[ "$ARCH" == "arm64" ]] && { LOKI_ARCH="arm64"; GRAFANA_ARCH="arm64"; } \
                              || { LOKI_ARCH="amd64"; GRAFANA_ARCH="amd64"; }
    ;;
  Linux)
    LOKI_OS="linux"; GRAFANA_OS="linux"
    GRAFANA_PKG="linux"
    case "$ARCH" in
      aarch64|arm64) LOKI_ARCH="arm64"; GRAFANA_ARCH="arm64" ;;
      armv7l|armhf)  LOKI_ARCH="arm";   GRAFANA_ARCH="armv7"  ;;
      *)             LOKI_ARCH="amd64"; GRAFANA_ARCH="amd64"  ;;
    esac
    ;;
  *)
    err "Unsupported OS: $OS. Use start_monitoring.bat (Windows CMD) or start_monitoring.ps1 (PowerShell)."
    ;;
esac

# ── --stop ────────────────────────────────────────────────────────────────────

stop_all() {
  log "Stopping Loki and Grafana..."
  local stopped=0
  for svc in loki grafana; do
    local pidfile="$PIDS_DIR/$svc.pid"
    if [[ -f "$pidfile" ]]; then
      local pid; pid=$(cat "$pidfile")
      if kill -0 "$pid" 2>/dev/null; then
        kill "$pid"
        log "  Stopped $svc (PID $pid)"
        stopped=$((stopped+1))
      fi
      rm -f "$pidfile"
    fi
  done
  # Also kill any stray processes by port
  for port in $LOKI_PORT $GRAFANA_PORT; do
    if command -v lsof &>/dev/null; then
      lsof -ti :"$port" | xargs kill -9 2>/dev/null || true
    fi
  done
  [[ $stopped -eq 0 ]] && log "No running services found." || log "Done."
}

# ── --status ──────────────────────────────────────────────────────────────────

show_status() {
  echo ""
  echo "  ┌──────────────────────────────────────────────────────┐"
  echo "  │         Olla Nest — Monitoring Status                 │"
  echo "  └──────────────────────────────────────────────────────┘"
  if loki_ready; then
    echo "  ● Loki     RUNNING  →  http://localhost:$LOKI_PORT"
  elif port_in_use $LOKI_PORT; then
    echo "  ◑ Loki     STARTING →  http://localhost:$LOKI_PORT (warming up)"
  else
    echo "  ○ Loki     stopped"
  fi
  if grafana_ready; then
    echo "  ● Grafana  RUNNING  →  http://localhost:$GRAFANA_PORT  (admin / see .grafana-password)"
  elif port_in_use $GRAFANA_PORT; then
    echo "  ◑ Grafana  STARTING →  http://localhost:$GRAFANA_PORT (warming up)"
  else
    echo "  ○ Grafana  stopped"
  fi
  echo ""
}

# ── Argument handling ─────────────────────────────────────────────────────────

ARG="${1:-}"
[[ "$ARG" == "--stop"    ]] && { stop_all;    exit 0; }
[[ "$ARG" == "--status"  ]] && { load_or_create_grafana_password; show_status; exit 0; }
[[ "$ARG" == "--restart" ]] && { stop_all; sleep 1; }

load_or_create_grafana_password

# ── Directory setup ───────────────────────────────────────────────────────────

mkdir -p "$LOKI_DIR" \
         "$LOKI_DATA/chunks" "$LOKI_DATA/rules" "$LOKI_DATA/compactor" \
         "$PIDS_DIR"

# ── Install Loki ─────────────────────────────────────────────────────────────

LOKI_BIN="$LOKI_DIR/loki"

if [[ ! -f "$LOKI_BIN" ]]; then
  log "Installing Loki v$LOKI_VERSION ($LOKI_OS/$LOKI_ARCH)..."
  TMP_ZIP="$LOKI_DIR/loki.zip"
  LOKI_URL="https://github.com/grafana/loki/releases/download/v${LOKI_VERSION}/loki-${LOKI_OS}-${LOKI_ARCH}.zip"
  download "$LOKI_URL" "$TMP_ZIP"
  unzip -q "$TMP_ZIP" -d "$LOKI_DIR"
  rm -f "$TMP_ZIP"
  # Binary inside zip is named "loki-<os>-<arch>" — normalise to "loki"
  LOKI_EXTRACTED="$(find "$LOKI_DIR" -maxdepth 1 -name "loki-*" ! -name "*.zip" ! -name "loki" | head -1)"
  if [[ -n "$LOKI_EXTRACTED" ]]; then
    mv "$LOKI_EXTRACTED" "$LOKI_BIN"
  fi
  chmod +x "$LOKI_BIN"
  log "Loki installed → $LOKI_BIN"
else
  log "Loki already installed (skip download)"
fi

# ── Install Grafana ───────────────────────────────────────────────────────────

GRAFANA_SERVER_BIN="$GRAFANA_DIR/bin/grafana"
[[ ! -f "$GRAFANA_SERVER_BIN" ]] && GRAFANA_SERVER_BIN="$GRAFANA_DIR/bin/grafana-server"

if [[ ! -f "$GRAFANA_DIR/bin/grafana" && ! -f "$GRAFANA_DIR/bin/grafana-server" ]]; then
  log "Installing Grafana v$GRAFANA_VERSION ($GRAFANA_PKG/$GRAFANA_ARCH)..."
  TMP_TGZ="/tmp/olla-nest-grafana.tar.gz"
  GRAFANA_URL="https://dl.grafana.com/oss/release/grafana-${GRAFANA_VERSION}.${GRAFANA_PKG}-${GRAFANA_ARCH}.tar.gz"
  download "$GRAFANA_URL" "$TMP_TGZ"
  mkdir -p "$GRAFANA_DIR"
  tar -xzf "$TMP_TGZ" -C "$GRAFANA_DIR" --strip-components=1
  rm -f "$TMP_TGZ"
  log "Grafana installed → $GRAFANA_DIR"
else
  log "Grafana already installed (skip download)"
fi

# Detect correct Grafana binary (v10+ uses "grafana server", older uses "grafana-server")
if [[ -f "$GRAFANA_DIR/bin/grafana" ]]; then
  GRAFANA_SERVER_BIN="$GRAFANA_DIR/bin/grafana"
  GRAFANA_CMD=("$GRAFANA_SERVER_BIN" server)
else
  GRAFANA_SERVER_BIN="$GRAFANA_DIR/bin/grafana-server"
  GRAFANA_CMD=("$GRAFANA_SERVER_BIN")
fi

# ── Sync provisioning files ───────────────────────────────────────────────────

PROV_SRC="$MONITORING_DIR/grafana-provisioning"
PROV_DST="$GRAFANA_DIR/provisioning"

rm -rf "$PROV_DST/datasources" "$PROV_DST/dashboards"
cp -r "$PROV_SRC/datasources" "$PROV_DST/"
cp -r "$PROV_SRC/dashboards"  "$PROV_DST/"
log "Grafana provisioning synced → $PROV_DST"

# ── Start Loki ────────────────────────────────────────────────────────────────

if port_in_use $LOKI_PORT; then
  log "Loki already running on port $LOKI_PORT — skipping."
else
  log "Starting Loki on port $LOKI_PORT..."
  export LOKI_DATA_DIR="$LOKI_DATA"
  "$LOKI_BIN" \
    -config.file="$MONITORING_DIR/loki-config.yml" \
    -config.expand-env=true \
    >> "$MONITORING_DIR/loki.log" 2>&1 &
  echo $! > "$PIDS_DIR/loki.pid"

  log "Waiting for Loki ingester to become ready (up to 35s)..."
  for i in $(seq 1 18); do
    sleep 2
    if loki_ready; then
      log "Loki ready (PID $(cat "$PIDS_DIR/loki.pid"))  →  http://localhost:$LOKI_PORT"
      break
    fi
    if [[ $i -eq 18 ]]; then
      warn "Loki did not report ready — check $MONITORING_DIR/loki.log"
      tail -5 "$MONITORING_DIR/loki.log" >&2
    fi
  done
fi

# ── Start Grafana ─────────────────────────────────────────────────────────────

if port_in_use $GRAFANA_PORT; then
  log "Grafana already running on port $GRAFANA_PORT — skipping."
else
  log "Starting Grafana on port $GRAFANA_PORT..."
  # Enforce the generated password on the admin user. Grafana only applies
  # cfg:security.admin_password when it first creates the DB; if the DB already
  # exists (e.g. from a previous run) it keeps the old password and the freshly
  # generated .grafana-password file would be wrong. Resetting here against the
  # DB (before the server launches) keeps the file authoritative and idempotent.
  if [[ -f "$GRAFANA_DIR/bin/grafana" ]]; then
    "$GRAFANA_DIR/bin/grafana" cli --homepath "$GRAFANA_DIR" \
      admin reset-admin-password "$GRAFANA_PASS" \
      >> "$MONITORING_DIR/grafana.log" 2>&1 || \
      warn "Could not pre-set Grafana admin password (will use existing)."
  fi
  "${GRAFANA_CMD[@]}" \
    --homepath="$GRAFANA_DIR" \
    cfg:server.http_port=$GRAFANA_PORT \
    cfg:server.domain=localhost \
    cfg:security.admin_user=admin \
    "cfg:security.admin_password=$GRAFANA_PASS" \
    cfg:analytics.reporting_enabled=false \
    cfg:analytics.check_for_updates=false \
    "cfg:paths.provisioning=$PROV_DST" \
    >> "$MONITORING_DIR/grafana.log" 2>&1 &
  echo $! > "$PIDS_DIR/grafana.pid"

  log "Waiting for Grafana to become ready (up to 20s)..."
  for i in $(seq 1 10); do
    sleep 2
    if grafana_ready; then
      log "Grafana ready (PID $(cat "$PIDS_DIR/grafana.pid"))  →  http://localhost:$GRAFANA_PORT"
      break
    fi
    if [[ $i -eq 10 ]]; then
      warn "Grafana did not report ready — check $MONITORING_DIR/grafana.log"
      tail -5 "$MONITORING_DIR/grafana.log" >&2
    fi
  done
fi

# ── Summary ───────────────────────────────────────────────────────────────────

echo ""
echo "  ┌──────────────────────────────────────────────────────────────────┐"
echo "  │              Olla Nest Monitoring — Ready                         │"
echo "  ├──────────────────────────────────────────────────────────────────┤"
echo "  │  Loki API     →  http://localhost:$LOKI_PORT                          │"
echo "  │  Grafana UI   →  http://localhost:$GRAFANA_PORT                          │"
echo "  │  Login        →  admin  /  $(cat "$GRAFANA_PASS_FILE")                    │"
echo "  │  (password saved in scripts/monitoring/.grafana-password)         │"
echo "  │  Dashboard    →  Olla Nest — Logs  (auto-provisioned)             │"
echo "  ├──────────────────────────────────────────────────────────────────┤"
echo "  │  Stop:        bash scripts/start_monitoring.sh --stop             │"
echo "  │  Restart:     bash scripts/start_monitoring.sh --restart          │"
echo "  │  Status:      bash scripts/start_monitoring.sh --status           │"
echo "  └──────────────────────────────────────────────────────────────────┘"
echo ""
