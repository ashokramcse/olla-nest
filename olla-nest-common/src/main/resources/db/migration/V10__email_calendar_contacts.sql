-- =============================================================================
-- Olla Nest — V10: Email, Calendar, Contacts
-- Covers categories F, G, H from the feature expansion
-- =============================================================================

-- ── Email accounts ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS email_accounts (
  id               TEXT PRIMARY KEY,
  owner            TEXT NOT NULL,
  name             TEXT NOT NULL DEFAULT 'Email',
  imap_host        TEXT NOT NULL,
  imap_port        INTEGER NOT NULL DEFAULT 993,
  imap_security    TEXT NOT NULL DEFAULT 'SSL',   -- SSL|TLS|NONE
  smtp_host        TEXT NOT NULL,
  smtp_port        INTEGER NOT NULL DEFAULT 587,
  smtp_security    TEXT NOT NULL DEFAULT 'STARTTLS',
  username         TEXT NOT NULL,
  password_enc     TEXT NOT NULL,                  -- encrypted
  display_name     TEXT NOT NULL DEFAULT '',
  signature        TEXT NOT NULL DEFAULT '',
  poll_interval_s  INTEGER NOT NULL DEFAULT 300,   -- seconds between IMAP polls
  enabled          INTEGER NOT NULL DEFAULT 1,
  last_polled_at   TEXT,
  team_id          TEXT,                           -- null = personal; set = team shared inbox
  created_at       TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at       TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_email_accounts_owner ON email_accounts(owner, enabled);
CREATE INDEX IF NOT EXISTS idx_email_accounts_team ON email_accounts(team_id) WHERE team_id IS NOT NULL;

-- ── Email messages (local cache / AI-triaged) ─────────────────────────────────
CREATE TABLE IF NOT EXISTS email_messages (
  id               TEXT PRIMARY KEY,              -- local UUID
  account_id       TEXT NOT NULL REFERENCES email_accounts(id) ON DELETE CASCADE,
  message_id       TEXT NOT NULL,                 -- RFC2822 Message-ID header
  in_reply_to      TEXT,
  references_ids   TEXT,                          -- space-sep list
  thread_id        TEXT,                          -- computed thread root message_id
  folder           TEXT NOT NULL DEFAULT 'INBOX',
  subject          TEXT NOT NULL DEFAULT '',
  from_addr        TEXT NOT NULL,
  to_addr          TEXT NOT NULL DEFAULT '',
  cc_addr          TEXT,
  bcc_addr         TEXT,
  date_sent        TEXT,
  body_text        TEXT,
  body_html        TEXT,
  is_read          INTEGER NOT NULL DEFAULT 0,
  is_starred       INTEGER NOT NULL DEFAULT 0,
  is_spam          INTEGER NOT NULL DEFAULT 0,
  urgency_score    INTEGER,                        -- 1-5 (AI)
  ai_summary       TEXT,
  ai_tags_json     TEXT NOT NULL DEFAULT '[]',
  has_attachments  INTEGER NOT NULL DEFAULT 0,
  attachments_json TEXT NOT NULL DEFAULT '[]',    -- [{name,size,type,path}]
  uid              INTEGER,                        -- IMAP UID
  created_at       TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_email_messages_account ON email_messages(account_id, folder, is_read);
CREATE INDEX IF NOT EXISTS idx_email_messages_thread ON email_messages(thread_id);
CREATE INDEX IF NOT EXISTS idx_email_messages_date ON email_messages(date_sent);
CREATE UNIQUE INDEX IF NOT EXISTS idx_email_messages_uid ON email_messages(account_id, uid) WHERE uid IS NOT NULL;

-- ── Email drafts ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS email_drafts (
  id           TEXT PRIMARY KEY,
  account_id   TEXT NOT NULL REFERENCES email_accounts(id) ON DELETE CASCADE,
  owner        TEXT NOT NULL,
  to_addr      TEXT NOT NULL DEFAULT '',
  cc_addr      TEXT,
  subject      TEXT NOT NULL DEFAULT '',
  body         TEXT NOT NULL DEFAULT '',
  reply_to_id  TEXT,
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_email_drafts_owner ON email_drafts(owner);

-- ── Calendars ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS calendars (
  id           TEXT PRIMARY KEY,
  owner        TEXT NOT NULL,
  name         TEXT NOT NULL DEFAULT 'My Calendar',
  color        TEXT NOT NULL DEFAULT '#F5C800',
  is_default   INTEGER NOT NULL DEFAULT 0,
  caldav_url   TEXT,
  caldav_ctag  TEXT,                               -- sync token
  team_id      TEXT,
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_calendars_owner ON calendars(owner);

-- ── Calendar events ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS calendar_events (
  id           TEXT PRIMARY KEY,
  calendar_id  TEXT NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
  uid          TEXT NOT NULL,                      -- iCal UID (for CalDAV sync)
  title        TEXT NOT NULL DEFAULT 'Event',
  description  TEXT,
  location     TEXT,
  start_at     TEXT NOT NULL,
  end_at       TEXT NOT NULL,
  all_day      INTEGER NOT NULL DEFAULT 0,
  rrule        TEXT,                               -- recurrence rule
  exdate_json  TEXT NOT NULL DEFAULT '[]',         -- exception dates
  status       TEXT NOT NULL DEFAULT 'confirmed',  -- confirmed|tentative|cancelled
  etag         TEXT,                               -- CalDAV ETag for sync
  caldav_href  TEXT,
  created_at   TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_calendar_events_cal ON calendar_events(calendar_id, start_at);
CREATE INDEX IF NOT EXISTS idx_calendar_events_uid ON calendar_events(uid);
CREATE INDEX IF NOT EXISTS idx_calendar_events_range ON calendar_events(start_at, end_at);

-- ── Contacts ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS contacts (
  id             TEXT PRIMARY KEY,
  owner          TEXT NOT NULL,
  display_name   TEXT NOT NULL DEFAULT '',
  first_name     TEXT NOT NULL DEFAULT '',
  last_name      TEXT NOT NULL DEFAULT '',
  email_json     TEXT NOT NULL DEFAULT '[]',       -- [{type,value}]
  phone_json     TEXT NOT NULL DEFAULT '[]',
  address_json   TEXT NOT NULL DEFAULT '[]',
  organization   TEXT,
  title          TEXT,
  notes          TEXT,
  carddav_url    TEXT,
  etag           TEXT,
  source         TEXT NOT NULL DEFAULT 'local',    -- local|carddav
  team_id        TEXT,
  created_at     TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at     TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_contacts_owner ON contacts(owner);
CREATE INDEX IF NOT EXISTS idx_contacts_name ON contacts(owner, display_name);
