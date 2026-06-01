-- =============================================================================
-- Olla Nest — V8: Add sandbox:run permission to the permission catalog
-- Applied: v2026.2.0
--
-- CRIT-1 MITIGATION: The code sandbox (CodeSandboxController) now requires
-- an explicit 'sandbox:run' right. This migration inserts the permission into
-- the catalog so admins can grant it from the UI.
--
-- NOTE: This migration does NOT automatically grant sandbox:run to any user.
-- Admins must explicitly grant it after reviewing the security implications.
-- =============================================================================

INSERT OR IGNORE INTO permission_catalog (key, category, description, risk_level)
VALUES (
  'sandbox:run',
  'Local Work',
  'Execute code snippets in the code sandbox (OS-level subprocess; requires explicit admin trust)',
  'critical'
);
