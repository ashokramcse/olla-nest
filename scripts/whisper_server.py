#!/usr/bin/env python3
"""
Olla Nest — Local Whisper Transcription Server
================================================
OpenAI-compatible speech-to-text server powered by faster-whisper.
Supports: macOS, Linux (Ubuntu/Debian/RHEL/CentOS/Alpine), Windows, Windows Server

Exposes:
  POST /v1/audio/transcriptions   — transcribe audio (OpenAI-compatible)
  GET  /health                    — health check (returns {"status":"ok"})

First-time setup
----------------
  macOS / Linux:
    bash scripts/start_whisper.sh

  Windows / Windows Server:
    scripts\\start_whisper.bat
    -- or --
    .\\scripts\\start_whisper.ps1

Environment variables
---------------------
  WHISPER_MODEL   tiny | base (default) | small | medium | large-v3
  WHISPER_PORT    port number (default: 8765)

Model sizes
-----------
  tiny     ~39 MB   fastest, good for clear speech
  base     ~74 MB   recommended (default)
  small    ~244 MB  better accuracy
  medium   ~769 MB  near-OpenAI quality
  large-v3 ~1.5 GB  matches OpenAI quality
"""

import os
import sys
import json
import tempfile
import http.server
import email.parser
import email.policy
from pathlib import Path

# ── Dependency check ─────────────────────────────────────────────────────────
def _require(module, package):
    try:
        __import__(module)
    except ImportError:
        print(f"[whisper] ERROR: '{package}' is not installed.")
        print(f"[whisper]   Run the setup script for your platform:")
        if sys.platform == "win32":
            print(f"[whisper]   scripts\\start_whisper.bat")
        else:
            print(f"[whisper]   bash scripts/start_whisper.sh")
        sys.exit(1)

_require("faster_whisper", "faster-whisper")

from faster_whisper import WhisperModel

# ── Config ───────────────────────────────────────────────────────────────────
MODEL_SIZE  = os.environ.get("WHISPER_MODEL", "base")
PORT        = int(os.environ.get("WHISPER_PORT", "8765"))
DEVICE      = "cpu"      # change to "cuda" for NVIDIA GPU
COMPUTE     = "int8"     # int8 = fastest on CPU; float16 for GPU

print(f"[whisper] Platform : {sys.platform}")
print(f"[whisper] Loading model '{MODEL_SIZE}' on {DEVICE} ({COMPUTE})…")
model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE)
print(f"[whisper] Model ready.")
print(f"[whisper] Listening on http://0.0.0.0:{PORT}")
print(f"[whisper] Endpoint : POST http://localhost:{PORT}/v1/audio/transcriptions")


# ── Multipart parser (no external deps — works Python 3.9 → 3.14+) ──────────

def parse_multipart(body: bytes, content_type: str):
    """
    Parse a multipart/form-data body and return the 'file' field as
    (audio_bytes, filename).  Uses stdlib email.parser — no cgi/multipart needed.
    Works identically on macOS, Linux, and Windows.
    """
    # email.parser needs a MIME message with headers
    mime_header = f"Content-Type: {content_type}\r\n\r\n"
    raw = mime_header.encode() + body

    msg = email.parser.BytesParser(policy=email.policy.compat32).parsebytes(raw)

    if not msg.is_multipart():
        return None, "audio.webm"

    for part in msg.walk():
        disposition = part.get("Content-Disposition", "")
        if 'name="file"' in disposition or "name=file" in disposition:
            filename = "audio.webm"
            # Extract filename from Content-Disposition
            for token in disposition.split(";"):
                token = token.strip()
                if token.lower().startswith("filename="):
                    filename = token[9:].strip().strip('"')
            payload = part.get_payload(decode=True)
            if payload:
                return payload, filename

    # Fallback: manual boundary split (handles edge-case encodings)
    return _manual_split(body, content_type)


def _manual_split(body: bytes, content_type: str):
    """Minimal boundary splitter used when email.parser finds no file part."""
    boundary = None
    for token in content_type.split(";"):
        token = token.strip()
        if token.lower().startswith("boundary="):
            boundary = token[9:].strip().strip('"').encode()
    if not boundary:
        return None, "audio.webm"

    delimiter = b"--" + boundary
    for part in body.split(delimiter):
        if b'name="file"' in part or b"name=file" in part:
            sep = b"\r\n\r\n" if b"\r\n\r\n" in part else b"\n\n"
            if sep in part:
                header_raw, content = part.split(sep, 1)
                content = content.rstrip(b"\r\n-")
                fname = "audio.webm"
                for tok in header_raw.split(b";"):
                    tok = tok.strip()
                    if tok.lower().startswith(b"filename="):
                        fname = tok[9:].strip(b'"').decode(errors="replace")
                return content, fname
    return None, "audio.webm"


# ── HTTP handler ─────────────────────────────────────────────────────────────

class WhisperHandler(http.server.BaseHTTPRequestHandler):

    def log_message(self, fmt, *args):
        print(f"[whisper] {self.address_string()} — {fmt % args}", flush=True)

    # ── GET /health ───────────────────────────────────────────────────────────
    def do_GET(self):
        if self.path == "/health":
            self._json({"status": "ok", "model": MODEL_SIZE, "port": PORT})
        else:
            self._json({"error": "Not found"}, 404)

    # ── POST /v1/audio/transcriptions ─────────────────────────────────────────
    def do_POST(self):
        if self.path != "/v1/audio/transcriptions":
            self._json({"error": "Not found"}, 404)
            return

        content_type = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in content_type:
            self._json({"error": "Expected multipart/form-data"}, 400)
            return

        length = int(self.headers.get("Content-Length", 0))
        body   = self.rfile.read(length)

        audio_bytes, audio_name = parse_multipart(body, content_type)

        if not audio_bytes:
            self._json({"error": "Could not parse 'file' field from multipart body"}, 400)
            return

        # Write audio to a platform-safe temp file
        suffix = Path(audio_name).suffix or ".webm"
        tmp_path = None
        try:
            with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
                tmp.write(audio_bytes)
                tmp_path = tmp.name

            segments, info = model.transcribe(tmp_path, beam_size=5)
            text = " ".join(seg.text for seg in segments).strip()
            print(f"[whisper] Transcribed {len(audio_bytes):,} bytes → {len(text)} chars", flush=True)
            self._json({"text": text})

        except Exception as exc:
            print(f"[whisper] Transcription error: {exc}", flush=True)
            self._json({"error": str(exc)}, 500)
        finally:
            if tmp_path:
                try:
                    os.unlink(tmp_path)
                except OSError:
                    pass

    def _json(self, data, status=200):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    server = http.server.HTTPServer(("0.0.0.0", PORT), WhisperHandler)
    print(f"[whisper] Ready. Press Ctrl+C to stop.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[whisper] Stopped.")
