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

## BUG-006 — `calculate` function-call tool non-functional on JDK 15+ — **MAJOR (product)** — FIXED
- **Feature:** AI function/tool calling — built-in `calculate` tool (`FunctionCallService.executeCalculate`).
- **Severity:** Major (a documented built-in tool returns an error instead of a result for every invocation on the deployed runtime).
- **Root cause:** Used `new ScriptEngineManager().getEngineByName("JavaScript")`. The Nashorn JS engine was removed in **JDK 15**; the project targets **Java 26**, so `getEngineByName` returns `null` and the tool responded `{"result": "Script engine unavailable — try a simpler expression"}`.
- **Evidence:** `FunctionCallServiceTest.calculateSimpleExpression` — expected `"2+2"` → contains `"4"`; actual `Script engine unavailable`.
- **Fix:** Replaced the JS engine with a self-contained recursive-descent arithmetic evaluator (`+ - * / %`, parentheses, unary minus, decimals; division-by-zero guarded; no `eval`, no script engine, no I/O — also removes a code-execution surface).
- **Verification:** `FunctionCallServiceTest` → 12 tests, 0 failures.
- **Regression test:** existing `calculateSimpleExpression` now guards it.

---

## BUG-003 — Stale `db.update` verifications / unstubbed mocks — **MAJOR (test-debt)** — **FIXED**
- **Affected (≈24 tests):** `CalendarServiceTest` (insertsEvent/insertsOwner/updatesById), `EmailServiceTest.insertsRow`, `NotesServiceTest.insertsRow`, `ContactsServiceTest.updateCalledWithIdAndOwner`, `CompareServiceTest.dbInsertCalled`, `ApiTokenServiceTest.dbInsertCallsWithOwnerAndName`, `BackgroundJobServiceTest` (×2), `PersonalAssistantServiceTest` (×2), `PromptSecurityServiceTest.insertsRow`, `ProviderServiceTest.upsertWhenApproved`, `SessionEnhancementServiceTest.copiesMessages`, `SkillsServiceTest` (×2), `TerminalServiceTest.writesAuditEventOnClose`, `VaultServiceTest.insertsWhenNoRowUpdated`, `McpServerServiceTest.insertsRow`, `FunctionCallServiceTest.calculateSimpleExpression`, `ConnectorSyncSchedulerTest` (×2), `StateControllerTest.bodyContainsUser`.
- **Root cause(s):**
  1. Tests `verify(db).update(contains("INSERT INTO …"), <any>)`, but the service either inserts via a different code path or the method **aborts earlier** because a prerequisite query (e.g. `db.queryForObject("SELECT COUNT(*) …")`) is **left unstubbed** → returns `null` → NPE on unboxing → INSERT never reached.
  - Evidence (Calendar): `Argument(s) are different! Wanted: db.update(contains("INSERT INTO calendars"), <any>); Actual invocations: db.queryForObject(…)`.
- **Why not a product bug:** The services' insert paths are real and correct; the **tests** under-stub the mock or assert the wrong JdbcTemplate method. Reproducible in isolation (Calendar: 15 run / 3 fail standalone), so not order-dependent.
- **Pre-existing:** Present in the very first `mvn test` of the audit, before any change in this run.
- **Resolution (no assertions weakened):** Root cause was a **Mockito varargs matching defect** — `(Object[]) any()` silently fails to match multi-argument varargs in this Mockito version, so every `verify(db).update(sql, (Object[]) any())` reported "argument(s) are different". Replaced all 30 occurrences with `any(Object[].class)` (count-independent, compiles unambiguously, matches the varargs array). Also fixed `Map.of(null)`/`List.of(null)` row builders and a `when().thenThrow` re-stub gotcha.
- **Verification:** all affected classes now green; full `mvn test` BUILD SUCCESS.
- **Impact:** Gate restored to green.

---

## BUG-004 — Webhook test does real DNS — **MINOR (test/env)** — **FIXED**
- **Test:** `WebhookServiceTest.insertsRow`.
- **Cause:** `WebhookService.validateUrl()` calls `InetAddress.getByName(host)` (a legitimate **SSRF guard** that rejects private IPs). The test URL `hooks.example.com` does not resolve in an offline CI box → `Cannot validate webhook URL: … nodename nor servname provided`.
- **Product:** Correct & desirable (SSRF prevention). **Test** is the defect.
- **Fix:** Switched the test URL to a public **IP literal** (`93.184.216.34`), so `getByName` resolves without a DNS lookup — deterministic offline, still a non-private address that exercises the SSRF guard.

---

## BUG-005 — Memory tests broken (null-map / re-stub / varargs) — **MINOR (test)** — **FIXED**
- **Tests:** `MemoryServiceTest` (keywordMatchReturnsHit, topKLimitsResults, skipsBlankEntries, noKeywordMatchReturnsEmpty, embeddingFailureSwallowed, evictionRunsBeforeInsert).
- **Cause (all test-side; product verified correct):**
  1. Row builders used `Map.of(..., "embedding_json", null, ...)` and `List.of(..., null)` → NPE (Map.of/List.of forbid null).
  2. `embeddingFailureSwallowed` re-stubbed with `when(embeddingService.embed(...)).thenThrow(...)` while `@BeforeEach` already made `embed` throw → the inner call threw during stubbing.
  3. `evictionRunsBeforeInsert` verified the eviction `DELETE` with no varargs matcher.
- **Note:** `MemoryService.remember()` and `recall()` already catch embedding failures and fall back to keyword search — graceful degradation confirmed by reading the source.
- **Fix:** null-safe `LinkedHashMap` row helper, `Arrays.asList`, `doThrow(...).when(...)`, and `any(Object[].class)` on the DELETE verify. `MemoryServiceTest` → 21 tests, 0 failures.

---

## OBS-001 — SQLite foreign-key enforcement is per-connection — **INFO**
- `PRAGMA foreign_keys` returned `0` on a fresh CLI connection. FK enforcement relies on Hikari `connection-init-sql` (`PRAGMA foreign_keys=ON`) running on **every** pooled connection. Verified configured in `application.properties`. Recommend an integration test that asserts a FK violation is actually rejected through the app's datasource (not just configured). See `DB_AUDIT_REPORT.md`.

---

## Severity ledger
| Severity | Count | IDs | Status |
|---|---|---|---|
| Critical | 1 | BUG-001 | FIXED |
| Major | 2 | BUG-006 (product: calculate tool), BUG-003 (test-debt) | FIXED |
| Minor | 3 | BUG-002, BUG-004, BUG-005 | FIXED |
| Info | 1 | OBS-001 | Noted (FK enforcement is per-connection) |

**All identified bugs are fixed. Full suite is green: 2069 tests, 0 failures, 0 errors, 0 skipped (`mvn test` BUILD SUCCESS).**

- 2 genuine product bugs found and fixed: **BUG-001** (session-cookie NPE regression) and **BUG-006** (`calculate` tool dead on JDK 15+).
- The rest were pre-existing **test-debt** (Mockito varargs matching, `Map.of(null)`, re-stub gotchas, DNS dependency) — fixed without weakening any assertion.
- **No product security or data-integrity blocker found.**
