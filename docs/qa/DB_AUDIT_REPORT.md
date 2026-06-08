# Olla Nest — Database & Migration Audit

**Date:** 2026-06-08 · **DB:** SQLite (WAL), shared by admin+user, Flyway-managed.

## 1. Migration integrity — PASS
- `flyway_schema_history`: **13 rows, all `success=1`** (baseline + V1–V12).
- Re-runs are idempotent (validate-on-migrate=true; checksum drift previously remediated via repair — see deployment history).
- Migration order V1→V12 applies cleanly; admin service owns migrations, user service has Flyway disabled (no double-apply).

## 2. Runtime PRAGMAs — PASS (with note)
| PRAGMA | Value | Verdict |
|---|---|---|
| `journal_mode` | `wal` | ✅ |
| `integrity_check` | `ok` | ✅ |
| `foreign_keys` (CLI conn) | `0` | ⚠️ see OBS-001 |

**OBS-001:** `foreign_keys` is a **per-connection** setting. The app enables it via Hikari `spring.datasource.hikari.connection-init-sql = PRAGMA … foreign_keys=ON …`. A raw `sqlite3` CLI connection shows `0` because it doesn't run that init SQL — expected, but it means **FK enforcement is implicit on the app pool only**. Recommend an integration test that inserts a child row with a non-existent parent through the app datasource and asserts rejection.

## 3. Schema surface — PASS
- **62 tables** present (58 feature tables + Flyway + SQLite internal).
- All tables documented in `FEATURES.md §11` exist (spot-verified via `sqlite_master`).

## 4. Tables by domain (from migrations)
- Core/RBAC: users, sessions, login_attempts, departments, groups, user_groups, teams, role_catalog, permission_catalog, access_grants, user_overrides, workspace_prefs, settings.
- AI/LLM: models, api_providers, api_models, chat_sessions, chat_messages, router_traces, feedback, cookbook_models.
- RAG/search: rag_documents, rag_chunks, web_search_log, image_generation_log, search_cache_index.
- Integrations: connector_configs, connector_documents, connector_sync_log, mcp_servers, webhooks, api_tokens, companion_tokens.
- SSO: sso_providers, oauth_state.
- Productivity/PIM: memories, skills, notes, scheduled_tasks, task_runs, comparisons, crew_members, user_templates, signatures, email_accounts, email_messages, email_drafts, calendars, calendar_events, contacts, gallery_albums, gallery_images, editor_drafts, research_tasks, youtube_transcripts.
- Security/audit: audit_events, prompt_security_log, event_log, vault_config, background_jobs.

## 5. NOT executed (recommended)
- **Constraint enforcement tests:** unique/NOT NULL/foreign-key rejection through the app datasource (Testcontainers or `@SpringBootTest` with a temp `DATA_DIR`).
- **Cascade / orphan behavior:** delete parent (user/team/calendar/email account) → verify children handling.
- **Concurrency / DB-lock:** parallel writes under WAL + busy_timeout.
- **Corruption / backup-restore:** trigger `POST /api/admin/settings/backup`, validate restore.
- **ENCRYPTION_KEY rotation impact:** confirm graceful failure for previously-encrypted secrets.

## Verdict
**PASS** for migration integrity, schema presence, WAL, and integrity-check. Conditional on adding active FK-enforcement and constraint-rejection integration tests (currently implicit).
