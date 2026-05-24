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

echo "[whisper] Platform: $OS"

# ── Install system dependencies ──────────────────────────────────────────────

install_system_deps() {
  if [[ "$OS" == "Darwin" ]]; then
    # macOS — use Homebrew
    if ! command -v brew &>/dev/null; then
      echo "[whisper] ERROR: Homebrew not found. Install from https://brew.sh"
      exit 1
    fi
    command -v pkg-config &>/dev/null || brew install pkg-config
    command -v ffmpeg     &>/dev/null || brew install ffmpeg

  elif command -v apt-get &>/dev/null; then
    # Ubuntu / Debian / Raspberry Pi OS
    echo "[whisper] Installing system deps via apt..."
    sudo apt-get update -qq
    sudo apt-get install -y -qq python3 python3-venv python3-pip ffmpeg pkg-config

  elif command -v dnf &>/dev/null; then
    # RHEL 8+ / CentOS Stream / Rocky / AlmaLinux / Fedora
    echo "[whisper] Installing system deps via dnf..."
    sudo dnf install -y python3 python3-venv python3-pip ffmpeg pkgconf-pkg-config

  elif command -v yum &>/dev/null; then
    # RHEL 7 / CentOS 7 (legacy)
    echo "[whisper] Installing system deps via yum..."
    sudo yum install -y python3 python3-pip ffmpeg pkgconfig

  elif command -v apk &>/dev/null; then
    # Alpine Linux (Docker containers)
    echo "[whisper] Installing system deps via apk..."
    apk add --no-cache python3 py3-pip ffmpeg pkgconf

  elif command -v pacman &>/dev/null; then
    # Arch Linux
    echo "[whisper] Installing system deps via pacman..."
    sudo pacman -Sy --noconfirm python python-pip ffmpeg

  else
    echo "[whisper] WARNING: Unknown package manager — skipping system dep install."
    echo "[whisper]   Please ensure python3, pip, and ffmpeg are installed manually."
  fi
}

install_system_deps

# ── Python check ─────────────────────────────────────────────────────────────
PYTHON_BIN="$(command -v python3 || command -v python || true)"
if [[ -z "$PYTHON_BIN" ]]; then
  echo "[whisper] ERROR: Python 3 not found. Install Python 3.9+ and re-run."
  exit 1
fi
echo "[whisper] Python: $("$PYTHON_BIN" --version)"

# ── Create venv ───────────────────────────────────────────────────────────────
if [[ ! -d "$VENV" ]]; then
  echo "[whisper] Creating virtual environment at $VENV ..."
  "$PYTHON_BIN" -m venv "$VENV"
fi

source "$VENV/bin/activate"

# ── Upgrade pip quietly ───────────────────────────────────────────────────────
pip install --upgrade pip --quiet

# ── Install Python packages ───────────────────────────────────────────────────
if ! python -c "import faster_whisper" 2>/dev/null; then
  echo "[whisper] Installing faster-whisper..."
  pip install faster-whisper --quiet
fi

if ! python -c "import multipart" 2>/dev/null; then
  echo "[whisper] Installing python-multipart..."
  pip install python-multipart --quiet
fi

# ── Launch server ─────────────────────────────────────────────────────────────
PORT="${WHISPER_PORT:-8765}"
echo "[whisper] Starting Whisper STT server on http://0.0.0.0:${PORT}"
WHISPER_PORT="$PORT" python "$SCRIPT_DIR/whisper_server.py"
