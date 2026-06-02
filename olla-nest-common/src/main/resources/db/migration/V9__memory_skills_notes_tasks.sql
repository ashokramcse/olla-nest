-- =============================================================================
-- Olla Nest — V9: Memory, Skills, Notes, Tasks, Crew Members, Comparisons
-- Covers categories E, I, K, N from the feature expansion
-- =============================================================================

-- ── Memory system ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS memories (
  id           TEXT PRIMARY KEY,
  owner        TEXT NOT NULL,
  text         TEXT NOT NULL,
  source       TEXT NOT NULL DEFAULT 'user',        -- user|agent|extractor
  session_id   TEXT,
  embedding_json TEXT,                               -- vector for semantic search
  tags_json    TEXT NOT NULL DEFAULT '[]',
  importance   INTEGER NOT NULL DEFAULT 5,           -- 1-10
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_memories_owner ON memories(owner, created_at);
CREATE INDEX IF NOT EXISTS idx_memories_session ON memories(session_id);

-- ── Skills system ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS skills (
  id                   TEXT PRIMARY KEY,
  name                 TEXT NOT NULL,
  description          TEXT NOT NULL DEFAULT '',
  category             TEXT NOT NULL DEFAULT 'general',
  tags_json            TEXT NOT NULL DEFAULT '[]',
  platforms_json       TEXT NOT NULL DEFAULT '[]',
  when_to_use          TEXT NOT NULL DEFAULT '',
  procedure_json       TEXT NOT NULL DEFAULT '[]',
  pitfalls_json        TEXT NOT NULL DEFAULT '[]',
  verification_json    TEXT NOT NULL DEFAULT '[]',
  status               TEXT NOT NULL DEFAULT 'draft',  -- draft|active|archived
  confidence           REAL NOT NULL DEFAULT 0.8,
  source               TEXT NOT NULL DEFAULT 'user',   -- user|learned
  owner                TEXT,                            -- null = shared/team
  teacher_model        TEXT,
  session_id           TEXT,
  version              TEXT NOT NULL DEFAULT '1.0.0',
  use_count            INTEGER NOT NULL DEFAULT 0,
  created_at           TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at           TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_skills_category ON skills(category);
CREATE INDEX IF NOT EXISTS idx_skills_owner ON skills(owner, status);
CREATE INDEX IF NOT EXISTS idx_skills_status ON skills(status);

-- ── Notes system ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS notes (
  id           TEXT PRIMARY KEY,
  owner        TEXT NOT NULL,
  title        TEXT NOT NULL DEFAULT '',
  content      TEXT,
  items_json   TEXT,                                   -- for checklists
  note_type    TEXT NOT NULL DEFAULT 'note',           -- note|checklist
  color        TEXT NOT NULL DEFAULT 'default',
  label        TEXT,
  pinned       INTEGER NOT NULL DEFAULT 0,
  archived     INTEGER NOT NULL DEFAULT 0,
  due_date     TEXT,
  repeat       TEXT NOT NULL DEFAULT 'none',           -- none|daily|weekly|monthly
  source       TEXT NOT NULL DEFAULT 'user',
  session_id   TEXT,
  image_url    TEXT,
  sort_order   INTEGER NOT NULL DEFAULT 0,
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_notes_owner ON notes(owner, archived, pinned);
CREATE INDEX IF NOT EXISTS idx_notes_due ON notes(due_date) WHERE due_date IS NOT NULL;

-- ── Scheduled tasks ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS scheduled_tasks (
  id                   TEXT PRIMARY KEY,
  owner                TEXT NOT NULL,
  name                 TEXT NOT NULL DEFAULT 'Task',
  prompt               TEXT,
  task_type            TEXT NOT NULL DEFAULT 'llm',       -- llm|action|research
  action               TEXT,
  schedule             TEXT NOT NULL DEFAULT 'daily',     -- once|daily|weekly|monthly|cron
  scheduled_time       TEXT NOT NULL DEFAULT '09:00',
  scheduled_day        INTEGER,
  scheduled_date       TEXT,
  cron_expression      TEXT,
  trigger_type         TEXT NOT NULL DEFAULT 'schedule',  -- schedule|event|webhook
  trigger_event        TEXT,
  trigger_count        INTEGER,
  output_target        TEXT NOT NULL DEFAULT 'session',   -- session|note|email
  model                TEXT,
  endpoint_url         TEXT,
  then_task_id         TEXT,
  notifications_enabled INTEGER NOT NULL DEFAULT 1,
  status               TEXT NOT NULL DEFAULT 'active',    -- active|paused|completed
  next_run             TEXT,
  last_run             TEXT,
  run_count            INTEGER NOT NULL DEFAULT 0,
  created_at           TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at           TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_scheduled_tasks_owner ON scheduled_tasks(owner, status);
CREATE INDEX IF NOT EXISTS idx_scheduled_tasks_next_run ON scheduled_tasks(next_run) WHERE status = 'active';

-- ── Task run history ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS task_runs (
  id           TEXT PRIMARY KEY,
  task_id      TEXT NOT NULL REFERENCES scheduled_tasks(id) ON DELETE CASCADE,
  status       TEXT NOT NULL DEFAULT 'running',    -- running|ok|error|cancelled
  output       TEXT,
  error        TEXT,
  started_at   TEXT NOT NULL DEFAULT (datetime('now')),
  finished_at  TEXT,
  duration_ms  INTEGER
);

CREATE INDEX IF NOT EXISTS idx_task_runs_task ON task_runs(task_id, started_at);

-- ── Personal AI Assistant (CrewMember) ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS crew_members (
  id                     TEXT PRIMARY KEY,
  owner                  TEXT NOT NULL UNIQUE,
  name                   TEXT NOT NULL DEFAULT 'Assistant',
  avatar                 TEXT NOT NULL DEFAULT '🤖',
  personality            TEXT NOT NULL DEFAULT '',
  model                  TEXT,
  endpoint_url           TEXT,
  enabled_tools_json     TEXT NOT NULL DEFAULT '[]',
  timezone               TEXT NOT NULL DEFAULT 'UTC',
  greeting               TEXT NOT NULL DEFAULT 'Hello! How can I help you today?',
  allow_autonomous_email INTEGER NOT NULL DEFAULT 0,
  session_id             TEXT,
  created_at             TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at             TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_crew_members_owner ON crew_members(owner);

-- ── Model comparisons (blind A/B test) ───────────────────────────────────────
CREATE TABLE IF NOT EXISTS comparisons (
  id           TEXT PRIMARY KEY,
  owner        TEXT NOT NULL,
  prompt       TEXT NOT NULL,
  model_a      TEXT NOT NULL,
  model_b      TEXT NOT NULL,
  endpoint_a   TEXT NOT NULL,
  endpoint_b   TEXT NOT NULL,
  session_id_a TEXT,
  session_id_b TEXT,
  is_blind     INTEGER NOT NULL DEFAULT 1,
  winner       TEXT,                             -- model_a name | model_b name | tie | null (pending)
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  voted_at     TEXT
);

CREATE INDEX IF NOT EXISTS idx_comparisons_owner ON comparisons(owner, created_at);

-- ── Presets / User templates ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_templates (
  id             TEXT PRIMARY KEY,
  owner          TEXT NOT NULL,
  name           TEXT NOT NULL,
  system_prompt  TEXT NOT NULL DEFAULT '',
  temperature    REAL NOT NULL DEFAULT 1.0,
  max_tokens     INTEGER NOT NULL DEFAULT 0,
  inject_prefix  TEXT NOT NULL DEFAULT '',
  inject_suffix  TEXT NOT NULL DEFAULT '',
  sort_order     INTEGER NOT NULL DEFAULT 0,
  created_at     TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at     TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_user_templates_owner ON user_templates(owner);

-- ── Signatures (visual stamps) ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS signatures (
  id           TEXT PRIMARY KEY,
  owner        TEXT NOT NULL,
  name         TEXT NOT NULL DEFAULT 'Signature',
  data_png     TEXT NOT NULL,   -- base64 PNG (no data: prefix)
  width        INTEGER,
  height       INTEGER,
  svg_data     TEXT,
  created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_signatures_owner ON signatures(owner);
