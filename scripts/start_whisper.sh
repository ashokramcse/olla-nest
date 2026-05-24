#!/bin/bash
# Olla Nest — Start local Whisper transcription server
# Run from anywhere: bash scripts/start_whisper.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$SCRIPT_DIR/venv"

# Create venv if it doesn't exist
if [ ! -d "$VENV" ]; then
  echo "[whisper] Creating virtual environment..."
  python3 -m venv "$VENV"
fi

# Activate venv
source "$VENV/bin/activate"

# Install faster-whisper if not already installed
if ! python -c "import faster_whisper" 2>/dev/null; then
  echo "[whisper] Installing faster-whisper..."
  pip install faster-whisper --quiet
fi

echo "[whisper] Starting server on http://localhost:${WHISPER_PORT:-8000}"
python "$SCRIPT_DIR/whisper_server.py"
