-- H2 compatibility: SQLite's datetime('now') is not in H2; create an alias.
CREATE ALIAS IF NOT EXISTS datetime AS $$
String datetime(String... args) {
    if (args == null || args.length == 0 || "now".equals(args[0])) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
               .format(new java.util.Date());
    }
    return args.length > 0 ? args[0] : "";
}
$$;

-- Minimal H2 schema for integration tests.
-- Only the tables touched by AuthController / SessionAuthFilter / AdminController.

CREATE TABLE IF NOT EXISTS users (
    id                     VARCHAR(64)  PRIMARY KEY,
    name                   VARCHAR(255) NOT NULL,
    email                  VARCHAR(255) NOT NULL UNIQUE,
    role                   VARCHAR(64)  NOT NULL DEFAULT 'user',
    rights                 TEXT,
    department_id          VARCHAR(64),
    active                 TINYINT      NOT NULL DEFAULT 1,
    employee_id            VARCHAR(64),
    designation            VARCHAR(255),
    team                   VARCHAR(255),
    branch                 VARCHAR(255),
    manager                VARCHAR(255),
    organization           VARCHAR(255),
    ai_access_tier         VARCHAR(64)  DEFAULT 'standard',
    daily_token_limit      BIGINT       DEFAULT 50000,
    monthly_token_limit    BIGINT       DEFAULT 1000000,
    gpu_quota_minutes      BIGINT       DEFAULT 120,
    vram_limit_mb          BIGINT       DEFAULT 8192,
    concurrent_model_limit BIGINT       DEFAULT 1,
    api_rate_limit_per_minute BIGINT    DEFAULT 30,
    max_context_size       BIGINT       DEFAULT 8192,
    mfa_enabled            TINYINT      DEFAULT 0,
    security_risk_score    BIGINT       DEFAULT 10,
    access_status          VARCHAR(64)  DEFAULT 'active',
    access_expires_at      VARCHAR(64),
    last_active_at         VARCHAR(64),
    auth_provider          VARCHAR(64)  DEFAULT 'local',
    phone                  VARCHAR(64),
    avatar_initials        VARCHAR(8),
    password_hash          VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS sessions (
    token      VARCHAR(64)  PRIMARY KEY,
    user_id    VARCHAR(64)  NOT NULL,
    expires_at VARCHAR(64)  NOT NULL
);

CREATE TABLE IF NOT EXISTS login_attempts (
    ip       VARCHAR(64) PRIMARY KEY,
    count    BIGINT      NOT NULL DEFAULT 0,
    reset_at BIGINT      NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id         VARCHAR(64)  PRIMARY KEY,
    actor_name VARCHAR(255),
    event_type VARCHAR(255),
    message    TEXT,
    context    TEXT,
    created_at VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS models (
    id       VARCHAR(128) PRIMARY KEY,
    status   VARCHAR(64)  DEFAULT 'available',
    provider VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS access_grants (
    id           VARCHAR(64) PRIMARY KEY,
    subject_type VARCHAR(64),
    subject_id   VARCHAR(64),
    model_id     VARCHAR(128),
    can_use      TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS user_groups (
    user_id  VARCHAR(64),
    group_id VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS user_overrides (
    id             VARCHAR(64) PRIMARY KEY,
    user_id        VARCHAR(64),
    permission_key VARCHAR(255),
    model_id       VARCHAR(128),
    effect         VARCHAR(16),
    reason         TEXT,
    expires_at     VARCHAR(64),
    created_at     VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS role_catalog (
    id          VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(255),
    description TEXT,
    permissions TEXT,
    system_role TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS permission_catalog (
    key         VARCHAR(255) PRIMARY KEY,
    category    VARCHAR(128),
    description TEXT,
    risk_level  VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS threads (
    id         VARCHAR(64) PRIMARY KEY,
    user_id    VARCHAR(64),
    title      VARCHAR(512),
    created_at VARCHAR(64),
    updated_at VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS messages (
    id         VARCHAR(64) PRIMARY KEY,
    thread_id  VARCHAR(64),
    role       VARCHAR(32),
    content    TEXT,
    created_at VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS settings (
    key   VARCHAR(255) PRIMARY KEY,
    value TEXT
);

CREATE TABLE IF NOT EXISTS departments (
    id   VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255)
);
