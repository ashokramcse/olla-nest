-- =============================================================================
-- Olla Nest — V11: MCP Servers, Webhooks, API Tokens, Gallery, Editor Drafts
-- Covers categories L, O, P from the feature expansion
-- =============================================================================

-- ── MCP servers ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS mcp_servers (
  id              TEXT PRIMARY KEY,
  name            TEXT NOT NULL,
  command         TEXT NOT NULL,
  args_json       TEXT NOT NULL DEFAULT '[]',
  env_json        TEXT NOT NULL DEFAULT '{}',
  transport       TEXT NOT NULL DEFAULT 'stdio',  -- stdio|sse|http
  url             TEXT,                            -- for sse/http transport
  oauth_config_json TEXT,
  disabled_tools_json TEXT NOT NULL DEFAULT '[]',
  enabled         INTEGER NOT NULL DEFAULT 1,
  team_id         TEXT,
  created_at      TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_mcp_servers_enabled ON mcp_servers(enabled);

-- ── Outgoing webhooks ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS webhooks (
  id           TEXT PRIMARY KEY,
  owner        TEXT NOT NULL,
  name         TEXT NOT NULL,
  url          TEXT NOT NULL,
  secret       TEXT,                               -- HMAC signing secret (plaintext, short-lived)
  events_json  TEXT NOT NULL DEFAULT '[]',         -- ["session.created","chat.completed",...]
  enabled      INTEGER NOT NULL DEFAULT 1,
  last_fired_at TEXT,
  last_status  INTEGER,
  team_id      TEXT,
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_webhooks_owner ON webhooks(owner, enabled);

-- ── API tokens ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS api_tokens (
  id             TEXT PRIMARY KEY,
  owner          TEXT NOT NULL,
  name           TEXT NOT NULL,
  token_hash     TEXT NOT NULL UNIQUE,             -- bcrypt hash
  token_prefix   TEXT NOT NULL,                    -- first 12 chars for display
  scopes_json    TEXT NOT NULL DEFAULT '["chat"]',
  is_active      INTEGER NOT NULL DEFAULT 1,
  last_used_at   TEXT,
  created_at     TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_api_tokens_owner ON api_tokens(owner, is_active);
CREATE INDEX IF NOT EXISTS idx_api_tokens_hash ON api_tokens(token_hash);

-- ── Gallery albums ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS gallery_albums (
  id           TEXT PRIMARY KEY,
  owner        TEXT NOT NULL,
  name         TEXT NOT NULL DEFAULT 'Album',
  description  TEXT,
  cover_image_id TEXT,
  team_id      TEXT,
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_gallery_albums_owner ON gallery_albums(owner);

-- ── Gallery images ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS gallery_images (
  id           TEXT PRIMARY KEY,
  owner        TEXT NOT NULL,
  album_id     TEXT REFERENCES gallery_albums(id) ON DELETE SET NULL,
  filename     TEXT NOT NULL,
  file_path    TEXT NOT NULL,
  file_hash    TEXT NOT NULL,
  file_size    INTEGER NOT NULL DEFAULT 0,
  mime_type    TEXT NOT NULL DEFAULT 'image/jpeg',
  width        INTEGER,
  height       INTEGER,
  exif_json    TEXT,
  is_generated INTEGER NOT NULL DEFAULT 0,         -- AI-generated vs uploaded
  prompt       TEXT,                               -- for AI-generated
  model        TEXT,
  is_active    INTEGER NOT NULL DEFAULT 1,
  created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_gallery_images_owner ON gallery_images(owner, is_active);
CREATE INDEX IF NOT EXISTS idx_gallery_images_album ON gallery_images(album_id);
CREATE INDEX IF NOT EXISTS idx_gallery_images_hash ON gallery_images(file_hash);

-- ── Editor drafts (canvas sessions) ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS editor_drafts (
  id               TEXT PRIMARY KEY,
  owner            TEXT NOT NULL,
  name             TEXT NOT NULL DEFAULT 'Untitled',
  source_image_id  TEXT,
  width            INTEGER,
  height           INTEGER,
  payload_json     TEXT NOT NULL DEFAULT '{}',     -- layers, offsets, opacities
  thumbnail        TEXT,                            -- small data URL
  created_at       TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at       TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_editor_drafts_owner ON editor_drafts(owner);

-- ── Research tasks (persistent deep research) ─────────────────────────────────
CREATE TABLE IF NOT EXISTS research_tasks (
  id           TEXT PRIMARY KEY,
  owner        TEXT NOT NULL,
  session_id   TEXT,
  query        TEXT NOT NULL,
  status       TEXT NOT NULL DEFAULT 'running',   -- running|completed|cancelled|error
  progress_json TEXT NOT NULL DEFAULT '{}',
  result_json  TEXT,
  report_html  TEXT,
  sources_json TEXT NOT NULL DEFAULT '[]',
  model        TEXT,
  endpoint_url TEXT,
  max_time_s   INTEGER NOT NULL DEFAULT 300,
  started_at   TEXT NOT NULL DEFAULT (datetime('now')),
  finished_at  TEXT,
  duration_ms  INTEGER
);

CREATE INDEX IF NOT EXISTS idx_research_tasks_owner ON research_tasks(owner, status);
CREATE INDEX IF NOT EXISTS idx_research_tasks_session ON research_tasks(session_id);

-- ── Background jobs ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS background_jobs (
  id           TEXT PRIMARY KEY,
  owner        TEXT,
  job_type     TEXT NOT NULL,                      -- download|research|email_poll|connector_sync|...
  name         TEXT NOT NULL DEFAULT 'Job',
  status       TEXT NOT NULL DEFAULT 'running',    -- running|completed|cancelled|error
  progress     INTEGER NOT NULL DEFAULT 0,         -- 0-100
  progress_msg TEXT,
  result_json  TEXT,
  error        TEXT,
  pid          INTEGER,                            -- OS process ID if subprocess
  started_at   TEXT NOT NULL DEFAULT (datetime('now')),
  finished_at  TEXT
);

CREATE INDEX IF NOT EXISTS idx_background_jobs_status ON background_jobs(status, started_at);
CREATE INDEX IF NOT EXISTS idx_background_jobs_owner ON background_jobs(owner, status);
