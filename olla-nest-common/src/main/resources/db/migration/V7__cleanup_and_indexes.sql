-- V7: cleanup indexes, trigger for workspace_prefs orphan cleanup, login_attempts index
-- Applied: v2026.1.10

-- L-18 Fix: Add index on login_attempts(reset_at) for efficient cleanup queries.
-- SQLite doesn't support scheduled events; the application performs probabilistic
-- cleanup on each successful login (see AuthController). This index makes those
-- DELETE WHERE reset_at < ? queries fast even at scale.
CREATE INDEX IF NOT EXISTS idx_login_attempts_reset_at ON login_attempts(reset_at);

-- L-10 Fix: Automatically clean up workspace_prefs rows when a user is deleted.
-- SQLite cannot add FK constraints to existing tables via ALTER TABLE, so we use
-- an AFTER DELETE trigger to enforce referential integrity.
CREATE TRIGGER IF NOT EXISTS trg_delete_workspace_prefs
AFTER DELETE ON users
FOR EACH ROW
BEGIN
    DELETE FROM workspace_prefs WHERE user_id = OLD.id;
END;

-- L-17 Fix: Composite index on audit_events(actor, created_at) for efficient
-- per-actor audit history queries (used in admin audit log views).
CREATE INDEX IF NOT EXISTS idx_audit_events_actor_date ON audit_events(actor, created_at);
