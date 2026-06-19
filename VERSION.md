# Version

**Current version:** v2026.2.2

**Release date:** 2026-06-19

**Runtime:** Oracle Java 26 + Spring Boot 4.1.0 (Spring Framework 7)

**Database:** SQLite 3.49.x (Flyway managed, WAL mode — V1–V12 migrations)

**Build:** Maven 3.9+ (multi-module: `olla-nest-common`, `olla-nest-admin`, `olla-nest-user`)

---

## Dependency Versions

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 26 (Oracle JDK) | JVM target; bytecode compiled to release 21 — Spring Boot 4 / Framework 7 baseline is Java 17+ |
| Spring Boot | 4.1.0 | Spring Framework 7; auto-config split into per-tech modules (`spring-boot-flyway`, `spring-boot-webmvc-test`) |
| Spring AI | 2.0.0 GA | RAG, prompt templates, function calling; `PromptTemplate.builder()` idiom |
| SQLite JDBC | 3.49.1.0 | BOM-managed by Spring Boot |
| Flyway | 11.x | BOM-managed; auto-config now in the `spring-boot-flyway` module |
| Jackson | 2.x | BOM-managed (Boot 4 also bundles Jackson 3; app uses the Jackson 2 API) |
| Apache PDFBox | 3.0.7 | PDF text extraction for RAG |
| marked.js | 18.0.4 | Markdown rendering (frontend) |
| highlight.js | 11.11.1 | Code syntax highlighting (frontend) |
| DOMPurify | 3.4.5 | XSS sanitisation (frontend) |
| Chart.js | 4.5.1 | Usage charts in admin (frontend) |
| xterm.js | 5.3.0 | WebSocket terminal (frontend) |

---

## Version History

| Version | Date | Summary |
|---------|------|---------|
| v2026.2.2 | 2026-06-19 | IDE config-metadata + constructor-injection cleanup; user-app context test; canonical comment skeleton across all 144 main classes; QA regression-confirmation campaign (2,190 tests, k6 0% fail, Playwright E2E, backup verified) |
| v2026.2.1 | 2026-06-16 | **Spring Boot 4.1.0**; canonical Javadoc/comment skeleton + per-method security/auth test docs (~1,040 methods) |
| v2026.2.0 | 2026-06-16 | **Major upgrade: Spring Boot 3.5 → 4.0 (Spring Framework 6 → 7), Spring AI 1.0 → 2.0**; Boot-4 module-split fixes (Flyway/MockMvc/UserDetails relocations); POI/ical4j/jsoup/ZXing bumps |
| v2026.1.9 | 2026-05-25 | SQL hardening, SOC 2 audit, 140 new security tests (1,559 total), GitGuardian remediation |
| v2026.1.8 | 2026-05-24 | Voice status feedback UX; log prefix dedup; Java formatter |
| v2026.1.7 | 2026-05-24 | Firefox voice fix: click-to-toggle MediaRecorder; 600ms guard; inline status |
| v2026.1.6 | 2026-05-24 | Branded alert overlays; STT admin UI; cross-platform Whisper setup |
| v2026.1.5 | 2026-05-24 | Local faster-whisper STT server; WhisperServerManager auto-start; OpenAI STT fallback |
| v2026.1.4 | 2026-05-23 | Spring Boot 3.5.14; comprehensive JavaDoc (79 files); `ok:false` consistency fix; SSRF fix for self-hosted tools |
| v2026.1.3 | 2026-05-23 | 20 connectors, SSO (Google/OIDC/SAML), web search, voice/image gen, deep research, code sandbox |
| v2026.1.2 | 2026-05-22 | Spring AI 1.0.0 GA: RAG/vector store, prompt templates, function calling |
| v2026.1.1 | 2026-05-22 | Java 26 runtime, sqlite-jdbc 3.49.1.0, Spring Boot 3.5.3 confirmed latest |
| v2026.1.0 | 2026-05-22 | Full migration to Java Spring Boot, Docker removed, 19 security fixes |
| v2026.0.30 | 2026-05-21 | 3-column workspace UI redesign, new chat session fix |
| v2026.0.29 | 2026-05-21 | Admin Reports KPI card height fix |
| v2026.0.28 | 2026-05-21 | SSE streaming with phase UX |

---

## Feature Matrix

| Feature | Since |
|---------|-------|
| Auto Router (local + cloud) | v2026.1.0 |
| Multi-provider AI (Ollama, Anthropic, OpenAI, Groq, custom) | v2026.1.0 |
| Role-based access control | v2026.1.0 |
| Departments / groups / teams | v2026.1.0 |
| AES-256-GCM credential encryption | v2026.1.0 |
| WebSocket terminal | v2026.1.0 |
| Workspace file integration | v2026.1.0 |
| 19-point security hardening | v2026.1.0 |
| RAG / vector store (PDFBox, cosine similarity) | v2026.1.2 |
| Spring AI prompt templates | v2026.1.2 |
| Function calling (4 built-in tools) | v2026.1.2 |
| 20 data source connectors | v2026.1.3 |
| SSO — Google OAuth 2.0 | v2026.1.3 |
| SSO — Generic OIDC | v2026.1.3 |
| SSO — SAML 2.0 | v2026.1.3 |
| Web Search (Serper / Brave / SearXNG) | v2026.1.3 |
| Voice input — local faster-whisper (free, default) | v2026.1.5 |
| Voice input — OpenAI Whisper STT (paid, optional) | v2026.1.3 |
| WhisperServerManager auto-start on port 8765 | v2026.1.5 |
| Cross-platform Whisper setup scripts (Mac/Linux/Windows) | v2026.1.6 |
| Voice readback (OpenAI TTS-1) | v2026.1.3 |
| Image generation (DALL-E 3 + Stable Diffusion) | v2026.1.3 |
| Deep Research (Plan → Search → Synthesise) | v2026.1.3 |
| Code Sandbox (Python / JS / Ruby / Java / Bash) | v2026.1.3 |
| Comprehensive JavaDoc (79 files) | v2026.1.4 |
| Spring Boot 3.5.14 | v2026.1.4 |
| Maven multi-module (common / admin / user) | v2026.1.5 |
| SQL injection prevention (enum guards, LIMIT bounds, allow-lists) | v2026.1.9 |
| SOC 2 audit trail (auth.login.failed, IP in events) | v2026.1.9 |
| BCrypt DoS prevention (email/password length limits) | v2026.1.9 |
| SSO bypass prevention (auth_provider='local' login gate) | v2026.1.9 |
| Concurrent backup guard (AtomicBoolean CAS) | v2026.1.9 |
| MDC structured logging (requestId, userId, role, ip per request) | v2026.1.9 |
| System path block-list for workspaceRoot | v2026.1.9 |
| Flyway V6 — performance indexes (26 total) | v2026.1.9 |
| 1,559 automated tests | v2026.1.9 |
| Flyway V7–V12 — productivity / PIM / platform / cookbook-vault-companion schemas (62 tables) | v2026.1.9–v2026.2.x |
| Spring Boot 4.0 → 4.1.0 (Spring Framework 7), Spring AI 2.0.0 | v2026.2.0–v2026.2.1 |
| Canonical comment skeleton across all 144 main classes | v2026.2.1–v2026.2.2 |
| 2,190 automated tests (88 classes, 0 fail) | v2026.2.2 |
