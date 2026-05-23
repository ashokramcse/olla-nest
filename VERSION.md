# Version

**Current version:** v2026.1.3

**Release date:** 2026-05-23

**Runtime:** Oracle Java 26 + Spring Boot 3.5.3 (latest stable)

**Database:** SQLite 3.49.x (Flyway managed, WAL mode)

**Build:** Maven 3.9+

---

## Dependency Versions

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 26 (Oracle JDK) | JVM target; bytecode compiled to Java 21 LTS for Spring Boot ASM compat |
| Spring Boot | 3.5.3 | Latest stable — May 2026 |
| Spring AI | 1.0.0 GA | RAG, prompt templates, function calling |
| SQLite JDBC | 3.49.1.0 | BOM-managed by Spring Boot |
| Flyway | 11.x | BOM-managed by Spring Boot |
| Jackson | 2.18.x | BOM-managed by Spring Boot |
| Apache PDFBox | 3.0.7 | PDF text extraction for RAG (latest stable) |
| marked.js | 18.0.4 | Markdown rendering (frontend, latest) |
| highlight.js | 11.11.1 | Code syntax highlighting (frontend, latest) |
| DOMPurify | 3.4.5 | XSS sanitisation (frontend, latest) |
| Chart.js | 4.5.1 | Usage charts in admin (frontend, latest) |
| xterm.js | 5.3.0 | WebSocket terminal (frontend, latest) |

---

## Version History

| Version | Date | Summary |
|---------|------|---------|
| v2026.1.3 | 2026-05-23 | Clean version catalog in pom.xml, resource file headers, all deps at latest, PDFBox 3.0.7 |
| v2026.1.2 | 2026-05-22 | Spring AI 1.0.0 GA: RAG/vector store, prompt templates, function calling |
| v2026.1.1 | 2026-05-22 | Java 26 runtime, sqlite-jdbc 3.49.1.0, Spring Boot 3.5.3 confirmed latest |
| v2026.1.0 | 2026-05-22 | Full migration to Java Spring Boot, Docker removed, 19 security fixes |
| v2026.0.30 | 2026-05-21 | 3-column workspace UI redesign, new chat session fix |
| v2026.0.29 | 2026-05-21 | Admin Reports KPI card height fix |
| v2026.0.28 | 2026-05-21 | SSE streaming with phase UX |
