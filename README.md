# Olla Nest

**Company-ready local AI workspace built on Ollama.**  
**Current release: [v2026.0.1.mvp](https://github.com/ashokramcse/olla-nest/releases/tag/v2026.0.1.mvp)**

Olla Nest gives teams a private, admin-controlled AI workspace where employees type once and the system automatically routes each request to the best approved local model — with no cloud dependency, no data leaving the network, and no employee needing to understand model names.

---

## What Makes Olla Nest Different

Most AI dashboards make users choose the model. Olla Nest adds a company control layer on top of Ollama:

- **Auto Router** — analyses every request and picks the best approved model automatically
- **Admin control** — manage which employees, groups, or departments access which models
- **Local-first** — all AI runs on your infrastructure via Ollama; no API keys, no external calls
- **Local file output** — generated code files are saved directly to a company workspace folder
- **Audit trail** — every routing decision and admin action is logged
- **Reports & analytics** — 10 interactive charts, token usage leaderboard, latency tracking
- **User profiles** — employee self-service profile with enterprise field-locking for SSO accounts
- **Teams management** — create and assign teams with inline team creation in user creation flow
- **Invite workflow** — auto-generated credentials shown on employee creation with copy support

---

## Requirements

- [Docker](https://docs.docker.com/get-docker/) and Docker Compose v2
- [Ollama](https://ollama.com) running on the host machine with at least one model pulled

Olla Nest is **Docker-only**. The app intentionally does not support `npm start`, `node server.js`, or a local frontend dev server on the host machine. Docker is the product runtime, so every user, contributor, and deployment follows the same path.

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

> ⚠️ Change the admin password immediately after first login via the avatar pill → **My Profile** or set `DEFAULT_ADMIN_PASSWORD` in `.env` before first boot.

---

## Ollama Setup

Ollama must run on the host machine. The Docker container reaches it via `host.docker.internal`.

**macOS / Windows Docker Desktop** — works out of the box. No extra config needed.

**Linux** — `extra_hosts: host.docker.internal:host-gateway` is already set in `docker-compose.yml`. Works automatically.

**Ollama on a different machine** — set `OLLAMA_URL` in `.env`:

```env
OLLAMA_URL=http://192.168.1.50:11434
```

Pull at least one model before starting:

```bash
ollama pull qwen2.5:7b
```

---

## Configuration

All configuration lives in `.env`. Copy `.env.example` and edit:

```env
DEFAULT_ADMIN_EMAIL=admin@yourcompany.com
DEFAULT_ADMIN_PASSWORD=replace-with-a-strong-password
DEFAULT_USER_PASSWORD=replace-with-employee-default
OLLAMA_URL=http://host.docker.internal:11434
SECRET_KEY=replace-with-a-random-64-char-hex-string
```

Restart the container after changing `.env`:

```bash
docker compose down && docker compose up --build
```

---

## Docker Commands

| Command | Description |
|---|---|
| `docker compose up --build` | Build image and start all services |
| `docker compose up -d --build` | Same, detached (background) |
| `docker compose down` | Stop and remove containers |
| `docker compose logs -f app` | Stream app logs |
| `docker compose restart app` | Restart app (no rebuild — use `up --build` for code changes) |
| `docker compose pull` | Pull latest base images |

---

## App Routes

| Route | Description |
|---|---|
| `/login` | Sign-in page |
| `/app` | Employee AI workspace |
| `/admin` | Admin dashboard (admin accounts only) |

---

## Employee Workspace (`/app`)

Employees access `/app` and can:

- **Chat** — type any question, Auto Router picks the best model
- **Generate code** — model returns runnable files; optionally written to a local workspace folder
- **Manual model select** — override Auto Router via the model picker in the composer
- **Workspace** — configure a local project folder; generated files are saved there automatically
- **My Profile** — update name, phone, designation, team, branch; change password (enterprise fields locked for SSO users)
- **Chat history** — browse, resume, pin, archive, fork past chat threads

---

## Admin Dashboard (`/admin`)

Admins access `/admin` and can:

| Tab | Features |
|---|---|
| **Overview** | Live stats: model count, user count, group count, audit feed |
| **Chat** | Test chat with full router visibility and model streaming |
| **Models** | Sync Ollama models; set governance tier, resource tier, context size |
| **Users** | Create employees; set department, team, role, AI access tier; reset passwords; invite credentials shown on creation |
| **Access Control** | RBAC role catalog, effective access inspector, per-user permission overrides |
| **Settings** | Auto Router toggle, local write config, workspace root, API model access |
| **Providers** | Ollama status + model pills; configure OpenAI / Anthropic / Groq / custom providers |
| **Reports** | 10 interactive charts: daily activity, model usage, token leaderboard (paginated), mode breakdown, department usage, tier distribution, live vs failed, audit timeline, latency by model |

---

## Auto Model Router

The router runs on every request:

1. Classifies the request (coding, writing, medical, OCR, review, general, etc.)
2. Checks which models the user is approved to access (user, group, department grants)
3. Scores each candidate model by capability match, speed, quality, and privacy weight
4. Selects the highest-scoring approved model
5. Falls back gracefully if no model is available
6. Enforces local-only routing for sensitive content (SSN, credit card, PHI, API keys detected)

The routing decision is visible in the **Auto Router** panel on the right side of the workspace.

---

## Security

Olla Nest v2026.0.1.mvp ships with:

- **Session cookies** — `HttpOnly`, `SameSite=Lax`, `Secure` (when behind HTTPS proxy), 12-hour expiry
- **Login rate limiting** — 10 failed attempts per IP per 15 minutes before lockout
- **CSRF protection** — `X-Requested-With` header required on all state-changing endpoints
- **HTTP security headers** — `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `X-XSS-Protection`, `Referrer-Policy`, `CSP`, `Permissions-Policy`
- **Input validation** — 16,000 character max on chat messages; 512 KB max JSON body
- **Password hashing** — bcrypt with cost factor 12
- **API key encryption** — AES-256-GCM for stored provider keys
- **Workspace path traversal protection** — file writes restricted to workspace root
- **Admin-only filesystem browse** — `/api/workspace/browse` restricted to admin role
- **Sensitive content detection** — SSN, credit card, PHI patterns force local-only model routing

---

## Data Storage

Olla Nest uses local SQLite inside the Docker volume — no external databases required.

| Store | File | Contains |
|---|---|---|
| SQLite | `/app/data/olla-nest.sqlite` | Users, roles, permissions, departments, groups, teams, models, chat sessions, messages, audit, router traces |
| Volume | `app-data` | Persistent data across restarts |

Data persists across `docker compose down / up` via the named `app-data` volume. To reset all data:

```bash
docker compose down -v   # removes the volume — destructive
```

---

## Project Structure

```text
server.js               Express backend — API, auth, routing, Ollama, SSE streaming
package.json            Scripts and metadata
public/
  login.html            Sign-in page
  login.js
  app.html              Employee workspace
  app.js
  admin.html            Admin dashboard (3,700+ lines)
  admin.js              Admin JS logic (1,300+ lines)
  dropdown.js           Shared dropdown utility
  styles.css            Design system
data/                   Generated at runtime — gitignored
  olla-nest.sqlite
  workspace/
infra/
  postgres/init.sql     Future production PostgreSQL schema
docker-compose.yml      App service with volume and host routing
Dockerfile              Node 24 Alpine image
.env.example            Environment variable reference
docs/
  ARCHITECTURE.md
  ENTERPRISE_ACCESS_CONTROL.md
  DEPLOYMENT.md
CHANGELOG.md            Full version history
VERSION.md              Version tracker with commit log
CONTRIBUTING.md
```

---

## Production Deployment

For a team or company deployment, set real credentials in `.env` before first boot:

```env
DEFAULT_ADMIN_EMAIL=admin@yourcompany.com
DEFAULT_ADMIN_PASSWORD=replace-with-a-strong-32-char-password
DEFAULT_USER_PASSWORD=replace-with-a-strong-default
OLLAMA_URL=http://your-ollama-server:11434
SECRET_KEY=replace-with-64-char-hex
SESSION_SECRET=replace-with-random-string
```

Then start:

```bash
docker compose up -d --build
```

Put Nginx or a reverse proxy in front for HTTPS and a custom domain.

---

## Roadmap

- [ ] Real SSO / LDAP / SAML integration
- [ ] Department and group policy editor UI
- [ ] Visual diff for generated file changes
- [ ] Full RAG / document knowledge base
- [ ] API model provider integration (OpenAI, Anthropic, Groq) — foundation in place
- [ ] Usage analytics dashboard for employees
- [ ] Desktop app (Tauri) for macOS, Windows, Linux
- [ ] Mobile app
- [ ] Team-based workspace folders
- [ ] Webhook / notification support for admin alerts

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Contributions should keep the project:
- Local-first and self-hostable
- Simple to run (Docker only)
- Safe for employees (no data exfiltration)
- Transparent about routing decisions

---

## Sponsoring

If Olla Nest is saving your team time or money, consider [sponsoring the project on GitHub](https://github.com/sponsors/ashokramcse). Every sponsor helps fund continued development, security updates, and new features.

---

## Links

[Architecture](docs/ARCHITECTURE.md) · [Enterprise Access Control](docs/ENTERPRISE_ACCESS_CONTROL.md) · [Deployment](docs/DEPLOYMENT.md) · [Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md) · [Releases](https://github.com/ashokramcse/olla-nest/releases)
