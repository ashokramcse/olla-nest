-- =============================================================================
-- Olla Nest — V12: Cookbook State, Vault, Companion Tokens, Event Log
-- Covers categories J, S, U, and infrastructure for X
-- =============================================================================

-- ── Cookbook: downloaded/served models ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS cookbook_models (
  id              TEXT PRIMARY KEY,
  hf_repo         TEXT NOT NULL,                  -- e.g. bartowski/Llama-3.2-3B-GGUF
  hf_filename     TEXT,                           -- specific file within repo
  display_name    TEXT NOT NULL DEFAULT '',
  quantization    TEXT NOT NULL DEFAULT 'Q4_K_M',
  format          TEXT NOT NULL DEFAULT 'gguf',   -- gguf|fp8|awq|gptq|mlx
  size_gb         REAL,
  params_b        REAL,
  use_case        TEXT NOT NULL DEFAULT 'general',
  is_downloaded   INTEGER NOT NULL DEFAULT 0,
  local_path      TEXT,
  is_served       INTEGER NOT NULL DEFAULT 0,
  serve_port      INTEGER,
  serve_backend   TEXT,                           -- llama.cpp|vllm|ollama
  serve_pid       INTEGER,
  registered_as_endpoint_id TEXT,
  last_seen_at    TEXT,
  created_at      TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_cookbook_models_downloaded ON cookbook_models(is_downloaded);

-- ── Companion / Mobile pairing tokens ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS companion_tokens (
  id           TEXT PRIMARY KEY,
  owner        TEXT NOT NULL,
  token_hash   TEXT NOT NULL UNIQUE,
  name         TEXT NOT NULL DEFAULT 'Mobile Device',
  scopes_json  TEXT NOT NULL DEFAULT '["chat"]',
  is_active    INTEGER NOT NULL DEFAULT 1,
  last_used_at TEXT,
  created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_companion_tokens_owner ON companion_tokens(owner, is_active);

-- ── Vault config ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS vault_config (
  id           TEXT PRIMARY KEY DEFAULT 'singleton',
  bw_path      TEXT NOT NULL DEFAULT 'bw',
  session_enc  TEXT,                              -- encrypted BW_SESSION
  server_url   TEXT,
  enabled      INTEGER NOT NULL DEFAULT 0,
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ── Event log (event bus audit trail) ────────────────────────────────────────
CREATE TABLE IF NOT EXISTS event_log (
  id           TEXT PRIMARY KEY,
  event_name   TEXT NOT NULL,
  owner        TEXT,
  payload_json TEXT NOT NULL DEFAULT '{}',
  created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_event_log_name ON event_log(event_name, created_at);
CREATE INDEX IF NOT EXISTS idx_event_log_owner ON event_log(owner, created_at);

-- ── YouTube transcript cache ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS youtube_transcripts (
  video_id     TEXT PRIMARY KEY,
  title        TEXT,
  transcript   TEXT NOT NULL,
  cached_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ── Search cache index (disk-based cache tracked in DB) ───────────────────────
CREATE TABLE IF NOT EXISTS search_cache_index (
  cache_key    TEXT PRIMARY KEY,
  query        TEXT NOT NULL,
  provider     TEXT NOT NULL,
  query_type   TEXT NOT NULL DEFAULT 'general',
  cached_at    TEXT NOT NULL DEFAULT (datetime('now')),
  expires_at   TEXT NOT NULL,
  hit_count    INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_search_cache_expires ON search_cache_index(expires_at);

-- ── Prompt security audit ─────────────────────────────────────────────────────
-- Tracks when prompt-injection policy was applied (for admin visibility)
CREATE TABLE IF NOT EXISTS prompt_security_log (
  id           TEXT PRIMARY KEY,
  owner        TEXT,
  session_id   TEXT,
  source_type  TEXT NOT NULL,                     -- rag|web|email|memory|skill|connector
  flagged      INTEGER NOT NULL DEFAULT 0,
  created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_prompt_security_log_date ON prompt_security_log(created_at);
