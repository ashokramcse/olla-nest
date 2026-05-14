# Contributing to Olla Nest

Thanks for considering a contribution.

Olla Nest is a local-first AI workspace for companies using Ollama. The project runs exclusively via Docker. Keep it simple, understandable, and easy to run.

## Principles

- Local-first by default — no cloud dependency in the core path
- Admin controls must be clear and practical
- Employee workflows must stay simple
- Model routing decisions must be transparent
- Prefer readable code over clever abstractions
- No heavy infrastructure unless it clearly improves the product

## Areas to Contribute

- Router scoring and model capability inference
- Admin policy and access grant editor
- Authentication and session management
- Workspace and file output controls
- UI improvements
- Tests
- Documentation

## Development Setup

Docker is the only supported runtime.

```bash
git clone https://github.com/ashokramcse/olla-nest.git
cd olla-nest
cp .env.example .env
docker compose up --build
```

Open **http://localhost:3000**.

Make code changes, then rebuild:

```bash
docker compose up --build
```

Do not run `npm start`, `node server.js`, or a host-machine frontend dev server. The app blocks non-Docker starts by design so every contribution is tested against the same runtime users receive.

## Project Structure

```
server.js          Backend — Express API, auth, router, Ollama integration, file writes
public/
  login.html       Sign-in page
  login.js
  app.html         Employee workspace
  app.js
  admin.html       Admin dashboard
  admin.js
  styles.css       Design system
docker-compose.yml App service, volume, Ollama host routing
Dockerfile         node:24-alpine image
package.json       Docker helper scripts and container-only start command
.env.example       Environment variable reference
docs/              Architecture and deployment reference
infra/             Future production database schemas
```

## Before Opening a Pull Request

- Test the full flow: login → workspace → admin
- Test at least one Build/Fix mode request
- Run `docker compose config --quiet`
- Run `docker compose build app`
- Keep commits focused with clear messages
- Update docs when behaviour changes
