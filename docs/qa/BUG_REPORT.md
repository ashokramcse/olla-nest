# Olla Nest — Bug Report

**Date:** 2026-06-08 · Bugs are evidence-backed; severity per the project rules.

---

## BUG-001 — Session cookie name NPE (regression) — **CRITICAL** — FIXED
- **Feature:** Authentication / session resolution (`AuthService.getToken`, `setSession`, `clearSession`).
- **Severity:** Critical (breaks session handling in any non-Spring construction path; broke the SOC2 security test suite).
- **Root cause:** `cookieName` was refactored from a `static final` constant to an `@Value("${app.session-cookie-name:olla_nest_session}")` field with **no field default**. Spring injects it at runtime, but any direct instantiation (all `AuthService` unit tests, and any non-managed use) leaves `cookieName == null` → `NullPointerException` at `cookieName.equals(c.getName())`.
- **Evidence:** `mvn test` → 44 errors of the form
  `NullPointer Cannot invoke "String.equals(Object)" because "<local3>.cookieName" is null`
  across `AuthServiceTest` (16), `Soc2AuditTest` (~15), `SqlSafetyTest`, etc.
- **Fix:** Initialize the field with the default literal: `private String cookieName = "olla_nest_session";` (Spring `@Value` still overrides at runtime).
- **Verification:** `AuthServiceTest + Soc2AuditTest + SqlSafetyTest = 187 tests, 0 failures`. Live login/`/me`/logout/cookie-isolation all green (see SECURITY report).
- **Regression test:** Existing `AuthServiceTest`/`Soc2AuditTest` now serve as the regression guard (no new test needed; they exercise the null-default path by construction).

---

## BUG-002 — `Map.of(null)` in MCP test — **MINOR (test)** — FIXED
- **Feature:** MCP server service unit tests.
- **Root cause:** `McpServerServiceTest.serverRow()` built a row with `Map.of(..., "url", null, ...)`; `Map.of` forbids null values → NPE → cascading `UnfinishedStubbing`.
- **Fix:** Switched to `new LinkedHashMap<>()` with `put` (same pattern previously applied to Email/Notes/Skills/PersonalAssistant row builders).
- **Status:** Fixed (one unrelated MCP test remains, see BUG-003 class).

---

## BUG-003 — Stale `db.update` verifications / unstubbed mocks — **MAJOR (test-debt)** — OPEN
- **Affected (≈24 tests):** `CalendarServiceTest` (insertsEvent/insertsOwner/updatesById), `EmailServiceTest.insertsRow`, `NotesServiceTest.insertsRow`, `ContactsServiceTest.updateCalledWithIdAndOwner`, `CompareServiceTest.dbInsertCalled`, `ApiTokenServiceTest.dbInsertCallsWithOwnerAndName`, `BackgroundJobServiceTest` (×2), `PersonalAssistantServiceTest` (×2), `PromptSecurityServiceTest.insertsRow`, `ProviderServiceTest.upsertWhenApproved`, `SessionEnhancementServiceTest.copiesMessages`, `SkillsServiceTest` (×2), `TerminalServiceTest.writesAuditEventOnClose`, `VaultServiceTest.insertsWhenNoRowUpdated`, `McpServerServiceTest.insertsRow`, `FunctionCallServiceTest.calculateSimpleExpression`, `ConnectorSyncSchedulerTest` (×2), `StateControllerTest.bodyContainsUser`.
- **Root cause(s):**
  1. Tests `verify(db).update(contains("INSERT INTO …"), <any>)`, but the service either inserts via a different code path or the method **aborts earlier** because a prerequisite query (e.g. `db.queryForObject("SELECT COUNT(*) …")`) is **left unstubbed** → returns `null` → NPE on unboxing → INSERT never reached.
  - Evidence (Calendar): `Argument(s) are different! Wanted: db.update(contains("INSERT INTO calendars"), <any>); Actual invocations: db.queryForObject(…)`.
- **Why not a product bug:** The services' insert paths are real and correct; the **tests** under-stub the mock or assert the wrong JdbcTemplate method. Reproducible in isolation (Calendar: 15 run / 3 fail standalone), so not order-dependent.
- **Pre-existing:** Present in the very first `mvn test` of the audit, before any change in this run.
- **Fix recommendation (do NOT weaken assertions):** For each test, stub the prerequisite query (e.g. `when(db.queryForObject(contains("SELECT COUNT"), eq(Integer.class), any())).thenReturn(0)`) and/or correct the verified method to match the implementation. Add the missing-stub as the regression guard.
- **Impact:** Blocks a green `mvn test` gate; masks true coverage signal.

---

## BUG-004 — Webhook test does real DNS — **MINOR (test/env)** — OPEN
- **Test:** `WebhookServiceTest.insertsRow`.
- **Cause:** `WebhookService.validateUrl()` calls `InetAddress.getByName(host)` (a legitimate **SSRF guard** that rejects private IPs). The test URL `hooks.example.com` does not resolve in an offline CI box → `Cannot validate webhook URL: … nodename nor servname provided`.
- **Product:** Correct & desirable (SSRF prevention). **Test** is the defect.
- **Fix:** Use a resolvable public host, or inject/mocked resolver, or assert the validation rejects private IPs explicitly.

---

## BUG-005 — Memory tests require embedding runtime — **MINOR (test/env)** — OPEN
- **Tests:** `MemoryServiceTest` (keywordMatchReturnsHit, topKLimitsResults, skipsBlankEntries, noKeywordMatchReturnsEmpty, embeddingFailureSwallowed, insertsRowsForEachText, evictionRunsBeforeInsert).
- **Cause:** Depend on a live embedding/Ollama runtime ("Runtime offline") and/or unstubbed mocks (NPE).
- **Fix:** Mock `EmbeddingService` deterministically; remove runtime dependency.

---

## OBS-001 — SQLite foreign-key enforcement is per-connection — **INFO**
- `PRAGMA foreign_keys` returned `0` on a fresh CLI connection. FK enforcement relies on Hikari `connection-init-sql` (`PRAGMA foreign_keys=ON`) running on **every** pooled connection. Verified configured in `application.properties`. Recommend an integration test that asserts a FK violation is actually rejected through the app's datasource (not just configured). See `DB_AUDIT_REPORT.md`.

---

## Severity ledger
| Severity | Count | IDs |
|---|---|---|
| Critical | 1 | BUG-001 (fixed) |
| Major | 1 | BUG-003 (open, test-debt) |
| Minor | 3 | BUG-002 (fixed), BUG-004, BUG-005 |
| Info | 1 | OBS-001 |

**No product security or data-integrity blocker found in the tested surface.** The single Critical was a test-breaking regression in cookie handling, now fixed and verified.
