<div align="center">

<img src="docs/logo-readme.svg" alt="Olla Nest" width="480" />

### Your AI. Your rules. Your hardware.

**The company AI workspace that routes every request to the right model automatically —**  
**local Ollama models, cloud providers, or both. Zero lock-in. Full admin control.**

<br/>

[![Version](https://img.shields.io/badge/version-v2026.1.2-f5c842?style=for-the-badge&logo=git&logoColor=black)](https://github.com/ashokramcse/olla-nest/releases)
[![License](https://img.shields.io/badge/license-MIT-22c55e?style=for-the-badge)](LICENSE)
[![Java](https://img.shields.io/badge/Java-26-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.3-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![SQLite](https://img.shields.io/badge/SQLite-WAL_mode-003B57?style=for-the-badge&logo=sqlite&logoColor=white)](https://sqlite.org)

<br/>

[**Quick Start**](#quick-start) · [**Features**](#features) · [**Auto Router**](#auto-router) · [**Admin Dashboard**](#admin-dashboard) · [**Providers**](#cloud-provider-setup) · [**Security**](#security) · [**Docs**](docs/) · [**Changelog**](CHANGELOG.md)

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
<td width="50%">

**🤖 Intelligence**
- Auto Router — classifies every request, picks the best model
- Multi-provider: Ollama, Anthropic, OpenAI, Groq, custom
- Streaming SSE responses with real-time token output
- Privacy gate — sensitive content never routed externally
- Configurable router weights (speed / quality / privacy)
- Per-mode local-only enforcement (build, fix)

</td>
<td width="50%">

**🔐 Security**
- Cookie-based session auth (HttpOnly, SameSite=Lax)
- AES-256-GCM encrypted API keys at rest
- IP brute-force protection on login
- SSRF protection on all provider URLs
- WebSocket terminal requires authentication
- Content-Security-Policy + HSTS headers

</td>
</tr>
<tr>
<td width="50%">

**👥 Enterprise**
- Role-based access control (admin / user)
- Departments, groups, teams
- Per-user daily token limits and rate limits
- Model governance (tier, GPU, sensitivity)
- Full audit log
- User access expiry enforcement

</td>
<td width="50%">

**🛠 Developer**
- 8 chat modes: ask, build, fix, review, debug, test, docs, plan
- Local workspace integration — AI writes files directly
- Interactive terminal (WebSocket / ProcessBuilder)
- Workspace file browser with path safety
- Automated SQLite backups (7-rotation)
- Flyway schema migrations

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

> **First-boot password:** Leave `DEFAULT_ADMIN_PASSWORD` unset or as `CHANGE_ME_ON_FIRST_BOOT`. The server will generate a secure random password and print it once to the console on first startup.

### 2 — Build

```bash
mvn clean package -DskipTests
```

### 3 — Run

```bash
java -jar target/olla-nest-*.jar
```

Or with explicit env vars:

```bash
ENCRYPTION_KEY=my-secret OLLAMA_URL=http://localhost:11434 java -jar target/olla-nest-*.jar
```

Open **http://localhost:3000** — the login page will appear.

### Running from Eclipse

1. **File → Import → Maven → Existing Maven Projects** → select the project folder
2. Right-click `OllaNestApplication.java` → **Run As → Run Configurations**
3. Add environment variables in the **Environment** tab
4. Click **Run**

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for full deployment guide.

---

## Auto Router

Every message is classified into capability tags (coding, debugging, writing, medical, vision, etc.) and scored against all approved models:

```
score = capabilityMatch(35) + specialistBonus(45) + speedScore + qualityScore + privacyScore
```

**Privacy gate:** If a message contains SSNs, credit cards, API keys, or medical terms — or the mode is `build`/`fix` — external providers are removed from the candidate pool automatically. Sensitive content stays local.

**Manual override:** Users can bypass the router and select any model they are approved for from the composer dropdown.

---

## Admin Dashboard

| Section | What you control |
|---------|-----------------|
| **Users** | Create, edit, deactivate, set roles, reset passwords, view effective access |
| **Models** | Governance tier, GPU requirement, sensitivity policy, per-model caps |
| **Providers** | Add Anthropic / OpenAI / Groq / custom endpoints with encrypted API keys |
| **Settings** | Ollama URL, router weights, local-only modes, sensitive content patterns, workspace |
| **Teams** | Departments, groups, teams with default permission sets |
| **Reports** | Daily activity, model usage, token leaderboard, latency, department breakdown |
| **Health** | DB stats, JVM memory, uptime, request counters |

---

## Cloud Provider Setup

Go to **Admin → Providers → Add Provider** to connect any cloud AI:

| Provider | Type | Notes |
|----------|------|-------|
| Anthropic Claude | `anthropic` | Requires API key from console.anthropic.com |
| OpenAI | `openai` | Requires API key from platform.openai.com |
| Groq | `groq` | Requires API key from console.groq.com |
| Custom / LiteLLM | `custom` | Any OpenAI-compatible endpoint |

All API keys are encrypted with AES-256-GCM before storage. Keys are never returned to the browser after saving.

---

## Security

| Feature | Implementation |
|---------|---------------|
| Session tokens | 256-bit `SecureRandom` hex, HttpOnly cookie, SameSite=Lax |
| Password hashing | BCrypt cost factor 12 |
| API key encryption | AES-256-GCM, random 12-byte IV per value |
| Login protection | IP-based rate limiting (5 attempts / 15 min) |
| SSRF protection | All provider URLs validated — private IPs blocked |
| Path traversal | Workspace browse restricted to user home directory |
| Terminal auth | WebSocket requires authenticated session + `workspace:build` right |
| CSP | `Content-Security-Policy` header on all responses |
| HSTS | `Strict-Transport-Security` header |
| Sensitive content | Built-in SSN, credit card, API key, PHI detection blocks external routing |

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `3000` | HTTP port |
| `ENCRYPTION_KEY` | *(required)* | AES key for encrypting stored API keys |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama server URL |
| `DATA_DIR` | `./data` | Directory for SQLite DB and backups |
| `STATIC_DIR` | `./public` | Directory for static frontend files |
| `DEFAULT_ADMIN_EMAIL` | `admin@ollanest.local` | Admin email seeded on first boot |
| `DEFAULT_ADMIN_PASSWORD` | *(auto-generated)* | Set to override; leave blank to auto-generate |
| `COOKIE_SECURE` | `false` | Set `true` when running behind HTTPS |
| `TRUSTED_PROXY` | *(empty)* | Trusted proxy IP for X-Forwarded-For |

---

## Project Structure

```
olla-nest/
├── pom.xml                              # Maven build (Spring Boot 3.3.5)
├── src/
│   └── main/
│       ├── java/com/ollanest/
│       │   ├── OllaNestApplication.java
│       │   ├── config/                  # Spring config (Security, WebSocket, Web)
│       │   ├── controller/              # REST controllers (all API endpoints)
│       │   │   └── admin/               # Admin-only controllers
│       │   ├── filter/                  # Auth filter, security headers filter
│       │   ├── service/                 # Business logic (auth, chat, AI providers, etc.)
│       │   ├── model/                   # POJO models
│       │   └── util/                    # URL validator, helpers
│       └── resources/
│           ├── application.properties   # App configuration
│           └── db/migration/
│               └── V1__init.sql         # Full schema (Flyway managed)
├── public/                              # Frontend (HTML/CSS/JS — unchanged)
│   ├── app.html / app.js                # User workspace
│   ├── admin.html / admin.js            # Admin dashboard
│   ├── login.html / login.js            # Login page
│   ├── styles.css                       # All styles
│   └── vendor/                          # marked, highlight.js, DOMPurify, xterm, chart
└── data/                                # Runtime data (gitignored)
    ├── olla-nest.sqlite                 # SQLite database
    └── backups/                         # Automated daily backups
```

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for full version history.

---

## License

MIT — see [LICENSE](LICENSE)
