#!/bin/bash
# Olla Nest — Start local Whisper transcription server
# Run from anywhere: bash scripts/start_whisper.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$SCRIPT_DIR/venv"

# ── Install system deps (brew) if missing ────────────────────────────────────
if ! command -v pkg-config &>/dev/null; then
  echo "[whisper] Installing pkg-config via brew..."
  brew install pkg-config
fi

if ! command -v ffmpeg &>/dev/null; then
  echo "[whisper] Installing ffmpeg via brew..."
  brew install ffmpeg
fi

# ── Create venv if missing ───────────────────────────────────────────────────
if [ ! -d "$VENV" ]; then
  echo "[whisper] Creating virtual environment..."
  python3 -m venv "$VENV"
fi

source "$VENV/bin/activate"

# ── Install Python deps if missing ──────────────────────────────────────────
if ! python -c "import faster_whisper" 2>/dev/null; then
  echo "[whisper] Installing faster-whisper..."
  pip install faster-whisper --quiet
fi

if ! python -c "import multipart" 2>/dev/null; then
  echo "[whisper] Installing python-multipart..."
  pip install python-multipart --quiet
fi

PORT="${WHISPER_PORT:-8765}"
echo "[whisper] Starting server on http://localhost:${PORT}"
WHISPER_PORT="$PORT" python "$SCRIPT_DIR/whisper_server.py"
