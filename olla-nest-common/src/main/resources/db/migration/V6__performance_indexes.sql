-- =============================================================================
-- Olla Nest — Database Schema V6 (Performance Indexes)
-- Adds missing indexes identified by DBA review of production query patterns.
-- All statements use IF NOT EXISTS so this migration is safely re-runnable.
-- =============================================================================

-- models.status: heavily queried by allowedModels()
--   WHERE status IN ('available','configured')  — full table scan without this.
CREATE INDEX IF NOT EXISTS idx_models_status ON models(status);

-- models.provider: queried by allowedModelIds() implicit-grant branches
--   WHERE provider != 'api' AND status != 'disabled'
--   WHERE provider = 'api' AND status != 'disabled'
CREATE INDEX IF NOT EXISTS idx_models_provider ON models(provider);

-- Composite index covers both provider and status predicates together —
-- lets SQLite satisfy the above queries with a single index scan.
CREATE INDEX IF NOT EXISTS idx_models_provider_status ON models(provider, status);

-- users.email: UNIQUE constraint already provides an implicit index.
-- Explicit index ensures the optimiser picks it up for joins and lookups.
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- users.active: frequent predicate in findUserByEmail() and login query
--   WHERE active = 1 AND ...
CREATE INDEX IF NOT EXISTS idx_users_active ON users(active);

-- Composite (email, active) covers the most common lookup:
--   WHERE email = ? AND active = 1
CREATE INDEX IF NOT EXISTS idx_users_email_active ON users(email, active);

-- users.role: ORDER BY role, name in list-users pagination
CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);

-- chat_sessions.updated_at: ORDER BY updated_at DESC LIMIT 1 in getActiveChat()
CREATE INDEX IF NOT EXISTS idx_chat_sessions_updated_at ON chat_sessions(updated_at);

-- Composite (user_id, is_active, updated_at) covers the active-session lookup:
--   WHERE user_id = ? AND is_active = 1 ORDER BY updated_at DESC LIMIT 1
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_active_updated
    ON chat_sessions(user_id, is_active, updated_at);

-- audit_events.actor: admin reports queries by actor
CREATE INDEX IF NOT EXISTS idx_audit_events_actor ON audit_events(actor);

-- audit_events.action: filter by action type in reports
CREATE INDEX IF NOT EXISTS idx_audit_events_action ON audit_events(action);

-- connector_sync_log.started_at: cleanup scheduler deletes rows older than 30 days
CREATE INDEX IF NOT EXISTS idx_connector_sync_log_started_at ON connector_sync_log(started_at);

-- access_grants.model_id: reverse lookup — which subjects have access to a model?
CREATE INDEX IF NOT EXISTS idx_access_grants_model ON access_grants(model_id);

-- user_overrides.expires_at: expiry filtering in effectiveAccess() and allowedModelIds()
CREATE INDEX IF NOT EXISTS idx_user_overrides_expires ON user_overrides(user_id, expires_at);
