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
- Department-based model access control
- Local-first Ollama integration with graceful fallback when Ollama is not running
- Audit trail for routed requests and admin changes
- Clean browser-based interface for the first MVP
- Model discovery from a local Ollama server

## Supported Local Models in This MVP

The current seed catalog is configured for these Ollama models:

- `qwen3.5:9b`
- `gemma4:26b`
- `granite4.1:3b`
- `lfm2.5-thinking:1.2b`
- `medgemma:4b`
- `glm-ocr:bf16`

You can adapt the model catalog in `server.js` for your own installed models.

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
- Which models the user is allowed to access
- Whether the task is coding, writing, medical, OCR, review, summary, or general reasoning

Then it selects the best approved model for the job.

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
server.js            Express backend, seed data, routing logic, Ollama API integration
public/index.html    Browser app shell
public/styles.css    Minimal dashboard styling
public/app.js        Frontend state, UI rendering, chat, admin actions
data/db.json         Local generated data store, ignored by Git
```

## Current Status

This is an MVP prototype. It is intentionally simple and local-first:

- No production authentication yet
- No external database yet
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
