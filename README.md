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
- Model discovery from a local Ollama server
- SQLite + document-store backend foundation

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

Olla Nest uses a mandatory SQL + NoSQL-style combination by default:

- SQL: SQLite for relational company data such as users, groups, departments, models, settings, and access grants.
- Document store: JSON document database for chats, audit events, and router traces.

Default local files:

```text
data/olla-nest.sqlite
data/documents.json
```

The goal is to support configurable company database backends later, such as PostgreSQL/MySQL for SQL and MongoDB/CouchDB-compatible stores for document data.

## Run Locally

Install dependencies:

```bash
npm install
```

Start the app:

```bash
npm start
```

Open:

```text
http://localhost:3000
```

Ollama is expected at:

```text
http://localhost:11434
```

You can override the Ollama URL:

```bash
OLLAMA_URL=http://localhost:11434 npm start
```

## Project Structure

```text
server.js            Express backend, SQL schema, document store, routing logic, Ollama integration
public/index.html    Browser app shell
public/styles.css    Minimal dashboard styling
public/app.js        Frontend state, UI rendering, chat, admin actions
data/*.sqlite        Local generated SQLite database, ignored by Git
data/documents.json  Local generated document store, ignored by Git
```

## Current Status

This is an MVP prototype. It is intentionally simple and local-first:

- No production authentication yet
- No external database yet
- Configurable external database adapters are not implemented yet
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

## Open Source Direction

Olla Nest is intended to become an open-source local AI workspace for companies, teams, and developers who want more control over how AI is deployed and used.

Contributions should keep the project:

- Local-first
- Simple to run
- Clear for admins
- Safe for employees
- Transparent about model routing decisions
