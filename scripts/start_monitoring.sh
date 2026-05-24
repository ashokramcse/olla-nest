#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Olla Nest — Grafana + Loki Monitoring Stack
# ─────────────────────────────────────────────────────────────────────────────
# Installs and starts:
#   • Loki  3.3.2  — log aggregation server (http://localhost:3100)
#   • Grafana 11.4.0 — dashboards UI (http://localhost:3200)
#
# Usage:
#   bash scripts/start_monitoring.sh          # start both
#   bash scripts/start_monitoring.sh --stop   # stop both
#   bash scripts/start_monitoring.sh --status # show status
#
# Data persisted in:  scripts/monitoring/loki-data/
# Grafana data in:    /opt/olla-nest-grafana/   (configurable via GRAFANA_DIR)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONITORING_DIR="$SCRIPT_DIR/monitoring"
LOKI_DIR="$MONITORING_DIR/loki-bin"
LOKI_DATA="$MONITORING_DIR/loki-data"
GRAFANA_DIR="${GRAFANA_DIR:-/opt/olla-nest-grafana}"
PIDS_DIR="$MONITORING_DIR/pids"

LOKI_VERSION="3.3.2"
GRAFANA_VERSION="11.4.0"
LOKI_PORT=3100
GRAFANA_PORT=3200

OS="$(uname -s)"
ARCH="$(uname -m)"

# ── Helpers ──────────────────────────────────────────────────────────────────

log()  { echo "[monitoring] $*"; }
warn() { echo "[monitoring] WARNING: $*" >&2; }
err()  { echo "[monitoring] ERROR: $*" >&2; exit 1; }

port_in_use() { lsof -ti :"$1" &>/dev/null; }

# ── Stop ─────────────────────────────────────────────────────────────────────

stop_all() {
  log "Stopping Loki and Grafana..."
  for pidfile in "$PIDS_DIR"/loki.pid "$PIDS_DIR"/grafana.pid; do
    if [[ -f "$pidfile" ]]; then
      pid=$(cat "$pidfile")
      if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" && log "Stopped PID $pid ($(basename "$pidfile" .pid))"
      fi
      rm -f "$pidfile"
    fi
  done
  log "Done."
  exit 0
}

# ── Status ───────────────────────────────────────────────────────────────────

show_status() {
  echo ""
  echo "  ┌─────────────────────────────────────────────────┐"
  echo "  │         Olla Nest — Monitoring Status            │"
  echo "  └─────────────────────────────────────────────────┘"

  if port_in_use $LOKI_PORT; then
    echo "  ● Loki     — RUNNING  http://localhost:$LOKI_PORT"
  else
    echo "  ○ Loki     — stopped"
  fi

  if port_in_use $GRAFANA_PORT; then
    echo "  ● Grafana  — RUNNING  http://localhost:$GRAFANA_PORT"
    echo "              Login: admin / CHANGE_ME_ON_FIRST_BOOT"
  else
    echo "  ○ Grafana  — stopped"
  fi
  echo ""
  exit 0
}

# ── Argument handling ─────────────────────────────────────────────────────────

[[ "${1:-}" == "--stop"   ]] && stop_all
[[ "${1:-}" == "--status" ]] && show_status

# ── OS / Arch detection ───────────────────────────────────────────────────────

case "$OS" in
  Darwin)
    LOKI_OS="darwin"
    GRAFANA_OS="darwin"
    case "$ARCH" in
      arm64)  LOKI_ARCH="arm64"; GRAFANA_ARCH="arm64" ;;
      *)      LOKI_ARCH="amd64"; GRAFANA_ARCH="amd64" ;;
    esac
    ;;
  Linux)
    LOKI_OS="linux"
    GRAFANA_OS="linux"
    case "$ARCH" in
      aarch64|arm64) LOKI_ARCH="arm64"; GRAFANA_ARCH="arm64" ;;
      *)             LOKI_ARCH="amd64"; GRAFANA_ARCH="amd64" ;;
    esac
    ;;
  *)
    err "Unsupported OS: $OS. Run Loki and Grafana manually — see docs/DEPLOYMENT.md"
    ;;
esac

# ── Directory setup ───────────────────────────────────────────────────────────

mkdir -p "$LOKI_DIR" "$LOKI_DATA/chunks" "$LOKI_DATA/rules" "$LOKI_DATA/compactor" "$PIDS_DIR"

# ── Install Loki ─────────────────────────────────────────────────────────────

LOKI_BIN="$LOKI_DIR/loki"

if [[ ! -f "$LOKI_BIN" ]]; then
  log "Downloading Loki v$LOKI_VERSION ($LOKI_OS/$LOKI_ARCH)..."
  LOKI_URL="https://github.com/grafana/loki/releases/download/v${LOKI_VERSION}/loki-${LOKI_OS}-${LOKI_ARCH}.zip"
  TMP_ZIP="$LOKI_DIR/loki.zip"

  if command -v curl &>/dev/null; then
    curl -fsSL "$LOKI_URL" -o "$TMP_ZIP"
  elif command -v wget &>/dev/null; then
    wget -q "$LOKI_URL" -O "$TMP_ZIP"
  else
    err "curl or wget required to download Loki."
  fi

  unzip -q "$TMP_ZIP" -d "$LOKI_DIR"
  # Loki zip contains "loki-<os>-<arch>" — rename to "loki"
  find "$LOKI_DIR" -maxdepth 1 -name "loki-*" -not -name "*.zip" | head -1 | xargs -I{} mv {} "$LOKI_BIN"
  chmod +x "$LOKI_BIN"
  rm -f "$TMP_ZIP"
  log "Loki downloaded → $LOKI_BIN"
fi

# ── Install Grafana ───────────────────────────────────────────────────────────

GRAFANA_BIN="$GRAFANA_DIR/bin/grafana"
GRAFANA_SERVER_BIN="$GRAFANA_DIR/bin/grafana-server"

if [[ ! -f "$GRAFANA_SERVER_BIN" ]]; then
  log "Downloading Grafana v$GRAFANA_VERSION ($GRAFANA_OS/$GRAFANA_ARCH)..."

  # macOS: .tar.gz; Linux: .tar.gz
  GRAFANA_URL="https://dl.grafana.com/oss/release/grafana-${GRAFANA_VERSION}.${GRAFANA_OS}-${GRAFANA_ARCH}.tar.gz"
  TMP_TGZ="/tmp/grafana.tar.gz"

  if command -v curl &>/dev/null; then
    curl -fsSL "$GRAFANA_URL" -o "$TMP_TGZ"
  else
    wget -q "$GRAFANA_URL" -O "$TMP_TGZ"
  fi

  mkdir -p "$GRAFANA_DIR"
  tar -xzf "$TMP_TGZ" -C "$GRAFANA_DIR" --strip-components=1
  rm -f "$TMP_TGZ"
  log "Grafana downloaded → $GRAFANA_DIR"
fi

# ── Copy provisioning files into Grafana ──────────────────────────────────────

PROV_SRC="$MONITORING_DIR/grafana-provisioning"
PROV_DST="$GRAFANA_DIR/provisioning"

cp -r "$PROV_SRC/datasources" "$PROV_DST/"
cp -r "$PROV_SRC/dashboards"  "$PROV_DST/"

log "Grafana provisioning files installed."

# ── Start Loki ────────────────────────────────────────────────────────────────

if port_in_use $LOKI_PORT; then
  log "Loki already running on port $LOKI_PORT — skipping."
else
  log "Starting Loki on port $LOKI_PORT..."
  "$LOKI_BIN" \
    -config.file="$MONITORING_DIR/loki-config.yml" \
    -config.expand-env=true \
    >> "$MONITORING_DIR/loki.log" 2>&1 &
  LOKI_PID=$!
  echo $LOKI_PID > "$PIDS_DIR/loki.pid"
  sleep 2
  if port_in_use $LOKI_PORT; then
    log "Loki started (PID $LOKI_PID)  →  http://localhost:$LOKI_PORT"
  else
    warn "Loki may not have started — check $MONITORING_DIR/loki.log"
  fi
fi

# ── Start Grafana ─────────────────────────────────────────────────────────────

if port_in_use $GRAFANA_PORT; then
  log "Grafana already running on port $GRAFANA_PORT — skipping."
else
  log "Starting Grafana on port $GRAFANA_PORT..."
  "$GRAFANA_DIR/bin/grafana" server \
    --config="$GRAFANA_DIR/conf/defaults.ini" \
    --homepath="$GRAFANA_DIR" \
    cfg:server.http_port=$GRAFANA_PORT \
    cfg:server.domain=localhost \
    cfg:security.admin_user=admin \
    cfg:security.admin_password="CHANGE_ME_ON_FIRST_BOOT" \
    cfg:analytics.reporting_enabled=false \
    cfg:analytics.check_for_updates=false \
    >> "$MONITORING_DIR/grafana.log" 2>&1 &
  GRAFANA_PID=$!
  echo $GRAFANA_PID > "$PIDS_DIR/grafana.pid"
  sleep 3
  if port_in_use $GRAFANA_PORT; then
    log "Grafana started (PID $GRAFANA_PID)  →  http://localhost:$GRAFANA_PORT"
  else
    warn "Grafana may not have started — check $MONITORING_DIR/grafana.log"
  fi
fi

# ── Summary ───────────────────────────────────────────────────────────────────

echo ""
echo "  ┌─────────────────────────────────────────────────────────────────┐"
echo "  │              Olla Nest Monitoring — Ready                        │"
echo "  ├─────────────────────────────────────────────────────────────────┤"
echo "  │  Loki API     →  http://localhost:$LOKI_PORT                         │"
echo "  │  Grafana UI   →  http://localhost:$GRAFANA_PORT                         │"
echo "  │  Login        →  admin  /  CHANGE_ME_ON_FIRST_BOOT                        │"
echo "  │  Dashboard    →  Olla Nest — Logs  (auto-provisioned)           │"
echo "  ├─────────────────────────────────────────────────────────────────┤"
echo "  │  Stop:        bash scripts/start_monitoring.sh --stop           │"
echo "  │  Status:      bash scripts/start_monitoring.sh --status         │"
echo "  └─────────────────────────────────────────────────────────────────┘"
echo ""
