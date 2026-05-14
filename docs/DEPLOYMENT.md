# Deployment

Olla Nest runs exclusively via Docker. There is no local Node.js development mode and no host-machine frontend dev server.

The server enforces this at startup. `npm start` intentionally exits with a Docker-only message, and `node server.js` exits unless it is running inside the container. The only supported way to run the product is Docker Compose.

---

## Prerequisites

- [Docker Engine](https://docs.docker.com/engine/install/) 24+ and Docker Compose v2
- [Ollama](https://ollama.com) running on the host machine
- At least one model pulled in Ollama:

```bash
ollama pull qwen2.5:7b
```

---

## Starting the App

```bash
git clone https://github.com/ashokramcse/olla-nest.git
cd olla-nest
cp .env.example .env
# Edit .env — set real credentials before first boot
docker compose up --build
```

Open: **http://localhost:3000**

The login page will appear. Use the credentials from `.env` (defaults shown below).

Do not start the app with `npm start` or `node server.js` on the host. For rare one-off diagnostics, maintainers can set `ALLOW_NON_DOCKER=1`, but that path is unsupported for normal use and should not be documented as a user setup.

---

## Environment Variables

All configuration is done via `.env`. Copy `.env.example` to `.env` and edit before first boot:

| Variable | Default | Description |
|---|---|---|
| `DEFAULT_ADMIN_EMAIL` | `admin@ollanest.local` | Admin account email, seeded on first boot |
| `DEFAULT_ADMIN_PASSWORD` | `CHANGE_ME_ON_FIRST_BOOT` | Admin password, seeded on first boot |
| `DEFAULT_USER_PASSWORD` | `CHANGE_ME_ON_FIRST_BOOT` | Default password for new employee accounts |
| `OLLAMA_URL` | `http://host.docker.internal:11434` | URL the container uses to reach Ollama |

> Credentials set in `.env` are only used during the **first boot** to seed the database. Changing them after first boot requires resetting the database (see below) or updating via the Admin dashboard.

`OLLA_NEST_DOCKER_RUNTIME=true` is set by the Docker image and Compose file. Do not move it into local host workflows.

---

## Ollama Connectivity

| Platform | Setup | OLLAMA_URL |
|---|---|---|
| macOS / Windows (Docker Desktop) | Works out of the box | `http://host.docker.internal:11434` |
| Linux | `extra_hosts` already set in docker-compose.yml | `http://host.docker.internal:11434` |
| Ollama on a separate machine | Set IP in `.env` | `http://192.168.x.x:11434` |

If the dashboard shows **Ollama not connected**, sign in as admin, go to **Settings → Model Sources**, update the Ollama URL, click **Test connection**, and **Save URL**.

---

## Docker Commands

```bash
# Start (build image first)
docker compose up --build

# Start in background
docker compose up -d --build

# Stop
docker compose down

# Stream logs
docker compose logs -f app

# Restart app only (no rebuild)
docker compose restart app

# Rebuild image after code changes
docker compose up --build

# Remove all containers + data volume (full reset)
docker compose down -v
```

---

## Data Persistence

All data is stored inside the `app-data` named Docker volume:

| Path in container | Contents |
|---|---|
| `/app/data/olla-nest.sqlite` | Users, roles, permissions, departments, groups, model governance, user overrides, settings |
| `/app/data/documents.json` | Chat history, audit log, router traces, workspace preferences |
| `/app/data/workspace/` | Default local output folder for Build/Fix file writes |

Data persists across `docker compose down / up` because the volume is not removed unless you pass `-v`.

**Full reset** (deletes all users, settings, chat history):

```bash
docker compose down -v
docker compose up --build
```

**Backup the volume** before upgrading or resetting:

```bash
docker run --rm \
  -v olla-nest_app-data:/data \
  -v $(pwd):/backup \
  alpine tar czf /backup/olla-nest-backup.tar.gz /data
```

---

## Upgrading

```bash
git pull
docker compose up --build
```

The app migrates the SQLite schema automatically on startup (additive changes only). Existing data is preserved.

## Validation

Before publishing a change, validate the Docker setup:

```bash
docker compose config --quiet
docker compose build app
docker compose up -d
docker compose ps
docker compose logs --tail=80 app
docker compose down
```

The container command is `npm run container:start`, which starts `server.js` inside Docker after the Docker-only runtime flag is set.

---

## Production Hardening Checklist

Before sharing with your team:

- [ ] Set a real `DEFAULT_ADMIN_EMAIL` and `DEFAULT_ADMIN_PASSWORD` in `.env`
- [ ] Set a real `DEFAULT_USER_PASSWORD` in `.env`
- [ ] Place a reverse proxy (Nginx, Caddy, Traefik) in front for HTTPS
- [ ] Restrict port 3000 to localhost; expose only via reverse proxy
- [ ] Store `.env` outside the repository or use Docker secrets
- [ ] Back up the `app-data` volume regularly
- [ ] Point `OLLAMA_URL` to a dedicated Ollama host if running multi-user

### Example Nginx reverse proxy config

```nginx
server {
    listen 443 ssl;
    server_name ai.yourcompany.com;

    ssl_certificate     /etc/ssl/certs/yourcompany.crt;
    ssl_certificate_key /etc/ssl/private/yourcompany.key;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

---

## Troubleshooting

**Container exits immediately**

```bash
docker compose logs app
```

Check for port conflicts or missing `.env`.

**Ollama not connected / no models**

1. Confirm Ollama is running on the host: `ollama list`
2. Sign in as admin → **Settings** → update and test the Ollama URL
3. On Linux, confirm `host.docker.internal` resolves: `docker exec olla-nest-app ping -c1 host.docker.internal`

**Port 3000 already in use**

Edit `docker-compose.yml` and change the host port:

```yaml
ports:
  - "8080:3000"   # access at http://localhost:8080
```

**Reset admin password**

```bash
docker compose down -v   # removes data — start fresh
docker compose up --build
```

Or update via Admin → Users → Reset Password if you still have admin access.
