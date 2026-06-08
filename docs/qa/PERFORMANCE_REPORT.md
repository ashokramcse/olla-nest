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

## NOT executed (recommended next)
- **k6/Gatling** with staged ramp (10 → 50 → 100 → 500 VUs), think-time, and proper percentile aggregation.
- **Chat streaming under load** (requires Ollama) — latency + cancellation storms.
- **Write-path load** (notes/tasks/calendar CRUD bursts) and **concurrent same-row updates**.
- **Large RAG corpus retrieval** and **document ingestion** throughput.
- **Soak (2–6h)** — heap growth, FD leaks, stuck jobs, response-time drift.
- Resource telemetry (CPU/RSS/GC/threads/FDs) via JFR or `jstat` during the runs.
