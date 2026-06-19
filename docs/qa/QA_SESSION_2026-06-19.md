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

## 5. Traceability

`FEATURE_TRACEABILITY_MATRIX.md` (prior) maps `docs/FEATURES.md` §1–§9 to controllers/services/tables and remains structurally valid — the upgrade changed *no* routes or table schemas (62 tables, 13 migrations unchanged). Endpoint inventory on the current build: **278 request mappings** (common 10 / admin 79 / user 189), consistent with the matrix.

---

## 6. NOT EXECUTED this session (honest gaps — require external inputs or long wall-clock)

These remain as in the prior campaign; the upgrade does not change their status, but they have **not** been re-run on the current build:
1. **Playwright** a11y/responsive/visual E2E re-run on the current frontend (prior `UI_UX_AUDIT_REPORT.md` carries forward).
2. **k6** fresh load/concurrency on current build (prior evidence: `evidence/k6-*.json`, 0% error).
3. **Multi-hour soak** for memory-leak/heap-growth on current build.
4. **Live external providers**: real IMAP/SMTP send-poll, DALL·E/OpenAI-TTS with real keys (paths proven to degrade to 503 "not configured").
5. **Git-history secret purge** (process item, not a runtime defect).

## 7. Recommendation

- **Release-readiness (local surface): PASS** — carry forward the prior verdict; this session removes the risk that the Boot 4.1 / Spring AI 2.0 upgrade silently broke critical flows. It did not.
- Before a production cut, execute items §6.1–§6.3 against this build and obtain real provider keys for §6.4.

_Evidence: command outputs captured in this session's transcript; suite counts from `target/*/surefire-reports/*.xml`; DB facts from `data/olla-nest.sqlite` PRAGMAs._
