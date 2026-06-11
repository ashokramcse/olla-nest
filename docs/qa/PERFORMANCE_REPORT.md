# Olla Nest — Performance / Load Report

**Date:** 2026-06-08 · **Method:** Parallel `curl` harness (50 concurrent workers) against the running admin service (8080). k6 is **not installed** in this environment; this is a lightweight but real concurrency measurement. Full k6/Gatling soak (Phase 17 scenarios 4–10, 2–6h soak) remains **NOT EXECUTED**.

## Results (50 concurrent workers)

| Endpoint | Requests | Error rate | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| `GET /api/auth/me` (authed) | 200 | **0.0%** | 1ms | 2ms | 3ms | 3ms |
| `GET /api/admin/users` (authed) | 300 | **0.0%** | 2ms | 4ms | 6ms | 8ms |
| `GET /admin-login` | 200 | (302 redirect)¹ | 1ms | 2ms | 4ms | 4ms |

¹ The authenticated cookie makes `/admin-login` 302-redirect to `/admin`, so the harness's `==200` check reports "non-200"; latency is unaffected and healthy. Not a failure.

## Integrity under load
- `PRAGMA integrity_check` after the run: **ok**.
- **DB lock errors** (`SQLITE_BUSY` / "database is locked") in logs: **0** — WAL + `busy_timeout` held up under 50-way concurrency.

## Assessment
Read-path latency is excellent (single-digit ms p99) and error-free under 50 concurrent clients; no DB corruption or lock contention. This validates the basic concurrency-safety acceptance criterion ("stress tests do not corrupt DB").

## 2026-06-09 — 15-min soak (`--stage 30s:20 --stage 14m:20 --stage 30s:0`)
Sustained **20 VUs for 15 minutes** against the live user service.

| Metric | Result |
|---|---|
| Total requests | **499,843** |
| Error rate | **0.00%** (0 failures) |
| Checks | **0 failed** / 499,843 |
| Throughput | **555 req/s** (185 CRUD iters/s, 166,614 iterations) |
| Latency p50 / p90 / p95 | **1.08ms / 2.71ms / 3.47ms** (max 217ms) |
| Data transferred | 1.4 GB received / 136 MB sent |
| **User-JVM RSS** | **444 MB → 455 MB** (flat across 15 min) |

**Leak/drift verdict:** RSS flat (GC reclaimed transient growth that peaked ~543MB mid-run), latency stable, zero errors — **no memory leak, no latency drift, no degradation under sustained load.**

## 2026-06-09 — 100-VU load run (`perf/k6-write-path.js`, `--stage 10s:50 --stage 30s:100 --stage 10s:0`)
Ramp 50 → **100 concurrent VUs** for 50s; each iteration = notes create → list → delete against the live user service (Java 26, SQLite/WAL).

| Metric | Result |
|---|---|
| Total requests | **86,662** |
| Error rate (`http_req_failed`) | **0.00%** (0 / 86,662) |
| Checks succeeded | **100%** (login/create/list/delete) |
| Throughput | **1,723 req/s** (574 full CRUD iters/s) |
| Latency p50 / p90 / p95 / p99 | **0.67ms / 2.54ms / 3.54ms / 6.38ms** |
| Max | 211ms |
| Thresholds (p95<500, p99<1000, err<1%) | **all PASS** |

**Integrity under load:** `PRAGMA integrity_check=ok`, no note-row leakage (count identical before/after — concurrent create/delete clean), servers healthy post-run. **No DB corruption, no lock errors, no degradation at 100 VUs.**

> **Note:** this run's post-load FK audit surfaced **BUG-033** (SQLite `foreign_keys` was never enforced at runtime → cascades silently no-op'd, orphans accumulated). Fixed via JDBC URL params; cascades verified live. See `BUG_REPORT.md`.

## k6 write-path load test (`perf/k6-write-path.js`) — EXECUTED
**Tooling:** k6 v2.0.0. **Scenario:** ramping 10 → 30 VUs over 40s; each iteration does notes **create → list → delete** (with cleanup) against the user service. **Thresholds:** p95 < 500ms, error rate < 1%, checks > 99%.

### Finding: BUG-013 (caught by this test)
First run exposed a **systemic concurrency bug** — `POST /api/notes` failed **~73%** (31.94% `http_req_failed`) with `SQLITE_CONSTRAINT_PRIMARYKEY: notes.id`: ~21 ID generators used timestamp-only IDs that collide under load. Fixed (random UUID suffix everywhere).

### After fix (PASS)
| Metric | Result |
|---|---|
| `create` 2xx | **100%** (was 27%) |
| `http_req_failed` | **0.00%** (was 31.94%) |
| checks succeeded | **16,786 / 16,786 (100%)** |
| p95 latency | **5.0ms** |
| p99 latency | **7.4ms** |
| iterations | ~157/s sustained at 30 VUs |

Latency is excellent and error-free under 30 concurrent write-heavy VUs after the fix; no DB corruption.

### Re-verification run — 2026-06-09 (regression-stable under load)
Re-ran the same staged scenario after the BUG-013 fix had been in place for a release cycle. Evidence: `docs/qa/evidence/k6-write-path-2026-06-09.json`.

| Metric | Result |
|---|---|
| `login/create/list/delete` checks | **16,990 / 16,990 (100.00%)** |
| `http_req_failed` | **0.00%** (0 of 16,990) |
| p95 latency | **4.2ms** |
| p99 latency | **6.55ms** |
| throughput | 421.85 req/s, 140.6 iter/s sustained at 30 VUs |
| iterations completed / interrupted | **5,663 / 0** |
| DB `integrity_check` after run | **ok** (WAL mode) |

All thresholds green. The systemic ID-collision class (BUG-013) remains fixed — **zero `SQLITE_CONSTRAINT_PRIMARYKEY` failures** across 5,663 concurrent create/delete cycles.

### Re-verification run — 2026-06-10 (after admin/user bug-fix batches)
Re-ran the staged write-path k6 scenario plus a mixed concurrent-create burst to confirm the BUG-019/040/041/043 fixes introduced no regression.

| Metric | Result |
|---|---|
| k6 staged (10→30 VUs, notes create/list/delete) | **17,077 / 17,077 checks (100%)**, `http_req_failed` **0.00%**, p95 **3.63ms**, p99 **6ms**, 5,692 iterations. Evidence: `docs/qa/evidence/k6-write-path-2026-06-10.json` |
| Mixed concurrent burst (120 parallel notes+contacts+tasks creates) | **120/120 → 2xx, 0 non-2xx** |
| DB after burst | `integrity_check` **ok**, journal_mode **wal**, 0 lock errors |

No ID-collision, constraint, or lock errors under concurrency; the `orDefault` NOT-NULL coercions hold under load.

## Soak / endurance — EXECUTED 2026-06-10 (8 min sustained, no leak)
Constant 20-VU write-path load (login → notes create → list → delete) for **8 minutes**, sampling JVM RSS/FDs each minute. Evidence: `docs/qa/evidence/k6-soak-2026-06-10.json`.

| Metric | Result |
|---|---|
| Requests | **256,015**, `http_req_failed` **0.00%** (0 of 256,015) |
| Iterations | 85,338 (~178/s sustained) · p95 **4.16ms** |
| RSS over time | 624 → 762MB (2 min peak) → **settled to 558MB** at 8 min — trends **down**, no upward leak |
| Open FDs | 162 → bounded ~178 (pool warmup) → **flat** — no descriptor leak |
| Heap used (post-GC) | **74MB** (baseline 312MB) — fully reclaims |
| DB after | `integrity_check` **ok**, WAL, **0** `SQLITE_BUSY`/lock errors |

**Verdict:** no memory leak, no FD leak, no latency drift, no DB lock contention over a quarter-million requests. (A multi-hour soak is still recommended for absolute confidence, but the 8-min trend — RSS and heap *decreasing* — shows healthy GC and bounded resources.)

## NOT executed (recommended next)
- **k6/Gatling** with staged ramp (10 → 50 → 100 → 500 VUs), think-time, and proper percentile aggregation.
- **Chat streaming under load** (requires Ollama) — latency + cancellation storms.
- **Write-path load** (notes/tasks/calendar CRUD bursts) and **concurrent same-row updates**.
- **Large RAG corpus retrieval** and **document ingestion** throughput.
- **Soak (2–6h)** — heap growth, FD leaks, stuck jobs, response-time drift.
- Resource telemetry (CPU/RSS/GC/threads/FDs) via JFR or `jstat` during the runs.
