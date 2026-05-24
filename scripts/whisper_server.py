#!/usr/bin/env python3
"""
Olla Nest — Local Whisper Transcription Server
================================================
A lightweight OpenAI-compatible speech-to-text server powered by faster-whisper.
Exposes POST /v1/audio/transcriptions — the same endpoint format as OpenAI Whisper API.

Usage
-----
  # First time only — create venv and install:
  python3 -m venv scripts/venv
  source scripts/venv/bin/activate
  pip install faster-whisper

  # Every time — start the server:
  source scripts/venv/bin/activate && python scripts/whisper_server.py

  # Optional: choose model size (tiny/base/small/medium/large-v3)
  WHISPER_MODEL=small python scripts/whisper_server.py

  # Optional: change port (default 8000)
  WHISPER_PORT=9000 python scripts/whisper_server.py

Model sizes (tradeoff: speed vs accuracy)
  tiny   — fastest, ~39 MB,  good for clear speech
  base   — fast,    ~74 MB,  recommended default
  small  — medium,  ~244 MB, better accuracy
  medium — slower,  ~769 MB, near-OpenAI quality
  large-v3 — slowest, ~1.5 GB, matches OpenAI quality

Admin Settings → STT Provider: Local Whisper Server
URL: http://localhost:8000/v1/audio/transcriptions
"""

import os
import sys
import tempfile
import http.server
import json
import cgi

# ---------------------------------------------------------------------------
# Dependency check
# ---------------------------------------------------------------------------
try:
    from faster_whisper import WhisperModel
except ImportError:
    print("ERROR: faster-whisper is not installed.")
    print("Run: pip install faster-whisper")
    sys.exit(1)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
MODEL_SIZE = os.environ.get("WHISPER_MODEL", "base")
PORT       = int(os.environ.get("WHISPER_PORT", "8000"))
DEVICE     = "cpu"   # change to "cuda" if you have an NVIDIA GPU

print(f"[whisper-server] Loading model '{MODEL_SIZE}' on {DEVICE}…")
model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type="int8")
print(f"[whisper-server] Model ready. Listening on http://localhost:{PORT}")


# ---------------------------------------------------------------------------
# HTTP handler
# ---------------------------------------------------------------------------
class WhisperHandler(http.server.BaseHTTPRequestHandler):

    def log_message(self, fmt, *args):
        print(f"[whisper-server] {self.address_string()} {fmt % args}")

    def do_POST(self):
        if self.path != "/v1/audio/transcriptions":
            self._send_json({"error": "Not found"}, 404)
            return

        # Parse multipart form-data
        content_type = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in content_type:
            self._send_json({"error": "Expected multipart/form-data"}, 400)
            return

        length = int(self.headers.get("Content-Length", 0))
        body   = self.rfile.read(length)

        # Write body to a temp file so cgi.FieldStorage can parse it
        with tempfile.NamedTemporaryFile(delete=False, suffix=".bin") as tmp:
            tmp.write(body)
            tmp_path = tmp.name

        try:
            import io
            environ = {
                "REQUEST_METHOD": "POST",
                "CONTENT_TYPE":   content_type,
                "CONTENT_LENGTH": str(length),
            }
            form = cgi.FieldStorage(
                fp=io.BytesIO(body),
                environ=environ,
                keep_blank_values=True,
            )

            audio_field = form.getvalue("file")
            if audio_field is None:
                self._send_json({"error": "Missing 'file' field"}, 400)
                return

            audio_bytes = audio_field if isinstance(audio_field, bytes) else audio_field.encode()

            # Write audio to a temp file
            suffix = ".webm"
            if "file" in form and hasattr(form["file"], "filename"):
                fname = form["file"].filename or "audio.webm"
                suffix = os.path.splitext(fname)[1] or ".webm"

            with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as af:
                af.write(audio_bytes)
                audio_path = af.name

            # Transcribe
            segments, info = model.transcribe(audio_path, beam_size=5)
            text = " ".join(seg.text for seg in segments).strip()
            os.unlink(audio_path)

            self._send_json({"text": text})

        except Exception as e:
            print(f"[whisper-server] ERROR: {e}")
            self._send_json({"error": str(e)}, 500)
        finally:
            try:
                os.unlink(tmp_path)
            except Exception:
                pass

    def _send_json(self, data, status=200):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


# ---------------------------------------------------------------------------
# Start server
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    server = http.server.HTTPServer(("0.0.0.0", PORT), WhisperHandler)
    print(f"[whisper-server] Serving on http://0.0.0.0:{PORT}  (Ctrl+C to stop)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[whisper-server] Stopped.")
