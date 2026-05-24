<div align="center">

<img src="docs/logo-readme.svg" alt="Olla Nest" width="480" />

### Your AI. Your rules. Your hardware.

**The company AI workspace that routes every request to the right model automatically —**  
**local Ollama models, cloud providers, or both. Zero lock-in. Full admin control.**

<br/>

[![Version](https://img.shields.io/badge/version-v2026.1.8-f5c842?style=for-the-badge&logo=git&logoColor=black)](https://github.com/ashokramcse/olla-nest/releases)
[![License](https://img.shields.io/badge/license-MIT-22c55e?style=for-the-badge)](LICENSE)
[![Java](https://img.shields.io/badge/Java-26-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.14-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring_AI-1.0.0-6DB33F?style=for-the-badge&logo=spring&logoColor=white)](https://spring.io/projects/spring-ai)
[![SQLite](https://img.shields.io/badge/SQLite-WAL_mode-003B57?style=for-the-badge&logo=sqlite&logoColor=white)](https://sqlite.org)

<br/>

[**Quick Start**](#quick-start) · [**Features**](#features) · [**Auto Router**](#auto-router) · [**Connectors**](#connectors--20-data-sources) · [**SSO**](#sso--enterprise-auth) · [**Voice & Images**](#voice--image-generation) · [**Deep Research**](#deep-research) · [**Web Search**](#web-search) · [**Code Sandbox**](#code-sandbox) · [**Admin Dashboard**](#admin-dashboard) · [**Providers**](#cloud-provider-setup) · [**Security**](#security) · [**Changelog**](CHANGELOG.md)

<br/>

---

</div>

## What is Olla Nest?

Olla Nest is a **self-hosted company AI workspace** — an admin-controlled layer that sits on top of Ollama and any cloud provider. Employees open one URL, type anything, and the **Auto Router** picks the best available model automatically. No API keys for employees. No model names to memorise. No data leaving the building unless you explicitly configure it.

<div align="center">
  <img src="docs/architecture.svg" alt="Olla Nest Auto Router Architecture" width="640"/>
</div>

---

## Features

<table>
<tr>
<td width="50%" valign="top">

**🤖 Intelligence**
- **Auto Router** — scores every request against all approved models; picks the best automatically
- **Multi-provider** — Ollama (local), Anthropic Claude, OpenAI, Groq, any custom OpenAI-compatible endpoint
- **Streaming SSE** — real-time token output with routing → streaming → done phase indicators
- **Privacy gate** — SSNs, credit cards, API keys, PHI patterns block external routing automatically
- **Configurable weights** — tune speed vs. quality vs. privacy per workspace
- **Per-mode local-only** — `build` and `fix` modes never leave your hardware
- **RAG / Vector Store** — upload PDFs, TXT, MD; chunked, embedded, retrieved per query
- **Deep Research** — Plan → Search (web + RAG) → Synthesise; fully streamed with progress steps
- **Web Search** — Serper (Google), Brave Search, or self-hosted SearXNG augmentation
- **Function calling** — `get_datetime`, `calculate`, `search_knowledge_base`, `get_system_info`

</td>
<td width="50%" valign="top">

**🔐 Security & Auth**
- **Session tokens** — 256-bit `SecureRandom` hex, HttpOnly cookie, SameSite=Lax
- **AES-256-GCM** — all API keys, SSO secrets, and connector credentials encrypted at rest
- **SSO** — Google OAuth 2.0, generic OIDC (Okta / Azure AD / Auth0 / Keycloak), SAML 2.0
- **Auto-provisioning** — SSO users created on first login, matched by email thereafter
- **Brute-force protection** — IP-based lockout: 5 attempts / 15 min
- **SSRF protection** — private and loopback IPs blocked on all cloud provider URLs
- **Terminal auth** — WebSocket requires authenticated session + `workspace:build` right
- **Security headers** — `Content-Security-Policy`, `Strict-Transport-Security`, `X-Frame-Options`
- **BCrypt** — password hashing at cost factor 12
- **Session invalidation** — role/permission changes immediately expire all user sessions

</td>
</tr>
<tr>
<td width="50%" valign="top">

**👥 Enterprise**
- **RBAC** — admin and user roles with fine-grained per-right permissions
- **Teams hierarchy** — departments → groups → teams with inherited permission sets
- **Per-user limits** — daily token cap, request rate limits, model tier access
- **Model governance** — GPU requirement, sensitivity tier, model-level caps, access expiry
- **Full audit log** — every request, model used, tokens consumed, timestamp, per-user
- **Access expiry** — expired accounts blocked at login; admin can revoke instantly
- **20+ connectors** — GitHub, Slack, Notion, Jira, Google Drive, Salesforce, and 14 more
- **Hourly connector sync** — SHA-256 dedup; only changed documents are re-ingested
- **Admin reports** — daily activity, token leaderboard, model usage, latency, department breakdown
- **Health dashboard** — JVM memory, DB stats, uptime, request / error counters

</td>
<td width="50%" valign="top">

**🛠 Developer**
- **8 chat modes** — ask, build, fix, review, debug, test, docs, plan (each with expert system prompt)
- **Workspace integration** — AI writes, reads, and browses files in your local workspace
- **Interactive terminal** — WebSocket + `ProcessBuilder`, authenticated, streamed in real time
- **Code Sandbox** — execute Python, JS, Ruby, Java, Bash; 10 s kill; stripped env; 4 KB cap
- **Voice input (local, free)** — `faster-whisper` runs on your server; auto-started by Spring Boot on port 8765; WAV, MP3, WebM, OGG, M4A supported
- **Voice input (cloud, optional)** — OpenAI Whisper API as a paid fallback; selectable per deployment in Admin → Settings
- **Voice readback** — OpenAI TTS-1; voices: alloy, echo, fable, onyx, nova, shimmer
- **Image generation** — DALL-E 3 (CDN URL) or Stable Diffusion Automatic1111 (base64 PNG)
- **Prompt templates** — per-mode Spring AI `PromptTemplate` with `{variable}` substitution
- **SQLite backups** — `@Scheduled` daily backup with 7-file rotation; zero-config
- **Flyway migrations** — V1–V5 schema applied automatically on startup

</td>
</tr>
</table>

---

## Quick Start

### Prerequisites

- **Java 26+** — [Download Oracle JDK 26](https://www.oracle.com/java/technologies/downloads/) or [OpenJDK](https://adoptium.net)
- **Maven 3.9+** — [Download Maven](https://maven.apache.org/download.cgi)
- **Ollama** — [Download Ollama](https://ollama.com) with at least one model pulled:
  ```bash
  ollama pull llama3.2:3b
  ```

### 1 — Clone and configure

```bash
git clone https://github.com/ashokramcse/olla-nest.git
cd olla-nest
cp .env.example .env
```

Edit `.env` — set a strong `ENCRYPTION_KEY` and your `OLLAMA_URL`:

```env
ENCRYPTION_KEY=your-random-secret-key-here
OLLAMA_URL=http://localhost:11434
DEFAULT_ADMIN_EMAIL=admin@ollanest.local
```

> **First-boot password:** Leave `DEFAULT_ADMIN_PASSWORD` unset or as `CHANGE_ME_ON_FIRST_BOOT`. The server generates a secure random password and prints it once to the console on first startup.

### 2 — Build

```bash
mvn clean package -DskipTests
```

### 3 — Run

```bash
java --enable-native-access=ALL-UNNAMED -jar target/olla-nest-*.jar
```

Or with explicit env vars:

```bash
ENCRYPTION_KEY=my-secret OLLAMA_URL=http://localhost:11434 java --enable-native-access=ALL-UNNAMED -jar target/olla-nest-*.jar
```

Open **http://localhost:3000** — the login page will appear.

### Running from Eclipse

1. **File → Import → Maven → Existing Maven Projects** → select the project folder
2. Right-click `OllaNestApplication.java` → **Run As → Run Configurations**
3. Add environment variables in the **Environment** tab
4. Click **Run**

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for the full deployment guide.

---

## Auto Router

Every message is classified into capability tags (coding, debugging, writing, medical, vision, etc.) and scored against all approved models:

```
score = capabilityMatch(35) + specialistBonus(45) + speedScore + qualityScore + privacyScore
```

**Privacy gate:** If a message contains SSNs, credit cards, API keys, or medical terms — or the mode is `build`/`fix` — external providers are removed from the candidate pool automatically. Sensitive content stays local.

**Manual override:** Users can bypass the router and select any model they are approved for from the composer dropdown.

---

## Connectors — 20 Data Sources

Olla Nest can ingest and index content from 20 external services, making it available for RAG retrieval in every chat. Connectors sync hourly in the background. Content is deduplicated using SHA-256 hashing — only changed documents are re-ingested.

### Available Connectors

| Connector | Type | What it syncs | Auth |
|-----------|------|---------------|------|
| **GitHub** | `github` | READMEs, open issues | Personal Access Token |
| **GitLab** | `gitlab` | Issues, READMEs | Personal Access Token |
| **Bitbucket** | `bitbucket` | Repos, issues | App password (Basic) |
| **Google Drive** | `gdrive` | Docs (plain text), PDFs | OAuth access token |
| **OneDrive / SharePoint** | `onedrive` | Text/Word files via MS Graph | OAuth access token |
| **Dropbox** | `dropbox` | txt, md, csv, json files | OAuth access token |
| **Slack** | `slack` | Public channel messages (last 100/channel) | Bot token (`xoxb-`) |
| **Microsoft Teams** | `teams` | Channel messages via MS Graph | OAuth access token |
| **Discord** | `discord` | Text channel messages | Bot token |
| **Notion** | `notion` | Pages and database entries | Integration token (`secret_`) |
| **Confluence** | `confluence` | Space pages (HTML stripped) | Email + API token (Basic) |
| **Jira** | `jira` | Issues with ADF description + comments | Email + API token (Basic) |
| **Linear** | `linear` | Issues via GraphQL | API key (`lin_api_`) |
| **Asana** | `asana` | Tasks from all workspaces | Personal Access Token |
| **Airtable** | `airtable` | All tables in a base (100 records each) | API key |
| **HubSpot** | `hubspot` | Contacts, deals | API key |
| **Salesforce** | `salesforce` | Opportunities, accounts via SOQL | OAuth access token + instance URL |
| **Zendesk** | `zendesk` | Tickets, help center articles | Email + API token (Basic) |
| **Gmail** | `gmail` | Emails by label (metadata + snippet) | OAuth access token |
| **Figma** | `figma` | File names + component descriptions | Personal Access Token (`figd_`) |

### Connector Administration

Go to **Admin → Connectors** to manage data sources:

```http
GET    /api/admin/connectors              # list all connectors
POST   /api/admin/connectors              # create new connector
PATCH  /api/admin/connectors/{id}         # update config or credentials
DELETE /api/admin/connectors/{id}         # delete + cascade cleanup
POST   /api/admin/connectors/{id}/sync    # trigger manual sync now
GET    /api/admin/connectors/{id}/logs    # view sync history
POST   /api/admin/connectors/{id}/test    # test credentials
```

Credentials are encrypted with AES-256-GCM before storage and never returned to the browser.

---

## SSO / Enterprise Auth

Olla Nest supports three enterprise authentication protocols alongside standard username/password login.

### Supported Protocols

| Protocol | Use case | Required fields |
|----------|----------|-----------------|
| **Google OAuth 2.0** | Google Workspace orgs | Client ID + Secret |
| **Generic OIDC** | Okta, Azure AD, Auth0, Keycloak | Client ID + Secret + Issuer URL |
| **SAML 2.0** | Enterprise IdPs (AD FS, Okta SAML) | IdP metadata URL + Entity ID |

### SSO Flow Endpoints

```http
GET  /api/auth/sso/providers          # list enabled providers (login page)
GET  /api/auth/sso/authorize/{id}     # redirect to IdP (stores CSRF state nonce)
GET  /api/auth/sso/callback           # OAuth/OIDC code exchange → session → /app
POST /api/auth/sso/saml/acs           # SAML assertion consumer → session → /app
```

**Auto-provisioning:** Users who authenticate via SSO for the first time are automatically created with role `user`. Existing accounts are matched by email address.

Configure via **Admin → Settings → Authentication**. Client secrets are AES-256-GCM encrypted at rest.

---

## Web Search

The composer **Web** toggle augments LLM responses with live search results. Three providers are supported:

| Provider | Setting value | API key required |
|----------|---------------|-----------------|
| **Serper** (Google results) | `serper` | `searchApiKey` from serper.dev |
| **Brave Search** | `brave` | `searchApiKey` from api.search.brave.com |
| **SearXNG** (self-hosted) | `searxng` | None — configure `searchBaseUrl` |

Up to 5 results are prepended to the system prompt as:

```
CURRENT WEB SEARCH RESULTS:
[1] Title
URL: https://...
Snippet text...
```

Configure via **Admin → Settings → Web Search** (`searchProvider`, `searchApiKey`, `searchBaseUrl`).

---

## Voice & Image Generation

### Voice Input (Speech-to-Text)

Olla Nest supports two STT providers — selectable in **Admin → Settings → Voice STT Provider**:

| Provider | Cost | Default | Setup required |
|----------|------|---------|----------------|
| **Local** (`faster-whisper`) | Free | ✅ Yes | Run `bash scripts/start_whisper.sh` once |
| **OpenAI Whisper API** | $0.006/min | No | Set `openaiApiKey` in Settings |

#### Local STT — One-time Setup

`WhisperServerManager` auto-starts the local Whisper HTTP server on port 8765 when Olla Nest launches. You only need to run the setup script once to create the Python virtual environment:

**macOS / Linux:**
```bash
bash scripts/start_whisper.sh
```

**Windows:**
```cmd
scripts\start_whisper.bat
```

**Windows PowerShell / Windows Server:**
```powershell
.\scripts\start_whisper.ps1
```

> **Requirements:** Python 3.9–3.12 (NOT 3.13/3.14 — `av` wheels are unavailable), ffmpeg.  
> The script auto-installs both via Homebrew (macOS), apt/dnf/apk (Linux), or winget (Windows).

After setup, Olla Nest automatically starts the Whisper server in the background on every boot — no manual step needed. Check logs for `[whisper] Process started (PID …)`.

#### API

```http
POST /api/voice/transcribe
Content-Type: multipart/form-data
Body: audio file (WAV, MP3, WebM, OGG, M4A)
→ { "text": "transcribed speech" }
```

Supported formats: WAV, MP3, WebM, OGG, M4A, FLAC.

### Voice Readback (Text-to-Speech)

AI responses can be read aloud via **OpenAI TTS-1**:

```http
POST /api/voice/speak
Body: { "text": "...", "voice": "alloy" }
→ audio/mpeg binary stream
```

Available voices: `alloy`, `echo`, `fable`, `onyx`, `nova`, `shimmer`. Defaults to `alloy`.

### Image Generation

```http
POST /api/images/generate
Body: { "prompt": "a mountain at sunset", "provider": "dalle" }
→ { "imageUrl": "https://...", "base64": null, "provider": "dalle", "model": "dall-e-3" }
```

| Provider | `imageProvider` value | Notes |
|----------|-----------------------|-------|
| **DALL-E 3** | `dalle` | Returns CDN URL. Requires `openaiApiKey`. Configurable `imageModel` + `imageSize`. |
| **Stable Diffusion** | `stable-diffusion` | Returns base64 PNG. Configure `sdBaseUrl` (default `http://localhost:7860`). Uses Automatic1111 WebUI API. |

---

## Deep Research

Deep Research is a multi-step pipeline that decomposes a complex question, searches for evidence, and synthesises a comprehensive report — all streamed in real time.

### Pipeline

```
Step 1: Plan      — LLM decomposes query into 3–5 focused sub-questions
Step 2: Search    — For each sub-question: web search (5 results) + RAG retrieval
Step 3: Synthesise — LLM writes full cited report from all gathered context
```

### SSE Progress Events

```json
{ "type": "research_step", "step": "plan",      "status": "done",    "subQuestions": [...] }
{ "type": "research_step", "step": "search",     "status": "running", "query": "sub-question" }
{ "type": "research_step", "step": "synthesize", "status": "running", "msg": "Writing report..." }
```

Enable by clicking the **Research** button in the composer, or send `"deepResearch": true` in the chat POST body.

---

## Code Sandbox

The sandbox button executes code blocks securely inside isolated subprocesses.

### Supported Languages

| Language | Runtime | Timeout |
|----------|---------|---------|
| **Python 3** | `python3` | 10 s SIGKILL |
| **JavaScript** | `node` | 10 s SIGKILL |
| **Ruby** | `ruby` | 10 s SIGKILL |
| **Java** | `javac` + `java` (compile-then-run) | 10 s SIGKILL |
| **Bash** | `bash` | 10 s SIGKILL |

### Security Model

- Stripped environment — only `PATH`, `HOME`, `LANG` set
- Fresh temp directory per execution — no cross-run file access
- Hard SIGKILL at 10 seconds — no runaway processes
- Output capped at 4 096 characters
- Requires `workspace:build` right

```http
POST /api/sandbox/run
Body: { "language": "python", "code": "print(2 + 2)" }
→ { "ok": true, "output": "4\n", "exitCode": 0, "language": "python" }
```

---

## RAG — Document Knowledge Base

Upload company documents to make them searchable in every chat:

```http
POST /api/documents/upload     # PDF, TXT, MD — max 10 MB
GET  /api/documents            # list all documents
DELETE /api/documents/{id}     # delete document + remove embeddings
```

| Setting | Value |
|---------|-------|
| Chunk size | 512 tokens |
| Chunk overlap | 64 tokens |
| Top-K retrieval | 5 chunks |
| Similarity threshold | 0.30 cosine |
| PDF support | Apache PDFBox 3.0.7 |
| Fallback | Keyword similarity when no embedding model configured |

Connector documents are indexed by the same RAG store — connector syncs use SHA-256 dedup so only changed content is re-embedded.

---

## Function Calling

The AI can invoke 4 built-in tools during a conversation (requires a model with tool-calling support):

| Tool | Description |
|------|-------------|
| `get_datetime` | Current date, time, day of week, timezone |
| `calculate` | Safe math expression evaluator |
| `search_knowledge_base` | Searches RAG vector store on demand |
| `get_system_info` | Product version and JVM runtime info |

Maximum 1 tool-call round-trip per message to prevent infinite loops.

---

## Admin Dashboard

| Section | What you control |
|---------|-----------------|
| **Users** | Create, edit, deactivate, set roles, reset passwords, view effective access |
| **Models** | Governance tier, GPU requirement, sensitivity policy, per-model caps |
| **Providers** | Add Anthropic / OpenAI / Groq / custom endpoints with encrypted API keys |
| **Connectors** | 20 data source connectors — create, configure, sync, view logs, test credentials |
| **Settings** | Ollama URL, router weights, SSO providers, web search, voice, image generation, workspace root |
| **Teams** | Departments, groups, teams with default permission sets |
| **Reports** | Daily activity, model usage, token leaderboard, latency, department breakdown |
| **Health** | DB stats, JVM memory, uptime, request/error counters |

---

## Cloud Provider Setup

Go to **Admin → Providers → Add Provider**:

| Provider | Type | API key source |
|----------|------|----------------|
| Anthropic Claude | `anthropic` | console.anthropic.com |
| OpenAI | `openai` | platform.openai.com |
| Groq | `groq` | console.groq.com |
| Custom / LiteLLM | `custom` | Any OpenAI-compatible endpoint |

All API keys are AES-256-GCM encrypted before storage. Keys are never returned to the browser after saving.

---

## Security

| Feature | Implementation |
|---------|---------------|
| Session tokens | 256-bit `SecureRandom` hex, HttpOnly cookie, SameSite=Lax |
| Password hashing | BCrypt cost factor 12 |
| API key / secret encryption | AES-256-GCM, random 12-byte IV per value |
| Connector credential encryption | AES-256-GCM in `connector_configs.credentials_enc` |
| SSO client secret encryption | AES-256-GCM in `sso_providers.client_secret_enc` |
| Login protection | IP-based rate limiting (5 attempts / 15 min) |
| SSRF protection | Cloud provider URLs — private/loopback IPs blocked; self-hosted URLs (SD, SearXNG) allow localhost |
| Path traversal | Workspace browse restricted to user home directory |
| Terminal auth | WebSocket requires authenticated session + `workspace:build` right |
| CSP | `Content-Security-Policy` header on all responses |
| HSTS | `Strict-Transport-Security: max-age=31536000` |
| SSO CSRF | State nonce stored in `oauth_state` table, validated on OAuth callback |
| Sensitive content routing | SSN, credit card, API key, PHI regex detection blocks external providers |
| Admin session invalidation | Changing a user's role or permissions immediately invalidates their sessions |
| Sandbox isolation | Subprocess stripped env, temp working dir, 10 s SIGKILL, 4 KB output cap |

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `3000` | HTTP listening port |
| `ENCRYPTION_KEY` | *(required)* | AES key for encrypting API keys, SSO secrets, connector credentials |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama server base URL |
| `DATA_DIR` | `./data` | SQLite database and backup directory |
| `STATIC_DIR` | `./public` | Static frontend files directory |
| `DEFAULT_ADMIN_EMAIL` | `admin@ollanest.local` | Admin email seeded on first boot |
| `DEFAULT_ADMIN_PASSWORD` | *(auto-generated)* | Leave blank for secure auto-generation |
| `COOKIE_SECURE` | `false` | Set `true` behind HTTPS reverse proxy |
| `TRUSTED_PROXY` | *(empty)* | Trusted proxy IP for X-Forwarded-For |
| `APP_BASE_URL` | `http://localhost:3000` | SSO redirect_uri base URL |

### Runtime Settings (Admin → Settings)

| Key | Default | Description |
|-----|---------|-------------|
| `openaiApiKey` | — | OpenAI key for voice (Whisper/TTS) and DALL-E |
| `imageProvider` | `dalle` | `dalle` or `stable-diffusion` |
| `imageModel` | `dall-e-3` | DALL-E model name |
| `imageSize` | `1024x1024` | DALL-E output dimensions |
| `sdBaseUrl` | `http://localhost:7860` | Stable Diffusion Automatic1111 URL |
| `searchProvider` | `serper` | `serper`, `brave`, or `searxng` |
| `searchApiKey` | — | Serper or Brave API key (encrypted) |
| `searchBaseUrl` | — | SearXNG self-hosted base URL |
| `ttsVoice` | `alloy` | Default TTS voice |

---

## API Reference

### Authentication
```http
POST /api/auth/login              # { email, password } → session cookie
POST /api/auth/logout             # clears session
GET  /api/bootstrap               # app config for current user (models, settings)
```

### Chat
```http
POST /api/chat/stream             # SSE stream; body: { message, mode, enableWebSearch, deepResearch }
POST /api/chat/feedback           # { messageId, rating: "up"|"down" }
POST /api/chat/clear              # archive current chat, start fresh
GET  /api/threads                 # list chat threads
GET  /api/threads/{id}            # get thread messages
DELETE /api/threads/{id}          # delete thread
```

### Documents / RAG
```http
POST /api/documents/upload        # multipart: file (PDF/TXT/MD, max 10 MB)
GET  /api/documents               # list documents
DELETE /api/documents/{id}        # delete document + embeddings
```

### Voice
```http
POST /api/voice/transcribe        # multipart audio → { text }
POST /api/voice/speak             # { text, voice } → audio/mpeg
```

### Images
```http
POST /api/images/generate         # { prompt, provider? } → { imageUrl, base64, provider, model }
```

### Code Sandbox
```http
POST /api/sandbox/run             # { language, code } → { ok, output, exitCode, language }
```

### SSO
```http
GET  /api/auth/sso/providers      # list enabled SSO providers
GET  /api/auth/sso/authorize/{id} # redirect to IdP
GET  /api/auth/sso/callback       # OAuth/OIDC callback
POST /api/auth/sso/saml/acs       # SAML assertion consumer
GET  /api/admin/sso/providers     # admin: list all SSO providers
POST /api/admin/sso/providers     # admin: create SSO provider
PATCH  /api/admin/sso/providers/{id}  # admin: update provider
DELETE /api/admin/sso/providers/{id}  # admin: delete provider
```

### Workspace
```http
GET  /api/workspace/files         # browse workspace files
POST /api/workspace/write         # write AI-generated file
GET  /api/workspace/read          # read file content
```

### Admin
```http
GET/POST/PATCH/DELETE /api/admin/users/{id}
GET/POST/PATCH/DELETE /api/admin/models/{id}
GET/POST/PATCH/DELETE /api/admin/providers/{id}
GET/POST/PATCH/DELETE /api/admin/connectors/{id}
POST   /api/admin/connectors/{id}/sync
GET    /api/admin/connectors/{id}/logs
POST   /api/admin/connectors/{id}/test
GET/POST /api/admin/settings
GET    /api/admin/reports/summary
GET    /api/admin/reports/users
GET    /api/admin/reports/models
GET    /api/admin/health
GET/POST/DELETE /api/admin/teams/departments
GET/POST/DELETE /api/admin/teams/groups
GET/POST/DELETE /api/admin/teams/teams
```

---

## Database Schema

SQLite in WAL mode, managed by Flyway — schema applied automatically on startup.

| Migration | Tables |
|-----------|--------|
| `V1__init.sql` | `users`, `sessions`, `settings`, `departments`, `groups`, `teams`, `roles`, `permissions`, `models`, `api_providers`, `chats`, `messages`, `rag_documents`, `rag_chunks`, `audit_log` |
| `V3__connectors.sql` | `connector_configs`, `connector_sync_log`, `connector_documents` |
| `V4__sso.sql` | `sso_providers`, `oauth_state` |
| `V5__search_images.sql` | Settings columns for search / image / voice |

---

## Project Structure

```
olla-nest/
├── pom.xml                                    # Maven — Spring Boot 3.5.14, Spring AI 1.0.0
├── src/main/java/com/ollanest/
│   ├── OllaNestApplication.java               # Entry point, virtual thread executor
│   ├── config/                                # Spring config beans
│   ├── controller/                            # REST controllers
│   │   └── admin/                             # Admin-only endpoints
│   ├── connector/                             # 20 data source connectors
│   │   ├── BaseConnector.java                 # Abstract base (HTTP, SHA-256 dedup, RAG)
│   │   ├── ConnectorRegistry.java             # Type → impl dispatch
│   │   ├── ConnectorSyncScheduler.java        # Hourly @Scheduled sync
│   │   └── impl/                              # AirtableConnector … ZendeskConnector
│   ├── filter/                                # SecurityHeadersFilter, SessionAuthFilter
│   ├── model/                                 # User, ModelRecord POJOs
│   ├── service/                               # All business logic
│   │   ├── AuthService.java                   # Sessions, BCrypt
│   │   ├── BackupService.java                 # @Scheduled daily backup
│   │   ├── ChatService.java                   # Context assembly, system prompts
│   │   ├── CodeSandboxService.java            # ProcessBuilder isolated execution
│   │   ├── CryptoService.java                 # AES-256-GCM
│   │   ├── DatabaseService.java               # Flyway + seed data
│   │   ├── DeepResearchService.java           # Plan → Search → Synthesise
│   │   ├── EmbeddingService.java              # Ollama embeddings + cosine similarity
│   │   ├── FunctionCallService.java           # 4 built-in AI tools
│   │   ├── ImageGenerationService.java        # DALL-E 3 + Stable Diffusion
│   │   ├── ModelService.java                  # Model access control
│   │   ├── MonitorService.java                # Request/error counters
│   │   ├── OllamaService.java                 # @Scheduled model sync
│   │   ├── PromptTemplateService.java         # Per-mode system prompts
│   │   ├── ProviderService.java               # Multi-provider AI dispatch
│   │   ├── RagService.java                    # Chunk, embed, retrieve
│   │   ├── RouterService.java                 # Auto Router scoring + privacy gate
│   │   ├── SsoService.java                    # Google OAuth, OIDC, SAML 2.0
│   │   ├── TerminalService.java               # WebSocket terminal
│   │   ├── UserService.java                   # Permission resolution
│   │   ├── VoiceService.java                  # Whisper STT + TTS-1
│   │   ├── WebSearchService.java              # Serper / Brave / SearXNG
│   │   └── WorkspaceService.java              # File I/O, artifact extraction
│   └── util/
│       └── UrlValidator.java                  # SSRF protection
├── src/main/resources/
│   ├── application.properties
│   └── db/migration/
│       ├── V1__init.sql
│       ├── V3__connectors.sql
│       ├── V4__sso.sql
│       └── V5__search_images.sql
├── public/                                    # Frontend (HTML / CSS / JS)
│   ├── app.html / app.js                      # User workspace
│   ├── admin.html / admin.js                  # Admin dashboard
│   ├── login.html / login.js                  # Login page
│   ├── styles.css                             # All styles
│   └── vendor/                               # marked, highlight.js, DOMPurify, xterm, chart.js
└── data/                                      # Runtime data (gitignored)
    ├── olla-nest.sqlite
    └── backups/                               # 7-rotation daily backups
```

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for full version history.

---

## License

MIT — see [LICENSE](LICENSE)
