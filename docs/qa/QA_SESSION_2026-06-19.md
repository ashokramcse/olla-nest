# Olla Nest — QA Session 2026-06-19 (Regression-Confirmation Baseline)

**Tester:** Principal QA / SDET / Security audit pass
**Trigger:** Major platform upgrade since the last campaign — **Spring Boot 3.5 → 4.1.0**, **Spring Framework 6 → 7**, **Spring AI 1.0 → 2.0.0**, configuration-metadata + comment-skeleton passes. This session **re-establishes the evidence baseline on the current build** and live-re-verifies the Phase 24 non-negotiables, rather than re-running the entire prior 24-phase campaign from scratch.

> **Honesty rules upheld:** every PASS below is backed by captured command output. A `000`/empty HTTP response is treated as an INVALID probe, never a PASS (this session caught and discarded two vacuous "passes" produced while the servers were briefly down). Nothing is marked PASS without non-vacuous proof.

---

## 0. Verdict

**PASS (regression-confirmed) on the locally-testable surface.** The Boot 4.1 / Spring AI 2.0 upgrade did **not** regress any of the proven-green areas from the prior campaign. The full automated suite is green and every Phase 24 non-negotiable was re-verified live with real HTTP/DB evidence.

Carry-forward verdict basis: the prior campaign (`TEST_EXECUTION_SUMMARY.md`, 2026-06-08/10) reached *"PASS for release on the locally-testable surface"* with all critical bugs fixed + regression-tested. This session confirms those guarantees still hold after the framework upgrade.

---

## 1. Build + full suite — Phase 23.1–23.4 (EXECUTED, current build)

```
mvn clean test  → BUILD SUCCESS (exit 0)
Surefire totals: 88 test classes · 2,190 test methods · 0 failures · 0 errors · 0 skipped
(console "Tests run" incl. parameterized invocations: 2,234)
```
Per module: olla-nest-common 2,045 · olla-nest-admin 145 · olla-nest-user 44 — all 0/0.

## 2. Database + migrations — Phase 15 (EXECUTED, live data DB)

```
flyway_schema_history : 13 rows, all success=1
PRAGMA integrity_check : ok
PRAGMA foreign_key_check: 0 violation rows
tables                 : 62  ·  indexes: 148  ·  journal_mode: wal
```
Plus `SchemaIntegrationTest` (admin) validates Flyway from an **empty** in-memory DB on every build (V1–V6 table/index existence, FK-on, history) — green.

## 3. Phase 24 non-negotiable acceptance criteria — (EXECUTED LIVE, both servers)

Servers: admin `:8080`, user `:8081` (run via preview from rebuilt jars). Two disposable local users used for cross-user checks.

| # | Acceptance criterion | Evidence | Status |
|---|---|---|---|
| AC1 | Admin + user log in independently | both `/api/auth/login` → 200 with session cookie | ✅ |
| AC1 | Cookies **service-separated** | admin cookie `olla_nest_session` vs user `olla_nest_user_session` (distinct names) | ✅ |
| AC1 | Cookie hardening | `HttpOnly` + `SameSite=Lax` present on Set-Cookie | ✅ |
| AC2/AC4 | RBAC: non-admin blocked from admin endpoint | user cookie → `GET /api/admin/users` = **401** | ✅ |
| — | Unauthenticated admin endpoint | no cookie → `GET /api/admin/users` = **401** | ✅ |
| AC3 | Session invalidation / replay | `/me`=authenticated → logout → same cookie `/me`=unauthenticated | ✅ |
| AC5 | Cross-user IDOR (notes) | user1 creates `note-mqkheb3s-6db55a`; user2 `GET` → **404 "Note not found"** (non-vacuous: real id) | ✅ |
| AC10 | Secret redaction in API | `/api/state` exposes no raw `sk-`/`sk_ant` key | ✅ |
| AC | Encryption at rest | `api_providers` API-key column holds ciphertext, **0** plaintext `sk-` rows | ✅ |
| AC | Audit trail (no secret) | login writes `audit_events.action='auth.login'` (1418→1419); detail contains **no** password/hash | ✅ |
| AC | Brute-force tracking | failed logins accumulate in `login_attempts` | ✅ |
| AC | Security headers | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy`, `Referrer-Policy` all present | ✅ |

## 4. Boot-4-upgrade-specific regressions checked (EXECUTED)

These are areas the framework upgrade was most likely to break; all confirmed intact:
- **Flyway auto-config** moved to `spring-boot-flyway` module → migrations still run (covered by AC2 + admin integration tests).
- **MockMvc test auto-config** moved to `spring-boot-webmvc-test` → 145 admin + 44 user integration tests green.
- **`UserDetailsServiceAutoConfiguration`** relocated → both apps boot (production `autoconfigure.exclude` fixed; AC1 proves live boot).
- **Spring 7 `NoResourceFoundException`** ctor change → `GlobalExceptionHandlerTest` green.

---

## 4b. Live black-box harness — Phases 3–16 (EXECUTED, 2026-06-19)

Harness `evidence/live-harness-2026-06-19.sh` (log `…-2026-06-19.log`) ran 71 assertions against the live servers with self-verified logins (a prior pass against briefly-down servers returned all `000` and its "passes" were **discarded as vacuous**, not counted). Result on the valid run: **67 PASS · 3 FAIL · 1 SKIP**.

Coverage (all live HTTP + DB-asserted): P3 auth/session (valid/wrong-pw/enumeration-resistance/missing-field 400/malformed-JSON 400/oversized-pw 400/forged-token/logout-CSRF 403), P4 RBAC user CRUD + IDOR + 404s, P5 **14 admin endpoints each admin=200 / user=401**, P6/9 notes/tasks/calendar/contacts/memory/account CRUD + **notes IDOR→404** + calendar BUG-021/027 regressions, P7 chat empty/oversized 400, P8 threads, P10 **sandbox gated on `sandbox:run`→403 (CRIT-1)**, P11 documents + multipart extract-text, P12 vault/**webhook SSRF metadata-IP→400 (BUG-020)**/**token secret returned once & not in list**/jobs, P13 connectors (20 types, create, missing-name→400 BUG-036), P14 MCP + non-admin block, P16 **SQLi handled, path-traversal→403 (HIGH-4), no stack-trace leak**.

**The 3 FAILs were investigated and classified — none are product defects:**
| Harness FAIL | Verdict | Root cause |
|---|---|---|
| `/api/admin?days=7` → 404 | **harness bug** | wrong path; real route is `/api/admin/reports` (admin=200, user=401 confirmed) |
| `/api/jobs/active` user → 403 | **correct RBAC** | endpoint calls `requireAdminUser()` — admin-only by design; harness expected 200 |
| tasks `schedule:"INVALID"` → 201 | **MINOR (loose validation)** | `schedule` is free-form; unknown value stored, yields no `next_run` (never fires) instead of a 400. Hardening opportunity, not a defect. |

SKIP (now **RESOLVED**, see §4d): note title stores raw `<script>` at rest → the Playwright render check proved the payload is **output-encoded and does not execute** in `/app`. Not a vulnerability.

## 4d. Frontend E2E / responsive / a11y — Phase 18 (EXECUTED, 2026-06-19, Playwright+Chromium)

Real headless-Chromium run (`evidence/TEST_EVIDENCE/*.png`): **11 PASS / 0 FAIL**.
- User `/login` and admin `/admin-login` load HTTP 200; correct title `Olla Nest — Sign in`; email+password fields present.
- **0 uncaught JS pageerrors, 0 console errors** (excluding expected pre-auth 401s) on login.
- **Responsive: no horizontal overflow at 320 / 768 / 1440 px** (scrollWidth == viewport at each).
- Login flow (fill + Enter) redirects to `/app`.
- **Stored-XSS render (Finding D):** seeded a note with `<script>` + `<img onerror>` via API, loaded `/app` — payload **did not execute** (no dialog, `window.__XSS__` unset). Output encoding confirmed.

Still NOT re-run this session: full a11y axe-core sweep, dark-mode visual diff, and every authenticated panel's empty/loading/error states (prior `UI_UX_AUDIT_REPORT.md` carries forward).

## 4c. Load + backup + integrity — Phases 17 & 19 (EXECUTED, 2026-06-19)

- **k6 load** (30 VUs, 20s, `evidence/k6-load-2026-06-19.json`): **63,169 requests, 0.00% failed**, p95 **37.3 ms** (threshold 800 ms), 100% of 63,168 checks passed, ~3,119 req/s.
- **Post-load DB integrity**: `integrity_check=ok`, **0 FK violations** — concurrency did not corrupt data (Phase 24 stress criterion ✅).
- **Backup** (Phase 19): `POST /api/admin/settings/backup` → 200, wrote `data/backups/olla-nest-2026-06-19T…Z.sqlite` (1.59 MB); backup `integrity_check=ok`, 62 tables, 13 migrations — a valid bootable copy.

## 5. Traceability

`FEATURE_TRACEABILITY_MATRIX.md` (prior) maps `docs/FEATURES.md` §1–§9 to controllers/services/tables and remains structurally valid — the upgrade changed *no* routes or table schemas (62 tables, 13 migrations unchanged). Endpoint inventory on the current build: **229 distinct routes / 278 request mappings** (common 10 / admin 79 / user 189), consistent with the matrix.

---

## 6. NOT EXECUTED this session (honest gaps — require external inputs or long wall-clock)

Narrowed sharply after this session — k6 load **and** Playwright E2E/responsive/XSS-render now executed. Genuinely remaining:
1. **Full a11y axe-core sweep + dark-mode visual diff + every authenticated panel's empty/loading/error state** (login flow + responsive + XSS-render done in §4d; deep a11y NOT EXECUTED — prior `UI_UX_AUDIT_REPORT.md` carries forward).
2. **Multi-hour soak** for memory-leak/heap-growth (20s k6 done; long soak NOT EXECUTED — needs wall-clock).
3. **Live external providers**: real IMAP/SMTP send-poll, DALL·E/OpenAI-TTS with real keys (paths proven to degrade to 503 "not configured"). **NOT EXECUTED** (need keys).
4. **Git-history secret purge** (process item, not a runtime defect).

## 7. Recommendation

- **Release-readiness (local surface): PASS** — carry forward the prior verdict; this session removes the risk that the Boot 4.1 / Spring AI 2.0 upgrade silently broke critical flows. It did not.
- Before a production cut, execute items §6.1–§6.3 against this build and obtain real provider keys for §6.4.

_Evidence: command outputs captured in this session's transcript; suite counts from `target/*/surefire-reports/*.xml`; DB facts from `data/olla-nest.sqlite` PRAGMAs._
