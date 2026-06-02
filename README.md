<div align="center">

<img src="docs/logo-readme.svg" alt="Olla Nest" width="480" />

### Your AI. Your rules. Your hardware.

**The company AI workspace that routes every request to the right model automatically —**  
**local Ollama models, cloud providers, or both. Zero lock-in. Full admin control.**

<br/>

[![Version](https://img.shields.io/badge/version-v2026.2.1-f5c842?style=for-the-badge&logo=git&logoColor=black)](https://github.com/ashokramcse/olla-nest/releases)
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
- **Brute-force protection** — IP-based lockout: 10 attempts / 15 min (DB-persisted, survives restarts)
- **BCrypt DoS prevention** — email > 320 chars or password > 1024 chars rejected before hashing
- **Constant-time auth** — dummy BCrypt sentinel prevents user-enumeration via timing
- **SSO bypass prevention** — `auth_provider='local'` enforced at login; SSO users cannot use password login
- **SQL injection prevention** — enum guards, LIMIT bounds checks, table-name allow-lists throughout
- **XSS prevention** — `BaseController.sanitizeText()` with `HtmlUtils.htmlEscape` on all user text
- **SSRF protection** — private and loopback IPs blocked on all cloud provider URLs
- **Terminal auth** — WebSocket requires authenticated session + `workspace:build` right
- **Security headers** — CSP, `Strict-Transport-Security`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Permissions-Policy`
- **MDC structured logging** — `requestId`, `userId`, `userRole`, `ip` on every log line
- **BCrypt** — password hashing at cost factor 12
- **Session invalidation** — role/permission changes immediately expire all user sessions
- **SOC 2 audit trail** — `auth.login`, `auth.login.failed`, `chat.request` events with IP in `audit_events`

</td>
</tr>
<tr>
<td width="50%" valign="top">

**👥 Enterprise**
- **RBAC** — admin and user roles with fine-grained per-right permissions
- **Teams hierarchy** — departments → groups → teams with inherited permission sets
- **Per-user limits** — daily token cap, request rate limits, model tier access
- **Model governance** — GPU requirement, sensitivity tier, model-level caps, access expiry
- **Full audit log** — every request, model used, tokens consumed, timestamp, per-user; SOC 2 compliant
- **Access expiry** — expired accounts blocked at login; admin can revoke instantly
- **20+ connectors** — GitHub, Slack, Notion, Jira, Google Drive, Salesforce, and 14 more
- **Hourly connector sync** — SHA-256 dedup; only changed documents are re-ingested
- **Admin reports** — daily activity, token leaderboard, model usage, latency, department breakdown
- **Health dashboard** — JVM memory, DB stats, uptime, request / error counters
- **1,559 automated tests** — unit, Mockito, MockMvc integration tests; SOC 2 + SQL hardening coverage

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
- **SQLite backups** — `@Scheduled` daily backup with 7-file rotation; concurrent-safe `AtomicBoolean` guard; zero-config
- **Flyway migrations** — V1–V6 schema applied automatically on startup

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

### 3 — Run both services

```bash
# Admin control panel — http://localhost:8080
java --enable-native-access=ALL-UNNAMED -jar olla-nest-admin/target/olla-nest-admin-2026.1.9.jar &

# Employee workspace — http://localhost:8081
java --enable-native-access=ALL-UNNAMED -jar olla-nest-user/target/olla-nest-user-2026.1.9.jar &
```

Or run both from Maven:

```bash
# From the project root (runs admin on 8080):
mvn spring-boot:run -pl olla-nest-admin --enable-native-access=ALL-UNNAMED

# In a second terminal (runs user on 8081):
mvn spring-boot:run -pl olla-nest-user --enable-native-access=ALL-UNNAMED
```

Open **http://localhost:8080** for the Admin panel · **http://localhost:8081** for the Employee workspace.

### Running from Eclipse

1. **File → Import → Maven → Existing Maven Projects** → select the project folder
2. Right-click `OllaNestAdminApplication.java` → **Run As → Run Configurations**
3. Add environment variables in the **Environment** tab; click **Run**
4. Repeat for `OllaNestUserApplication.java`

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
| Password hashing | BCrypt cost factor 12; cost 10 for constant-time dummy sentinel |
| Constant-time auth | Dummy BCrypt hash always evaluated; prevents user-enumeration via timing |
| BCrypt DoS prevention | Email > 320 chars or password > 1024 chars rejected before hash (HTTP 400) |
| SSO bypass prevention | `AND auth_provider = 'local'` enforced on login query; SSO users cannot use password path |
| API key / secret encryption | AES-256-GCM, random 12-byte IV per value |
| Connector credential encryption | AES-256-GCM in `connector_configs.credentials_enc` |
| SSO client secret encryption | AES-256-GCM in `sso_providers.client_secret_enc` |
| Login rate limiting | IP-based: 10 attempts / 15 min, DB-persisted (`login_attempts` table), survives restarts |
| SQL injection prevention | `ORDER BY` enum guard, `LIMIT` bounds check (1–500), table-name allow-list, parameterised queries |
| XSS prevention | `sanitizeText()` via `HtmlUtils.htmlEscape` on all user-supplied text before persistence |
| SSRF protection | Cloud provider URLs — private/loopback IPs blocked; self-hosted URLs (SD, SearXNG) allow localhost |
| System path protection | `workspaceRoot` setting blocked from pointing to `/etc`, `/bin`, `/proc`, `/sys`, `/dev`, `C:\Windows` |
| Path traversal | Workspace browse restricted to user home directory |
| Terminal auth | WebSocket requires authenticated session + `workspace:build` right |
| Security headers | `Content-Security-Policy`, `X-Frame-Options: DENY`, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy` on all responses |
| HSTS | `Strict-Transport-Security: max-age=31536000` (HTTPS only) |
| MDC structured logging | `requestId`, `userId`, `userEmail`, `userRole`, `method`, `path`, `ip` on every log line |
| Audit trail (SOC 2) | `auth.login`, `auth.login.failed` (with actor IP), `chat.request` written to `audit_events` table |
| SSO CSRF | State nonce stored in `oauth_state` table, validated on OAuth callback |
| CSRF protection | `X-Requested-With` header required on all state-changing endpoints |
| Sensitive content routing | SSN, credit card, API key, PHI regex detection blocks external providers |
| Admin session invalidation | Changing a user's role or permissions immediately invalidates their sessions |
| Concurrent backup guard | `AtomicBoolean` CAS — only one `VACUUM INTO` runs at a time; concurrent requests rejected |
| Sandbox isolation | Subprocess stripped env, temp working dir, 10 s SIGKILL, 4 KB output cap |

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ADMIN_PORT` | `8080` | Admin control panel HTTP port |
| `USER_PORT` | `8081` | Employee workspace HTTP port |
| `ENCRYPTION_KEY` | *(required)* | AES key for encrypting API keys, SSO secrets, connector credentials |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama server base URL |
| `DATA_DIR` | `./data` | SQLite database and backup directory |
| `DEFAULT_ADMIN_EMAIL` | `admin@ollanest.local` | Admin email seeded on first boot |
| `DEFAULT_ADMIN_PASSWORD` | *(auto-generated)* | Leave blank — secure password printed to console on first boot |
| `DEFAULT_USER_PASSWORD` | `CHANGE_ME_ON_FIRST_BOOT` | Default password for newly created user accounts |
| `COOKIE_SECURE` | `false` | Set `true` behind HTTPS reverse proxy |
| `TRUSTED_PROXY` | *(empty)* | Trusted proxy IP for `X-Forwarded-For` rate-limit resolution |
| `APP_BASE_URL` | `http://localhost:8080` | SSO redirect_uri base URL |

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
POST   /api/admin/settings/backup         # trigger immediate VACUUM INTO backup
GET/POST/DELETE /api/admin/teams/departments
GET/POST/DELETE /api/admin/teams/groups
GET/POST/DELETE /api/admin/teams/teams
```

---

## Database Schema

SQLite in WAL mode (`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000`), managed by Flyway — all 6 migrations applied automatically on startup. 30 tables, 26 indexes.

| Migration | Purpose |
|-----------|---------|
| `V1__init.sql` | Core schema: `users`, `sessions`, `settings`, `departments`, `groups`, `teams`, `models`, `api_providers`, `chat_sessions`, `chat_messages`, `audit_events`, `login_attempts`, `router_traces`, `feedback`, and more |
| `V2__rag.sql` | `rag_documents`, `rag_chunks` — document knowledge base |
| `V3__connectors.sql` | `connector_configs`, `connector_sync_log`, `connector_documents` — 20 data-source connectors |
| `V4__sso.sql` | `sso_providers`, `oauth_state` — Google OAuth, OIDC, SAML 2.0 |
| `V5__search_images.sql` | Settings columns for search / image generation / voice |
| `V6__performance_indexes.sql` | 17 composite indexes for audit, router trace, RAG, chat, and session queries |

---

## Project Structure

Maven multi-module project — three modules built from a single parent POM.

```
olla-nest/
├── pom.xml                                         # Parent POM — Spring Boot 3.5.14, Spring AI 1.0.0
│
├── olla-nest-common/                               # Shared library (JAR) — all services, models, connectors
│   └── src/main/java/com/ollanest/
│       ├── config/                                 # Spring config beans (AppConfig, SecurityConfig, WebSocket)
│       ├── connector/                              # 20 data-source connectors
│       │   ├── BaseConnector.java                  # HTTP, SHA-256 dedup, RAG wiring
│       │   ├── ConnectorRegistry.java              # Type → impl dispatch
│       │   ├── ConnectorSyncScheduler.java         # Hourly @Scheduled sync
│       │   └── impl/                               # AirtableConnector … ZendeskConnector
│       ├── controller/                             # Shared REST controllers (Auth, Bootstrap, Chat, …)
│       │   └── BaseController.java                 # requireAuth/requireAdmin/sanitizeText helpers
│       ├── filter/
│       │   ├── MdcLoggingFilter.java               # Per-request MDC: requestId, userId, role, ip
│       │   ├── SessionAuthFilter.java              # Reads cookie → attaches User to request
│       │   └── SecurityHeadersFilter.java          # CSP, HSTS, X-Frame-Options, Referrer-Policy
│       ├── model/                                  # User, ChatSession, ChatMessage POJOs
│       ├── service/
│       │   ├── AuthService.java                    # Sessions (ConcurrentHashMap + DB), BCrypt
│       │   ├── BackupService.java                  # @Scheduled VACUUM INTO, AtomicBoolean guard
│       │   ├── ChatService.java                    # Context assembly, system prompts, audit events
│       │   ├── CodeSandboxService.java             # ProcessBuilder isolated execution
│       │   ├── CryptoService.java                  # AES-256-GCM
│       │   ├── DatabaseService.java                # Flyway V1–V6 + seed data
│       │   ├── DeepResearchService.java            # Plan → Search → Synthesise pipeline
│       │   ├── EmbeddingService.java               # Ollama embeddings + cosine similarity
│       │   ├── FunctionCallService.java            # 4 built-in AI tools
│       │   ├── ImageGenerationService.java         # DALL-E 3 + Stable Diffusion
│       │   ├── ModelService.java                   # Model access control
│       │   ├── MonitorService.java                 # Request/error counters
│       │   ├── OllamaService.java                  # @Scheduled 60-second model sync
│       │   ├── PromptTemplateService.java          # Per-mode system prompts
│       │   ├── ProviderService.java                # Multi-provider AI dispatch (Ollama, Claude, OpenAI, Groq)
│       │   ├── RagService.java                     # Chunk, embed, retrieve
│       │   ├── RouterService.java                  # Auto Router scoring + privacy gate
│       │   ├── SsoService.java                     # Google OAuth, OIDC, SAML 2.0
│       │   ├── UserService.java                    # Permission resolution, publicUser()
│       │   ├── VoiceService.java                   # Whisper STT (local + OpenAI) + TTS-1
│       │   ├── WebSearchService.java               # Serper / Brave / SearXNG
│       │   ├── WhisperServerManager.java           # Auto-starts faster-whisper on port 8765
│       │   └── WorkspaceService.java               # File I/O, artifact extraction
│       └── util/
│           └── UrlValidator.java                   # SSRF protection — blocks private/loopback IPs
│   └── src/main/resources/db/migration/
│       ├── V1__init.sql                            # Core schema (30 tables)
│       ├── V2__rag.sql                             # rag_documents, rag_chunks
│       ├── V3__connectors.sql                      # connector_configs, sync_log, documents
│       ├── V4__sso.sql                             # sso_providers, oauth_state
│       ├── V5__search_images.sql                   # Search / image / voice settings columns
│       └── V6__performance_indexes.sql             # 17 composite indexes
│
├── olla-nest-admin/                                # Admin control panel Spring Boot app (port 8080)
│   └── src/main/java/com/ollanest/controller/admin/
│       ├── AdminUserController.java                # User CRUD, sessions, overrides, effective-access
│       ├── AdminSettingsController.java            # Settings, departments, backup trigger
│       ├── AdminReportsController.java             # Analytics, feedback
│       ├── AdminModelsController.java              # Model governance (status allow-list)
│       ├── AdminProvidersController.java           # Provider CRUD, model approval
│       ├── AdminConnectorController.java           # Connector CRUD, sync, test, logs
│       ├── AdminTeamsController.java               # Teams CRUD
│       └── AdminHealthController.java              # Health check, DB stats, JVM info
│
├── olla-nest-user/                                 # Employee workspace Spring Boot app (port 8081)
│   └── src/main/java/com/ollanest/controller/
│       ├── ChatController.java                     # POST /api/chat/stream (SSE), /clear, /feedback
│       ├── ThreadController.java                   # GET/DELETE /api/threads
│       ├── AccountController.java                  # Profile, usage, password change
│       ├── DocumentController.java                 # RAG document upload/delete
│       ├── WorkspaceController.java                # File browse, read, write
│       ├── VoiceController.java                    # STT transcribe, TTS speak
│       ├── ImageController.java                    # DALL-E 3 / Stable Diffusion
│       ├── CodeSandboxController.java              # Code execution sandbox
│       └── SsoController.java                     # SSO authorize, callback, SAML ACS
│
├── public/                                         # Frontend static files (HTML / CSS / JS)
│   ├── app.html / app.js                           # Employee workspace SPA
│   ├── admin.html / admin.js                       # Admin dashboard SPA
│   ├── login.html / login.js / admin-login.*       # Login pages
│   ├── styles.css                                  # All styles
│   └── vendor/                                     # marked, highlight.js, DOMPurify, xterm, chart.js
│
├── scripts/
│   ├── whisper_server.py                           # OpenAI-compatible faster-whisper HTTP server (port 8765)
│   ├── start_whisper.sh / .bat / .ps1             # One-time venv setup (macOS / Linux / Windows)
│   └── monitoring/                                 # Grafana + Loki stack (optional)
│
└── data/                                           # Runtime data (gitignored)
    ├── olla-nest.sqlite                            # Main SQLite database (WAL mode)
    └── backups/                                    # 7-rotation daily VACUUM INTO backups
```

---

## Test Coverage

Unit tests are written with **JUnit 5** and **Mockito** — no Spring context is loaded, keeping tests fast and focused on service logic.

### Service tests

| Service | Test class |
|---------|-----------|
| `ApiTokenService` | `ApiTokenServiceTest` |
| `AuthService` | `AuthServiceTest` |
| `ChatService` | `ChatServiceTest` |
| `CryptoService` | `CryptoServiceTest` |
| `DatabaseService` | `DatabaseServiceTest` |
| `MemoryService` | `MemoryServiceTest` |
| `ModelService` | `ModelServiceTest` |
| `NotesService` | `NotesServiceTest` |
| `PromptSecurityService` | `PromptSecurityServiceTest` |
| `RateLimiterService` | `RateLimiterServiceTest` |
| `RouterService` | `RouterServiceTest` |
| `SkillsService` | `SkillsServiceTest` |
| `Soc2AuditService` | `Soc2AuditTest` |
| `SqlSafetyService` | `SqlSafetyTest` |

### Filter tests

| Filter | Test class |
|--------|-----------|
| `MdcLoggingFilter` | `MdcLoggingFilterTest` |
| `SecurityHeadersFilter` | `SecurityHeadersFilterTest` |
| `SessionAuthFilter` | `SessionAuthFilterTest` |

### Controller tests

| Controller | Test class |
|------------|-----------|
| `AuthController` | `AuthControllerTest` |
| `GlobalExceptionHandler` | `GlobalExceptionHandlerTest` |

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for full version history.

---

## License

MIT — see [LICENSE](LICENSE)
