#!/bin/bash
# Olla Nest — Local Whisper STT Server Setup & Launcher
# Supports: macOS, Ubuntu/Debian, RHEL/CentOS/Rocky, Alpine Linux
#
# Usage:
#   bash scripts/start_whisper.sh
#
# Environment variables:
#   WHISPER_MODEL=small   (default: base)
#   WHISPER_PORT=8765     (default: 8765)

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$SCRIPT_DIR/venv"
OS="$(uname -s 2>/dev/null || echo Unknown)"

# faster-whisper's 'av' dependency requires Python ≤ 3.12.
# Python 3.13+ has no binary wheels for 'av' yet.
REQUIRED_PYTHON_MAJOR=3
REQUIRED_PYTHON_MINOR=11   # 3.11 — stable LTS, all wheels available

echo "[whisper] Platform: $OS"

# ── Install system dependencies ──────────────────────────────────────────────

if [[ "$OS" == "Darwin" ]]; then
  if ! command -v brew &>/dev/null; then
    echo "[whisper] ERROR: Homebrew not found. Install from https://brew.sh"
    exit 1
  fi
  command -v pkg-config &>/dev/null || brew install pkg-config
  command -v ffmpeg     &>/dev/null || brew install ffmpeg

elif command -v apt-get &>/dev/null; then
  echo "[whisper] Installing system deps via apt..."
  sudo apt-get update -qq
  sudo apt-get install -y -qq python3.11 python3.11-venv python3-pip ffmpeg pkg-config || \
    sudo apt-get install -y -qq python3 python3-venv python3-pip ffmpeg pkg-config

elif command -v dnf &>/dev/null; then
  echo "[whisper] Installing system deps via dnf..."
  sudo dnf install -y python3.11 python3-pip ffmpeg pkgconf-pkg-config 2>/dev/null || \
    sudo dnf install -y python3 python3-pip ffmpeg pkgconf-pkg-config

elif command -v yum &>/dev/null; then
  echo "[whisper] Installing system deps via yum..."
  sudo yum install -y python3 python3-pip ffmpeg pkgconfig

elif command -v apk &>/dev/null; then
  echo "[whisper] Installing system deps via apk..."
  apk add --no-cache python3 py3-pip ffmpeg pkgconf

else
  echo "[whisper] WARNING: Unknown package manager — skipping system dep install."
fi

# ── Find a suitable Python (3.11 or 3.12 preferred, NOT 3.13/3.14) ──────────

find_python() {
  # Prefer exact version matches first, then fall through
  for candidate in \
      python3.11 \
      /usr/local/bin/python3.11 \
      /opt/homebrew/bin/python3.11 \
      python3.12 \
      /usr/local/bin/python3.12 \
      /opt/homebrew/bin/python3.12 \
      python3.10 \
      python3; do
    if command -v "$candidate" &>/dev/null; then
      ver=$("$candidate" -c "import sys; print(sys.version_info[:2])" 2>/dev/null || echo "")
      # Accept 3.9 – 3.12, reject 3.13+
      if echo "$ver" | grep -qE "\(3, (9|10|11|12)\)"; then
        echo "$candidate"
        return 0
      fi
    fi
  done
  return 1
}

PYTHON_BIN=$(find_python || true)

if [[ -z "$PYTHON_BIN" ]]; then
  echo "[whisper] Python 3.9–3.12 not found. Installing Python 3.11 ..."
  if [[ "$OS" == "Darwin" ]]; then
    brew install python@3.11
    PYTHON_BIN="$(brew --prefix)/bin/python3.11"
    # Refresh PATH so the new binary is visible
    export PATH="$(brew --prefix)/bin:$PATH"
  elif command -v apt-get &>/dev/null; then
    sudo apt-get install -y -qq python3.11 python3.11-venv
    PYTHON_BIN="python3.11"
  elif command -v dnf &>/dev/null; then
    sudo dnf install -y python3.11
    PYTHON_BIN="python3.11"
  else
    echo "[whisper] ERROR: Cannot auto-install Python 3.11 on this platform."
    echo "[whisper]   Please install Python 3.11 or 3.12 manually and re-run."
    exit 1
  fi
fi

echo "[whisper] Using Python: $PYTHON_BIN ($($PYTHON_BIN --version))"

# ── Delete old venv if it was built with wrong Python version ────────────────
if [[ -d "$VENV" ]]; then
  VENV_PYTHON="$VENV/bin/python"
  if [[ -f "$VENV_PYTHON" ]]; then
    VENV_VER=$("$VENV_PYTHON" -c "import sys; print(sys.version_info[:2])" 2>/dev/null || echo "")
    if echo "$VENV_VER" | grep -qE "\(3, (13|14|15)\)"; then
      echo "[whisper] Existing venv is Python 3.13+ — removing and rebuilding with Python $($PYTHON_BIN --version)..."
      rm -rf "$VENV"
    fi
  fi
fi

# ── Create venv ───────────────────────────────────────────────────────────────
if [[ ! -d "$VENV" ]]; then
  echo "[whisper] Creating virtual environment with $($PYTHON_BIN --version) ..."
  "$PYTHON_BIN" -m venv "$VENV"
fi

source "$VENV/bin/activate"

# ── Upgrade pip quietly ───────────────────────────────────────────────────────
pip install --upgrade pip --quiet

# ── Install Python packages ───────────────────────────────────────────────────
if ! python -c "import faster_whisper" 2>/dev/null; then
  echo "[whisper] Installing faster-whisper (this may take a minute)..."
  pip install faster-whisper
fi

# ── Launch server ─────────────────────────────────────────────────────────────
PORT="${WHISPER_PORT:-8765}"
echo "[whisper] Starting Whisper STT server on http://0.0.0.0:${PORT}"
WHISPER_PORT="$PORT" python "$SCRIPT_DIR/whisper_server.py"
