-- =============================================================================
-- Olla Nest — Database Schema  V4 (SSO / Single Sign-On)
-- Adds sso_providers and oauth_state tables.
-- Supports Google OAuth 2.0, Generic OIDC (Okta, Azure AD, Auth0),
-- and SAML 2.0.  Existing password-based login is unaffected.
-- =============================================================================

-- Configuration for each SSO provider (admin-configured)
CREATE TABLE IF NOT EXISTS sso_providers (
  id                TEXT PRIMARY KEY,           -- sso-google-01, sso-okta-01, etc.
  type              TEXT NOT NULL,              -- google|oidc|saml
  name              TEXT NOT NULL,              -- "Sign in with Google", "Sign in with Okta"
  enabled           INTEGER NOT NULL DEFAULT 1,
  client_id         TEXT,                       -- OAuth client ID (Google / OIDC)
  client_secret_enc TEXT,                       -- AES-256-GCM encrypted client secret
  -- JSON bag for type-specific config:
  --   google : { "hd": "company.com" }          (restrict to domain)
  --   oidc   : { "issuerUrl": "https://...", "scopes": "openid email profile" }
  --   saml   : { "entityId": "...", "metadataUrl": "...", "acsUrl": "..." }
  config_json       TEXT,
  created_at        TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at        TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Short-lived OAuth CSRF state tokens (10-minute TTL, cleaned by scheduler)
CREATE TABLE IF NOT EXISTS oauth_state (
  state        TEXT PRIMARY KEY,   -- 256-bit random hex nonce
  provider_id  TEXT NOT NULL REFERENCES sso_providers(id) ON DELETE CASCADE,
  redirect_uri TEXT,               -- post-login redirect (optional)
  created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_oauth_state_created ON oauth_state(created_at);
