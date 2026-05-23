# Version

**Current version:** v2026.1.4

**Release date:** 2026-05-23

**Runtime:** Oracle Java 26 + Spring Boot 3.5.14 (latest stable patch)

**Database:** SQLite 3.49.x (Flyway managed, WAL mode)

**Build:** Maven 3.9+

---

## Dependency Versions

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 26 (Oracle JDK) | JVM target; bytecode compiled to Java 21 LTS for Spring Boot ASM compat |
| Spring Boot | 3.5.14 | Latest stable patch — May 2026 |
| Spring AI | 1.0.0 GA | RAG, prompt templates, function calling |
| SQLite JDBC | 3.49.1.0 | BOM-managed by Spring Boot |
| Flyway | 11.x | BOM-managed by Spring Boot |
| Jackson | 2.18.x | BOM-managed by Spring Boot |
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
| Voice input (OpenAI Whisper STT) | v2026.1.3 |
| Voice readback (OpenAI TTS-1) | v2026.1.3 |
| Image generation (DALL-E 3 + Stable Diffusion) | v2026.1.3 |
| Deep Research (Plan → Search → Synthesise) | v2026.1.3 |
| Code Sandbox (Python / JS / Ruby / Java / Bash) | v2026.1.3 |
| Comprehensive JavaDoc (79 files) | v2026.1.4 |
| Spring Boot 3.5.14 | v2026.1.4 |
