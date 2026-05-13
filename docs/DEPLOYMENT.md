# Deployment

Olla Nest supports two deployment modes.

## 1. Local Developer Mode

Use this when developing the MVP on a laptop.

Storage:

- SQLite for SQL data
- JSON document store for chat/audit/router traces
- In-memory realtime state

Commands:

```bash
npm install
cp .env.example .env
npm start
```

Open:

```text
http://localhost:3000/login
```

Default first boot admin:

```text
Email: admin@ollanest.local
Password: ChangeMe!CreateARealPassword123
```

Use a strong password:

```bash
DEFAULT_ADMIN_PASSWORD="your-strong-password" npm start
```

Main routes:

```text
/login  Sign in
/app    Employee workspace
/admin  Admin dashboard
```

Ollama should be running locally:

```text
http://localhost:11434
```

## 2. Company/Production Mode

Use PostgreSQL + MongoDB + Redis.

Start databases:

```bash
docker compose up -d postgres mongo redis
```

Configure `.env`:

```bash
STORAGE_MODE=production
DEFAULT_ADMIN_EMAIL=admin@yourcompany.com
DEFAULT_ADMIN_PASSWORD=replace-with-a-strong-password
DATABASE_URL=postgresql://olla_nest:olla_nest@localhost:5432/olla_nest
MONGODB_URI=mongodb://localhost:27017/olla_nest
REDIS_URL=redis://localhost:6379
OLLAMA_URL=http://localhost:11434
```

Start app:

```bash
npm start
```

## Desktop App Direction

The web app should remain the primary UI surface. The desktop app should wrap and extend it.

Recommended path:

1. Keep the backend API separate from UI concerns.
2. Move frontend to React + Vite when UI complexity increases.
3. Package desktop with Tauri for macOS, Windows, and Linux.
4. Let desktop builds manage local Ollama connection, local workspace permissions, and file access.
5. Keep the same API contracts for future mobile apps.

## Production Checklist

- Configure PostgreSQL, MongoDB, and Redis.
- Configure Ollama or approved model providers.
- Set real authentication.
- Set HTTPS and trusted origins.
- Store secrets outside Git.
- Add backups for PostgreSQL and MongoDB.
- Add Redis persistence only for queues and recoverable realtime state.
- Add observability for model latency, routing decisions, errors, and usage.
