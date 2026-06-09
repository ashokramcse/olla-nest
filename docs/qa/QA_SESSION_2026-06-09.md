# Olla Nest — QA Re-validation Session (2026-06-09)

Full-stack re-validation against the live local environment (admin :8080, user :8081,
shared SQLite `data/olla-nest.sqlite`). This session re-ran the existing suites for fresh
evidence and added two new MAJOR findings (both fixed with regression coverage). No
existing test was deleted or weakened.

## Evidence summary

| Layer | Method | Result |
|---|---|---|
| Build | `mvn clean test` (Java 26, Maven 3.9.16) | **BUILD SUCCESS**, exit 0 |
| Backend tests | JUnit5 / Spring Boot Test, Surefire XML aggregate | **2101 tests, 0 failures, 0 errors, 0 skipped** |
| Migrations | live `flyway_schema_history` | V0 baseline + **V1–V12 all `success=1`** |
| DB integrity | `PRAGMA integrity_check` / `journal_mode` | `ok`, `wal`, 62 tables |
| DB FK audit | `PRAGMA foreign_key_check` | 2 orphan `connector_sync_log` rows — pre-existing residue (OBS-001), cleanup SQL noted |
| Security headers | `curl -D-` on `/admin-login` | CSP, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`, `Permissions-Policy` all present |
| RBAC (unauth) | `curl` 8 admin + user endpoints | All protected routes **401** (`/api/auth/me` safely 200) |
| E2E | Playwright (chromium/firefox/webkit) | **163 passed, 1 failed → fixed → 164 green** |
| Accessibility | axe-core WCAG A/AA | 1 serious violation found+fixed → **0 violations** |
| Performance | k6 write-path, ramp to 30 VUs, ~30s | **16,987 reqs, 0 failures**, p95 **3.81ms**, p99 **6.11ms**, 422 req/s, 100% checks, thresholds PASS |

## New findings (this session)

- **BUG-025 — MAJOR (API contract):** missing required `@RequestParam` returned **500** instead of **400**
  (e.g. `GET /api/memory/search` with no `?q=`). Added a `MissingServletRequestParameterException`
  handler in `GlobalExceptionHandler` + regression test. Live verify on :8081 needs a user-jar redeploy.
- **BUG-026 — MAJOR (a11y, serious):** chat transcript container `#messages.messages-area` was
  scrollable but not keyboard-focusable (WCAG 2.1.1). Added `role="log" aria-label aria-live tabindex="0"`
  in `public/app.html`. **Verified live:** a11y suite 4/4, 0 violations.

See `BUG_REPORT.md` for full detail.

## Coverage executed vs. the 24-phase plan

Provable on this machine and **executed with evidence**: build/migration validation,
backend unit+integration, live security headers/RBAC/secret-handling, DB integrity,
e2e UI + a11y, k6 write-path load.

**Not executed — require absent infrastructure** (marked BLOCKED, never PASS): multi-hour
soak; 500-VU load beyond local capacity; live third-party connector matrix (GitHub/Slack/
Salesforce/… real credentials); SMTP/IMAP send/receive; external image/STT providers;
ZAP dynamic scan; Loki/Grafana monitoring stack assertions. These need real tokens, an
Ollama model host, mail servers, and the monitoring stack running.

## Verdict

**PASS WITH MINOR ISSUES → effectively CONDITIONAL PASS pending redeploy.**
Core flows (auth, RBAC, DB integrity, security headers, chat UI, productivity CRUD) are
proven green. The two new MAJOR bugs are fixed in source with regression tests; BUG-025's
fix still needs a running-instance redeploy before the live :8081 endpoint reflects 400.
No security or data-integrity **blocker** was found. The orphan `connector_sync_log` rows
are stale residue, not active corruption.
