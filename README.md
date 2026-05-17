# Olla Nest

**Company-ready AI workspace — local models, cloud providers, and full admin control.**  
**Current release: [v2026.0.10](https://github.com/ashokramcse/olla-nest/releases/tag/v2026.0.10)**

Olla Nest gives teams a private, admin-controlled AI workspace. Employees type once and the system automatically routes each request to the best available model — whether that is a local Ollama model on your own hardware or a cloud provider like Anthropic or OpenAI. No employee needs to understand model names or manage API keys.

---

## What Makes Olla Nest Different

| Feature | How it works |
|---|---|
| **Auto Router** | Classifies every request and picks the best approved model by capability, speed, quality, and privacy score |
| **Multi-provider** | Ollama (local) + Anthropic, OpenAI, Groq, and any OpenAI-compatible endpoint |
| **Chat memory** | Sliding-window context history — the model remembers everything said in the current session |
| **File upload** | Attach images (vision models) or text files directly in the chat composer |
| **Admin control** | Manage which employees, groups, or departments access which models |
| **Local-first** | Ollama runs on your own hardware — zero data leaves your network |
| **Cloud-optional** | Add a provider key in Admin → Providers to unlock cloud models |
| **Audit trail** | Every routing decision and admin action is logged |
| **Reports** | 10 interactive charts — token leaderboard, latency by model, dept usage, live vs failed |
| **Enterprise profiles** | Employee fields (designation, team, branch, manager), SSO-compatible field locking |
| **Invite workflow** | Auto-generated credentials shown on employee creation with copy support |

---

## Requirements

- [Docker](https://docs.docker.com/get-docker/) and Docker Compose v2
- [Ollama](https://ollama.com) running on the host (for local models), **or** an API key from Anthropic / OpenAI / Groq (for cloud models)

Olla Nest is **Docker-only**. No `npm start` or local `node server.js`. Docker is the runtime for every environment — dev, staging, production.

---

## Quick Start

```bash
git clone https://github.com/ashokramcse/olla-nest.git
cd olla-nest
cp .env.example .env
docker compose up --build
```

Open **http://localhost:3000** — you will be redirected to the login page.

**Default admin credentials (first boot only):**

| Field    | Value                             |
|----------|-----------------------------------|
| Email    | `admin@ollanest.local`            |
| Password | `CHANGE_ME_ON_FIRST_BOOT` |

> ⚠️ Change the admin password immediately after first login via **Admin → Users → Edit** or set `DEFAULT_ADMIN_PASSWORD` in `.env` before first boot.

---

## Ollama Setup (Local Models)

Ollama runs on the host machine. The Docker container reaches it via `host.docker.internal`.

| Platform | What to do |
|---|---|
| **macOS / Windows** (Docker Desktop) | Works out of the box — no extra config |
| **Linux** | `host.docker.internal` is set via `extra_hosts` in `docker-compose.yml` — works automatically |
| **Remote machine** | Set `OLLAMA_URL=http://192.168.x.x:11434` in `.env` |

Pull at least one model before starting:

```bash
ollama pull gemma3:4b        # fast, 4 GB — good default
ollama pull qwen2.5:7b       # great all-rounder
ollama pull llama3.1:8b      # strong reasoning, 128k context
```

---

## Cloud Provider Setup (Optional)

To use Anthropic, OpenAI, Groq, or any OpenAI-compatible endpoint:

1. Open **Admin → Providers**
2. Click **Add Provider**, choose your type, enter your API key
3. Click **Sync Models** — Olla Nest calls the provider's real model list API (no hardcoded lists)
4. **Approve** the models you want employees to access
5. Approved models immediately appear in the Auto Router and model picker — no restart needed

---

## Configuration

All configuration lives in `.env`. Copy `.env.example` and edit:

```env
DEFAULT_ADMIN_EMAIL=admin@yourcompany.com
DEFAULT_ADMIN_PASSWORD=replace-with-a-strong-password
DEFAULT_USER_PASSWORD=replace-with-employee-default
OLLAMA_URL=http://host.docker.internal:11434
SECRET_KEY=replace-with-a-random-64-char-hex-string
SESSION_SECRET=replace-with-random-string
```

Restart after changing `.env`:

```bash
docker compose down && docker compose up --build
```

---

## Docker Commands

| Command | Description |
|---|---|
| `docker compose up --build` | Build image and start |
| `docker compose up -d --build` | Build and start in background |
| `docker compose down` | Stop and remove containers (data persists) |
| `docker compose down -v` | Stop **and delete all data** (destructive) |
| `docker compose logs -f app` | Stream app logs |
| `docker compose restart app` | Restart without rebuild |

---

## App Routes

| Route | Who | Description |
|---|---|---|
| `/login` | Employees | Employee sign-in |
| `/admin-login` | Admins | Admin-only sign-in (rejects non-admin accounts) |
| `/app` | Employees | AI workspace |
| `/admin` | Admins | Admin dashboard |

---

## Employee Workspace (`/app`)

| Feature | Details |
|---|---|
| **Chat** | Type anything — Auto Router picks the best approved model automatically |
| **Chat memory** | The model remembers the full current session via sliding-window history |
| **File upload** | Attach images (sent to vision models as base64) or text files (appended as code blocks) |
| **Model picker** | Override Auto Router with a specific model via the composer dropdown |
| **Mode buttons** | Ask · Build · Review · Fix · Learn · Debug · Test · Docs · Plan |
| **Workspace** | Configure a local project folder — generated files are saved there automatically |
| **Chat history** | Browse, resume, pin, archive, fork past chat threads |
| **Profile** | Edit name, phone, designation, team, branch; change password |

---

## Admin Dashboard (`/admin`)

| Tab | What you can do |
|---|---|
| **Overview** | Live stats (models, users, groups), audit event feed, quick actions |
| **Chat** | Admin test chat with full router panel, model streaming, markdown rendering |
| **Models** | Sync Ollama models; set governance tier, resource tier, speed/quality scores |
| **Users** | Create employees; set department, team, role, AI access tier, token limits; inline edit panel; permission grid with colour-coded risk groups |
| **Access Control** | Department permission defaults, RBAC role catalog, per-user permission matrix |
| **Settings** | Auto Router toggle, local write config, workspace root, API model access, governance defaults |
| **Providers** | Ollama status + model pills; configure Anthropic/OpenAI/Groq/custom; sync real model lists; approve individual models |
| **Reports** | 10 interactive Chart.js charts + paginated token leaderboard |

---

## Auto Router

The router runs on every chat message:

1. **Classify** — detects request type (coding, writing, reasoning, OCR, medical, general…)
2. **Authorise** — finds which models the user can access via user, group, and department grants
3. **Score** — ranks each candidate by capability match × speed × quality × privacy weight
4. **Select** — picks the highest-scoring approved available model
5. **Fallback** — if no model is available, returns a clear error with configuration guidance
6. **Privacy enforcement** — SSN, credit card, PHI, or API key patterns detected → local-only routing regardless of user preferences

The routing decision (selected model, reason, candidate scores) is shown in the **Auto Router** panel on the right side of the workspace.

---

## Chat Context — How Memory Works

Every chat session's history is stored in SQLite. Before each model call:

1. The system prompt is built (instructions + workspace context + mode)
2. All prior messages for the session are loaded from the database
3. A **sliding window** algorithm trims the history to fit the model's context window:
   - Walks messages **newest → oldest**
   - Keeps messages until the token budget runs out
   - Budget = model context limit − system prompt tokens − new message tokens − 512 buffer
4. The final array sent to the model: `[system, ...history, new user message]`

Context limits are looked up dynamically — from Ollama's `/api/show` for local models, from `api_models.context_window` for cloud providers. No hardcoded model names.

---

## Security

| Control | Details |
|---|---|
| Session cookies | `HttpOnly`, `SameSite=Lax`, `Secure` (HTTPS), 12-hour expiry |
| Login rate limiting | 10 failed attempts per IP per 15 minutes |
| CSRF protection | `X-Requested-With` header required on all state-changing requests |
| HTTP security headers | `X-Frame-Options`, `X-Content-Type-Options`, `X-XSS-Protection`, `Referrer-Policy`, CSP, `Permissions-Policy` |
| Input validation | 16,000 char max on messages; 512 KB max JSON body |
| Password hashing | bcrypt cost factor 12 |
| API key encryption | AES-256-GCM for all stored provider keys |
| XSS sanitisation | AI output sanitised with DOMPurify before rendering |
| Path traversal | File writes restricted to workspace root |
| Sensitive content | SSN, credit card, PHI patterns → force local-only routing |

---

## Data Storage

SQLite inside a Docker volume — no external database required.

| What | Where | Contains |
|---|---|---|
| Database | `/app/data/olla-nest.sqlite` | Users, roles, permissions, departments, models, chats, audit, router traces, provider config |
| Volume | `app-data` (named Docker volume) | Persists across restarts |
| Workspace | Configurable path (bind mount) | Generated files from Build mode |

---

## Project Structure

```text
server.js               Express backend — all API routes, auth, routing engine, Ollama sync, SSE streaming
package.json            Metadata and npm scripts
public/
  login.html / login.js         Employee sign-in page
  app.html   / app.js           Employee workspace SPA
  admin.html / admin.js         Admin dashboard SPA (~3,700 + ~1,300 lines)
  dropdown.js                   Shared dropdown utility
  styles.css                    Design system
data/                   Runtime only — gitignored
  olla-nest.sqlite
  workspace/
docs/
  ARCHITECTURE.md               System design and component diagram
  ENTERPRISE_ACCESS_CONTROL.md  RBAC, permission groups, department defaults
  DEPLOYMENT.md                 Production deployment guide
CHANGELOG.md            Full version history
VERSION.md              Version tracker with per-commit log
CONTRIBUTING.md         Contribution guide
docker-compose.yml      App service with volume and host routing
Dockerfile              Node 24 Alpine image
.env.example            Environment variable reference
```

---

## Production Deployment

Set real credentials before first boot:

```env
DEFAULT_ADMIN_EMAIL=admin@yourcompany.com
DEFAULT_ADMIN_PASSWORD=replace-with-a-strong-32-char-password
DEFAULT_USER_PASSWORD=replace-with-a-strong-default
OLLAMA_URL=http://your-ollama-server:11434
SECRET_KEY=replace-with-64-char-hex
SESSION_SECRET=replace-with-random-string
```

```bash
docker compose up -d --build
```

Put Nginx or Caddy in front for HTTPS and a custom domain. See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for a full guide.

---

## Roadmap

- [ ] Real SSO / LDAP / SAML integration
- [ ] RAG document knowledge base
- [ ] Department and group policy editor UI
- [ ] Usage analytics dashboard for employees
- [ ] Visual diff for generated file changes
- [ ] Desktop app (Tauri) for macOS, Windows, Linux
- [ ] Mobile PWA
- [ ] Team-based workspace folders
- [ ] Webhook / notification support for admin alerts
- [ ] Conversation summarisation for ultra-long sessions

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Keep contributions:
- Local-first and self-hostable
- Simple to run (Docker only)
- Safe for employees (no data exfiltration)
- Transparent about routing decisions

---

## Links

[Architecture](docs/ARCHITECTURE.md) · [Enterprise Access Control](docs/ENTERPRISE_ACCESS_CONTROL.md) · [Deployment](docs/DEPLOYMENT.md) · [Changelog](CHANGELOG.md) · [Version History](VERSION.md) · [Contributing](CONTRIBUTING.md) · [Releases](https://github.com/ashokramcse/olla-nest/releases)
