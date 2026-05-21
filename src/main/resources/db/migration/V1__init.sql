-- V1: Initial schema for Olla Nest (translated from src/db/index.js)

CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS departments (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS groups (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS teams (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  description TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  email TEXT UNIQUE,
  password_hash TEXT,
  role TEXT NOT NULL,
  rights TEXT NOT NULL DEFAULT '["chat:use"]',
  department_id TEXT,
  active INTEGER NOT NULL DEFAULT 1,
  employee_id TEXT,
  designation TEXT,
  team TEXT,
  branch TEXT,
  manager TEXT,
  organization TEXT NOT NULL DEFAULT 'Olla Nest',
  ai_access_tier TEXT NOT NULL DEFAULT 'standard',
  daily_token_limit INTEGER NOT NULL DEFAULT 50000,
  monthly_token_limit INTEGER NOT NULL DEFAULT 1000000,
  gpu_quota_minutes INTEGER NOT NULL DEFAULT 120,
  vram_limit_mb INTEGER NOT NULL DEFAULT 8192,
  concurrent_model_limit INTEGER NOT NULL DEFAULT 1,
  api_rate_limit_per_minute INTEGER NOT NULL DEFAULT 30,
  max_context_size INTEGER NOT NULL DEFAULT 8192,
  mfa_enabled INTEGER NOT NULL DEFAULT 0,
  security_risk_score INTEGER NOT NULL DEFAULT 10,
  access_status TEXT NOT NULL DEFAULT 'active',
  access_expires_at TEXT,
  last_active_at TEXT,
  auth_provider TEXT NOT NULL DEFAULT 'local',
  phone TEXT NOT NULL DEFAULT '',
  avatar_initials TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS user_groups (
  user_id TEXT NOT NULL,
  group_id TEXT NOT NULL,
  PRIMARY KEY (user_id, group_id)
);

CREATE TABLE IF NOT EXISTS models (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  provider TEXT NOT NULL,
  model_ref TEXT NOT NULL,
  status TEXT NOT NULL,
  capabilities TEXT NOT NULL,
  speed_score INTEGER NOT NULL,
  quality_score INTEGER NOT NULL,
  privacy TEXT NOT NULL,
  context_size INTEGER,
  last_seen_at TEXT,
  governance_tier TEXT NOT NULL DEFAULT 'approved-local',
  resource_tier TEXT NOT NULL DEFAULT 'standard',
  gpu_required INTEGER NOT NULL DEFAULT 0,
  max_concurrency INTEGER NOT NULL DEFAULT 2,
  max_context_size INTEGER,
  external_cost_tier TEXT NOT NULL DEFAULT 'local-free',
  sensitive_allowed INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS access_grants (
  id TEXT PRIMARY KEY,
  subject_type TEXT NOT NULL,
  subject_id TEXT NOT NULL,
  model_id TEXT NOT NULL,
  can_use INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS role_catalog (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT,
  permissions TEXT NOT NULL DEFAULT '[]',
  system_role INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS permission_catalog (
  key TEXT PRIMARY KEY,
  category TEXT NOT NULL,
  description TEXT NOT NULL,
  risk_level TEXT NOT NULL DEFAULT 'low'
);

CREATE TABLE IF NOT EXISTS user_overrides (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  permission_key TEXT NOT NULL,
  model_id TEXT,
  effect TEXT NOT NULL,
  reason TEXT,
  expires_at TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_sessions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT 'New Chat',
  pinned INTEGER NOT NULL DEFAULT 0,
  archived INTEGER NOT NULL DEFAULT 0,
  unread INTEGER NOT NULL DEFAULT 0,
  is_active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_messages (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  user_id TEXT,
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  mode TEXT,
  model_id TEXT,
  model_name TEXT,
  route_reason TEXT,
  tokens_used INTEGER DEFAULT 0,
  latency_ms INTEGER DEFAULT 0,
  live INTEGER DEFAULT 1,
  artifacts_json TEXT,
  extracted_files_json TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_events (
  id TEXT PRIMARY KEY,
  actor TEXT,
  action TEXT NOT NULL,
  detail TEXT,
  extra_json TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS router_traces (
  id TEXT PRIMARY KEY,
  user_id TEXT,
  session_id TEXT,
  message TEXT,
  mode TEXT,
  selected_model_id TEXT,
  tags_json TEXT,
  candidates_json TEXT,
  live INTEGER DEFAULT 1,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS workspace_prefs (
  user_id TEXT PRIMARY KEY,
  workspace_root TEXT NOT NULL,
  permission_mode TEXT NOT NULL DEFAULT 'default',
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS api_providers (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  type TEXT NOT NULL,
  base_url TEXT,
  api_key_enc TEXT NOT NULL,
  enabled INTEGER DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS api_models (
  id TEXT PRIMARY KEY,
  provider_id TEXT NOT NULL,
  model_id TEXT NOT NULL,
  display_name TEXT NOT NULL,
  capability_tags TEXT,
  context_window INTEGER,
  is_approved INTEGER DEFAULT 0,
  governance_tag TEXT DEFAULT 'approved',
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS feedback (
  id TEXT PRIMARY KEY,
  message_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  rating INTEGER NOT NULL,
  comment TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS login_attempts (
  ip TEXT PRIMARY KEY,
  count INTEGER NOT NULL DEFAULT 0,
  reset_at INTEGER NOT NULL
);

-- Performance indexes
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);
CREATE INDEX IF NOT EXISTS idx_chat_messages_session_id ON chat_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_chat_messages_user_date ON chat_messages(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_messages_created_at ON chat_messages(created_at);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_active ON chat_sessions(user_id, is_active);
CREATE INDEX IF NOT EXISTS idx_audit_events_created_at ON audit_events(created_at);
CREATE INDEX IF NOT EXISTS idx_router_traces_created_at ON router_traces(created_at);
CREATE INDEX IF NOT EXISTS idx_feedback_message_id ON feedback(message_id);
CREATE INDEX IF NOT EXISTS idx_access_grants_subject ON access_grants(subject_type, subject_id);
CREATE INDEX IF NOT EXISTS idx_user_overrides_user ON user_overrides(user_id);
