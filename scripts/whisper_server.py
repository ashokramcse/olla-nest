#!/usr/bin/env python3
"""
Olla Nest — Local Whisper Transcription Server
================================================
OpenAI-compatible speech-to-text server powered by faster-whisper.
Exposes: POST /v1/audio/transcriptions

Setup (first time only):
  brew install pkg-config ffmpeg
  python3 -m venv scripts/venv
  source scripts/venv/bin/activate
  pip install faster-whisper python-multipart

Start (every time):
  bash scripts/start_whisper.sh

  Or set port:  WHISPER_PORT=8765 python scripts/whisper_server.py
  Or set model: WHISPER_MODEL=small python scripts/whisper_server.py

Model sizes (speed vs accuracy):
  tiny   — ~39 MB,  fastest
  base   — ~74 MB,  recommended default
  small  — ~244 MB, better accuracy
  medium — ~769 MB, near-OpenAI quality
  large-v3 — ~1.5 GB, matches OpenAI quality
"""

import os
import sys
import tempfile
import http.server
import json

# ── Dependency check ─────────────────────────────────────────────────────────
def _check(module, pkg):
    try:
        __import__(module)
    except ImportError:
        print(f"ERROR: '{pkg}' is not installed.")
        print(f"Run: pip install {pkg}")
        sys.exit(1)

_check("faster_whisper", "faster-whisper")
_check("multipart",      "python-multipart")

from faster_whisper import WhisperModel
from multipart.multipart import parse_options_header
import multipart as mp

# ── Config ───────────────────────────────────────────────────────────────────
MODEL_SIZE = os.environ.get("WHISPER_MODEL", "base")
PORT       = int(os.environ.get("WHISPER_PORT", "8765"))
DEVICE     = "cpu"   # change to "cuda" if you have an NVIDIA GPU

print(f"[whisper] Loading model '{MODEL_SIZE}' on {DEVICE}…")
model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type="int8")
print(f"[whisper] Model ready. Listening on http://0.0.0.0:{PORT}")
print(f"[whisper] Endpoint: POST http://localhost:{PORT}/v1/audio/transcriptions")


# ── HTTP handler ─────────────────────────────────────────────────────────────
class WhisperHandler(http.server.BaseHTTPRequestHandler):

    def log_message(self, fmt, *args):
        print(f"[whisper] {self.address_string()} — {fmt % args}")

    def do_GET(self):
        if self.path == "/health":
            self._send_json({"status": "ok", "model": MODEL_SIZE})
        else:
            self._send_json({"error": "Not found"}, 404)

    def do_POST(self):
        if self.path != "/v1/audio/transcriptions":
            self._send_json({"error": "Not found"}, 404)
            return

        content_type = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in content_type:
            self._send_json({"error": "Expected multipart/form-data"}, 400)
            return

        length = int(self.headers.get("Content-Length", 0))
        body   = self.rfile.read(length)

        # Parse multipart using python-multipart (works on Python 3.13+)
        audio_bytes = None
        audio_name  = "audio.webm"

        content_type_bytes = content_type.encode("utf-8")
        _, params = parse_options_header(content_type_bytes)
        boundary = params.get(b"boundary")
        if boundary is None:
            self._send_json({"error": "Missing boundary in Content-Type"}, 400)
            return

        parts = {}

        def on_field(field):
            parts[field.field_name.decode()] = field.value.decode()

        def on_file(file):
            file.file_object.seek(0)
            parts["_file_data"] = file.file_object.read()
            parts["_file_name"] = file.file_name.decode() if file.file_name else "audio.webm"

        mp.create_form_parser(
            {"Content-Type": content_type},
            on_field,
            on_file,
        )

        # Fallback: manual boundary split if python-multipart API differs
        if "_file_data" not in parts:
            audio_bytes, audio_name = _manual_parse(body, boundary if isinstance(boundary, bytes) else boundary.encode())
        else:
            audio_bytes = parts["_file_data"]
            audio_name  = parts.get("_file_name", "audio.webm")

        if not audio_bytes:
            self._send_json({"error": "Could not parse audio from request"}, 400)
            return

        try:
            suffix = os.path.splitext(audio_name)[1] or ".webm"
            with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as af:
                af.write(audio_bytes)
                audio_path = af.name

            segments, _ = model.transcribe(audio_path, beam_size=5)
            text = " ".join(seg.text for seg in segments).strip()
            os.unlink(audio_path)
            self._send_json({"text": text})

        except Exception as e:
            print(f"[whisper] Transcription ERROR: {e}")
            self._send_json({"error": str(e)}, 500)

    def _send_json(self, data, status=200):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def _manual_parse(body: bytes, boundary: bytes):
    """Minimal multipart parser — fallback when python-multipart API varies."""
    delimiter = b"--" + boundary
    parts = body.split(delimiter)
    for part in parts:
        if b'name="file"' in part or b"name=file" in part:
            # Split headers from body on double CRLF
            if b"\r\n\r\n" in part:
                headers_raw, content = part.split(b"\r\n\r\n", 1)
            elif b"\n\n" in part:
                headers_raw, content = part.split(b"\n\n", 1)
            else:
                continue
            # Strip trailing boundary delimiter
            content = content.rstrip(b"\r\n-")
            # Extract filename
            fname = "audio.webm"
            for tok in headers_raw.split(b";"):
                tok = tok.strip()
                if tok.startswith(b"filename="):
                    fname = tok[9:].strip(b'"').decode(errors="replace")
            return content, fname
    return None, "audio.webm"


# ── Entry point ───────────────────────────────────────────────────────────────
if __name__ == "__main__":
    server = http.server.HTTPServer(("0.0.0.0", PORT), WhisperHandler)
    print(f"[whisper] Ready. Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[whisper] Stopped.")
