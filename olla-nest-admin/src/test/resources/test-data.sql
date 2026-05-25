-- Test seed data for integration tests.
-- Passwords use BCrypt with cost factor 4 (fast for tests).
-- Plain-text values are indicated in comments — never use these in production.

-- Admin user: plain-text password = "junit-test-password-only"
INSERT OR IGNORE INTO users (id, name, email, role, rights, active, ai_access_tier,
                   daily_token_limit, monthly_token_limit, gpu_quota_minutes,
                   vram_limit_mb, concurrent_model_limit, api_rate_limit_per_minute,
                   max_context_size, auth_provider, avatar_initials, password_hash)
VALUES (
    'u-test-admin-001',
    'Integration Test Admin',
    'junit-integration-test-only@example.com',
    'admin',
    '["admin:full","models:manage","workspace:build"]',
    1,
    'premium',
    500000, 10000000, 600, 16384, 5, 120, 32768,
    'local',
    'IA',
    '$2a$04$5pXQJK8Gkj7.fBNL2TsN9OE0xQaJm1z.fLhMdJBs3w4hHb.vB1Fq.'
);

-- Regular user: plain-text password = "junit-test-password-only"
INSERT OR IGNORE INTO users (id, name, email, role, rights, active, ai_access_tier,
                   daily_token_limit, monthly_token_limit, gpu_quota_minutes,
                   vram_limit_mb, concurrent_model_limit, api_rate_limit_per_minute,
                   max_context_size, auth_provider, avatar_initials, password_hash, department_id)
VALUES (
    'u-test-user-001',
    'Integration Test User',
    'test-user-seed-only@example.com',
    'user',
    '["chat:use","models:local:use"]',
    1,
    'standard',
    50000, 1000000, 120, 8192, 1, 30, 8192,
    'local',
    'IU',
    '$2a$04$5pXQJK8Gkj7.fBNL2TsN9OE0xQaJm1z.fLhMdJBs3w4hHb.vB1Fq.',
    'dept-product'
);

-- Inactive user (should be denied login)
INSERT OR IGNORE INTO users (id, name, email, role, active, password_hash)
VALUES (
    'u-test-inactive-001',
    'Inactive Test User',
    'inactive-test-seed-only@example.com',
    'user',
    0,
    '$2a$04$5pXQJK8Gkj7.fBNL2TsN9OE0xQaJm1z.fLhMdJBs3w4hHb.vB1Fq.'
);

-- Expired access user
INSERT OR IGNORE INTO users (id, name, email, role, active, access_expires_at, password_hash)
VALUES (
    'u-test-expired-001',
    'Expired Test User',
    'expired-test-seed-only@example.com',
    'user',
    1,
    '2020-01-01 00:00:00',
    '$2a$04$5pXQJK8Gkj7.fBNL2TsN9OE0xQaJm1z.fLhMdJBs3w4hHb.vB1Fq.'
);
