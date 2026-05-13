# Olla Nest

Olla Nest is a company-ready local AI workspace for Ollama. It gives teams a private, admin-controlled dashboard where employees can ask once and the system automatically routes the request to the best approved local model.

The goal is simple: make local AI usable inside companies without asking every employee to understand model names, setup, permissions, or routing decisions.

## Why Olla Nest Exists

Most AI dashboards make users choose the model. That works for technical users, but it does not scale well inside companies.

Olla Nest adds a company layer on top of Ollama:

- Admins manage models, access, departments, and policies.
- Employees use one simple workspace.
- Auto Model Router chooses the best approved model for each request.
- Local-first deployment keeps company work closer to the machine.

## MVP Features

- Employee AI workspace with Ask, Build, Review, Fix, and Learn modes
- Auto Model Router that selects the best approved model for the request
- Admin panel for company models, users, departments, policies, and settings
- User, group, and department-based model access control
- Local-first Ollama integration with graceful fallback when Ollama is not running
- Audit trail for routed requests and admin changes
- Clean browser-based interface for the first MVP
- TailAdmin-style React UI built with Tailwind CSS and Material Symbols
- Model discovery from a local Ollama server
- Admin-configurable model sources for Ollama and future API providers
- Production architecture target: PostgreSQL + MongoDB + Redis
- Local development fallback: SQLite + JSON document store + in-memory realtime state

## Model Discovery

Olla Nest does not require a hardcoded model list.

On startup and during app use, it calls the local Ollama server at `/api/tags`, discovers installed models, stores them in the model registry, and infers practical capabilities from model names.

Examples:

- Models with names like `coder`, `code`, `qwen`, or `deepseek` are treated as stronger candidates for coding work.
- Models with names like `ocr`, `vision`, or `vl` are treated as stronger candidates for image/document extraction.
- Models with names like `med`, `clinical`, or `health` are treated as stronger candidates for medical-domain requests.
- General models are still available for normal writing, summary, learning, and reasoning tasks.

Admins can later refine this with explicit model metadata and access grants.

## Product Concept

Olla Nest has two main experiences.

### Admin Panel

Admins can manage:

- Local Ollama models
- Future API-based models
- Users
- Departments
- Department policies
- Model permissions
- System settings
- Usage and audit history

### Employee Workspace

Employees can:

- Ask questions
- Use work modes such as Ask, Build, Review, Fix, and Learn
- Use Auto Router by default
- Manually select an approved model when needed
- See only the models approved for their department or account

## Auto Model Router

Auto Model Router is the key product layer.

It checks:

- What the user is asking
- Which department or role the user belongs to
- Which models are installed locally
- Which models the user is allowed to access through user, group, or department grants
- Whether the task is coding, writing, medical, OCR, review, summary, or general reasoning
- Model speed and quality estimates
- Local/privacy preference

Then it selects the best approved model for the job.

The route is not hardcoded to a department or model. Access decides what the user may use; the request decides which approved model is best.

## Database Architecture

Olla Nest uses polyglot persistence.

The production default is:

- PostgreSQL for relational source-of-truth data
- MongoDB for AI chat history, traces, and flexible tool outputs
- Redis for token streaming, live session state, queues, and rate limiting

For local developer mode, the app can run without Docker:

```text
data/olla-nest.sqlite
data/documents.json
```

SQLite and JSON are local fallbacks only. The company-grade default is PostgreSQL + MongoDB + Redis.

Read more:

- [Architecture](docs/ARCHITECTURE.md)
- [Deployment](docs/DEPLOYMENT.md)

## Run With Docker

Docker is the default production-like setup.

```bash
cp .env.example .env
docker compose up --build
```

Open:

```text
http://localhost:3000/login
```

This starts:

- Olla Nest app
- PostgreSQL with pgvector
- MongoDB
- Redis

Ollama should run on your host laptop. Docker uses:

```text
http://host.docker.internal:11434
```

If the dashboard shows `Ollama offline`, open Admin -> Model Sources and test the Ollama URL. For Docker on macOS, use `http://host.docker.internal:11434`. For a direct local `npm start`, use `http://localhost:11434`.

## Run Locally Without Docker

Install dependencies:

```bash
npm install
```

For local app development, run the API and Vite web app in two terminals:

```bash
npm run dev:api
npm run dev
```

Open:

```text
http://localhost:5173/login
```

To test the production build locally:

```bash
npm run build
npm start
```

Then open:

```text
http://localhost:3000/login
```

Default first boot admin:

```text
Email: admin@ollanest.local
Password: ChangeMe!CreateARealPassword123
```

Set a real password before sharing the app:

```bash
DEFAULT_ADMIN_PASSWORD="your-strong-password" npm start
```

App URLs:

```text
/login  Sign in
/app    User workspace
/admin  Admin dashboard
```

Ollama is expected at:

```text
http://localhost:11434
```

You can override the Ollama URL:

```bash
OLLAMA_URL=http://localhost:11434 npm start
```

## Run With Production Databases

Start PostgreSQL, MongoDB, and Redis:

```bash
docker compose up -d postgres mongo redis
```

Set production storage mode:

```bash
STORAGE_MODE=production npm start
```

## Project Structure

```text
server.js            Express backend, SQL schema, document store, routing logic, Ollama integration
src/main.jsx         React app with MUI dashboard and workspace UI
src/styles.css       Tailwind CSS and Material Design layout refinements
dist/                Built frontend bundle, generated by npm run build
docs/ARCHITECTURE.md Product architecture and database strategy
docs/DEPLOYMENT.md   Local and production deployment procedure
docker-compose.yml   PostgreSQL + MongoDB + Redis local services
data/*.sqlite        Local generated SQLite database, ignored by Git
data/documents.json  Local generated document store, ignored by Git
```

## Current Status

This is an MVP prototype. It is intentionally simple and local-first:

- MVP authentication exists with default admin bootstrap
- Production authentication/SSO is not implemented yet
- PostgreSQL, MongoDB, and Redis are defined as production defaults
- Runtime adapters still use the local fallback implementation in this MVP
- No multi-tenant deployment yet
- No real API model connector yet
- No file/project editing tools yet

Those are expected next steps as the product matures.

## Roadmap

- Real authentication and role-based access control
- Admin UI for adding/editing models without code changes
- Department policy editor
- Workspace/project folder access
- Local file reading and project Q&A
- Approval-based command execution
- Multi-model comparison and judging
- API model connectors
- Usage analytics and billing controls
- Enterprise deployment options
- Tauri desktop app for macOS, Windows, and Linux
- Mobile app using shared API contracts

## Open Source Direction

Olla Nest is intended to become an open-source local AI workspace for companies, teams, and developers who want more control over how AI is deployed and used.

Contributions should keep the project:

- Local-first
- Simple to run
- Clear for admins
- Safe for employees
- Transparent about model routing decisions
