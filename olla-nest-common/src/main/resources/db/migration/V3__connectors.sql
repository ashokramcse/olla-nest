-- =============================================================================
-- Olla Nest — Database Schema  V3 (Connectors / Knowledge Sync)
-- Adds connector_configs, connector_sync_log, connector_documents tables.
-- Connectors pull content from external services (GitHub, Slack, Notion, etc.)
-- and ingest it into the RAG knowledge base automatically on a schedule.
-- =============================================================================

-- Master config per connector instance (one row = one configured integration)
CREATE TABLE IF NOT EXISTS connector_configs (
  id           TEXT PRIMARY KEY,                -- e.g. conn-github-abc123
  name         TEXT NOT NULL,                   -- human label: "Engineering GitHub"
  type         TEXT NOT NULL,                   -- github|gdrive|slack|notion|jira|confluence|...
  enabled      INTEGER NOT NULL DEFAULT 1,
  auth_type    TEXT NOT NULL DEFAULT 'api_key', -- oauth|api_key|basic|token
  credentials_enc TEXT,                         -- AES-256-GCM encrypted JSON credentials
  config_json  TEXT,                            -- non-secret config (org, repo, channel, etc.)
  last_synced_at TEXT,
  sync_status  TEXT NOT NULL DEFAULT 'idle',    -- idle|syncing|ok|error
  sync_error   TEXT,
  docs_total   INTEGER NOT NULL DEFAULT 0,
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Log of each sync run (kept for 30 days, cleaned by scheduler)
CREATE TABLE IF NOT EXISTS connector_sync_log (
  id              TEXT PRIMARY KEY,
  connector_id    TEXT NOT NULL REFERENCES connector_configs(id) ON DELETE CASCADE,
  started_at      TEXT NOT NULL DEFAULT (datetime('now')),
  finished_at     TEXT,
  docs_synced     INTEGER NOT NULL DEFAULT 0,
  docs_deleted    INTEGER NOT NULL DEFAULT 0,
  error           TEXT,
  status          TEXT NOT NULL DEFAULT 'running'  -- running|ok|error
);

-- One row per external document ingested via a connector
CREATE TABLE IF NOT EXISTS connector_documents (
  id           TEXT PRIMARY KEY,
  connector_id TEXT NOT NULL REFERENCES connector_configs(id) ON DELETE CASCADE,
  external_id  TEXT NOT NULL,           -- source-system identifier (issue #123, page UUID, etc.)
  title        TEXT NOT NULL,
  url          TEXT,
  content_hash TEXT,                    -- SHA-256 of content; used to skip unchanged docs
  rag_doc_id   TEXT REFERENCES rag_documents(id) ON DELETE SET NULL,
  synced_at    TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(connector_id, external_id)
);

CREATE INDEX IF NOT EXISTS idx_connector_docs_connector ON connector_documents(connector_id);
CREATE INDEX IF NOT EXISTS idx_connector_sync_log_connector ON connector_sync_log(connector_id);
