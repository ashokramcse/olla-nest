# Olla Nest

**Company-ready local AI workspace built on Ollama.**

Olla Nest gives teams a private, admin-controlled AI workspace where employees type once and the system automatically routes each request to the best approved local model — with no cloud dependency, no data leaving the network, and no employee needing to understand model names.

---

## What Makes Olla Nest Different

Most AI dashboards make users choose the model. Olla Nest adds a company control layer on top of Ollama:

- **Auto Router** — analyses every request and picks the best approved model automatically
- **Admin control** — manage which employees, groups, or departments access which models
- **Local-first** — all AI runs on your infrastructure via Ollama; no API keys, no external calls
- **Local file output** — Build and Fix modes write real files directly to a company workspace folder
- **Audit trail** — every routing decision and admin action is logged

---

## Requirements

- [Docker](https://docs.docker.com/get-docker/) and Docker Compose v2
- [Ollama](https://ollama.com) running on the host machine with at least one model pulled

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

| Field    | Value                           |
|----------|---------------------------------|
| Email    | `admin@ollanest.local`          |
| Password | `ChangeMe!CreateARealPassword123` |

> Change the admin password immediately after first login via **Admin → Settings** or set `DEFAULT_ADMIN_PASSWORD` in `.env` before first boot.

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
| `docker compose restart app` | Restart the app container only |
| `docker compose pull` | Pull latest base images |

Or use the npm shortcuts (requires Node locally only to run the shortcut — Docker does the actual work):

```bash
npm run docker:up       # docker compose up --build
npm run docker:down     # docker compose down
npm run docker:logs     # docker compose logs -f app
npm run docker:restart  # docker compose restart app
```

---

## App Routes

| Route | Description |
|---|---|
| `/login` | Sign-in page |
| `/app` | Employee AI workspace |
| `/admin` | Admin dashboard |

---

## Employee Workspace

Employees access `/app` and can:

- **Ask** — general questions routed to the best approved model
- **Build** — generate code or files, optionally written to a local workspace folder
- **Review** — code or content review with actionable findings
- **Fix** — diagnose bugs and get specific fixes
- **Learn** — plain-language explanations with examples

The **Auto Router** selects the best available model for each request. Employees can also manually select any approved model from the composer.

For **Build** and **Fix** modes, employees can enable "Write to workspace" to have generated files saved directly to a configured local path inside the container volume.

---

## Admin Dashboard

Admins access `/admin` and can:

- **Overview** — model count, user count, group count, department count
- **Local Models** — sync and inspect discovered Ollama models with speed/quality scores
- **Users** — create accounts, reset passwords, activate/deactivate employees
- **Settings** — configure Auto Router, API model access, local file write permissions, Ollama URL, workspace root
- **Audit Trail** — timestamped log of all routing decisions and admin changes

---

## Auto Model Router

The router runs on every request:

1. Classifies the request (coding, writing, medical, OCR, review, general, etc.)
2. Checks which models the user is approved to access (user, group, department grants)
3. Scores each candidate model by capability match, speed, quality, and privacy
4. Selects the highest-scoring approved model
5. Falls back gracefully if no model is available

The routing logic is visible in the **Auto Router** panel on the right side of the workspace after each request.

---

## Data Storage

Olla Nest uses local SQLite and a JSON document store inside the Docker volume — no external databases required for running.

| Store | File | Contains |
|---|---|---|
| SQLite | `/app/data/olla-nest.sqlite` | Users, groups, departments, models, permissions, settings |
| JSON | `/app/data/documents.json` | Chat history, audit log, router traces, workspace prefs |
| Volume | `app-data` | Persistent data across container restarts |

Data persists across `docker compose down / up` via the named `app-data` volume. To reset all data:

```bash
docker compose down -v   # removes the volume — destructive
```

---

## Project Structure

```text
server.js               Express backend — API, auth, routing logic, Ollama integration, file writes
public/
  login.html            Sign-in page
  login.js
  app.html              Employee workspace
  app.js
  admin.html            Admin dashboard
  admin.js
  styles.css            Design system
data/                   Generated at runtime — gitignored
  olla-nest.sqlite
  documents.json
  workspace/
infra/
  postgres/init.sql     Future production PostgreSQL schema
docker-compose.yml      App service with volume and Ollama host routing
Dockerfile              Node 24 Alpine image
.env.example            Environment variable reference
docs/
  ARCHITECTURE.md       Database strategy and product stack direction
  DEPLOYMENT.md         Deployment reference
```

---

## Production Deployment

For a team or company deployment, set real credentials in `.env` before first boot:

```env
DEFAULT_ADMIN_EMAIL=admin@yourcompany.com
DEFAULT_ADMIN_PASSWORD=replace-with-a-strong-32-char-password
DEFAULT_USER_PASSWORD=replace-with-a-strong-default
OLLAMA_URL=http://your-ollama-server:11434
```

Then start:

```bash
docker compose up -d --build
```

Put Nginx or a reverse proxy in front for HTTPS and a custom domain.

---

## Roadmap

- Real authentication and SSO
- Department and group policy editor UI
- More complete model access grant screens
- Visual diff for generated file changes
- Full RAG / document knowledge base
- API model provider integration (OpenAI, Anthropic, Groq)
- Real-time token streaming
- Usage analytics and billing controls
- Desktop app (Tauri) for macOS, Windows, Linux
- Mobile app

---

## Open Source

Olla Nest is open-source and intended to become the standard local AI workspace for companies and teams who want control over how AI is deployed and used.

Contributions should keep the project local-first, simple to run, safe for employees, and transparent about model routing decisions.

[Architecture](docs/ARCHITECTURE.md) · [Deployment](docs/DEPLOYMENT.md) · [Contributing](CONTRIBUTING.md)
