-- =============================================================================
-- Olla Nest — Database Schema  V5 (Web Search, Image Generation, Voice)
-- Adds web_search_log and image_generation_log tables for audit/analytics.
-- Provider settings (API keys, URLs) are stored in the existing settings table.
-- =============================================================================

-- Log of web searches performed during chat (for admin analytics)
CREATE TABLE IF NOT EXISTS web_search_log (
  id           TEXT PRIMARY KEY,
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  session_id   TEXT,
  query        TEXT NOT NULL,
  provider     TEXT NOT NULL,  -- serper|brave|searxng
  results_count INTEGER NOT NULL DEFAULT 0,
  latency_ms   INTEGER,
  created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Log of image generations (for admin analytics + rate limiting)
CREATE TABLE IF NOT EXISTS image_generation_log (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  session_id TEXT,
  prompt     TEXT NOT NULL,
  provider   TEXT NOT NULL,  -- dalle|stable-diffusion
  model      TEXT,
  size       TEXT,
  status     TEXT NOT NULL DEFAULT 'ok',  -- ok|error
  error      TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_web_search_log_user ON web_search_log(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_image_gen_log_user  ON image_generation_log(user_id, created_at);
