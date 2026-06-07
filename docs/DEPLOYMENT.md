# Deployment

Olla Nest is a **Maven multi-module Spring Boot application** — two independent services built from one parent POM, sharing a common library. They run as separate `java -jar` processes and share a single SQLite database.

| Service | Module | Default port | Purpose |
|---------|--------|-------------|---------|
| Admin control panel | `olla-nest-admin` | `8080` (`ADMIN_PORT`) | User management, model governance, settings, reports |
| Employee workspace | `olla-nest-user` | `8081` (`USER_PORT`) | Chat, RAG, voice, image generation, code sandbox |

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Java (JDK) | 21+ | [Adoptium OpenJDK](https://adoptium.net) recommended |
| Maven | 3.9+ | [Download](https://maven.apache.org/download.cgi) — only needed to build from source |
| Ollama | Latest | [Download](https://ollama.com) — at least one model pulled |

Pull a model before starting:
```bash
ollama pull llama3.2:3b
```

---

## Quick Start

```bash
# 1. Clone
git clone https://github.com/ashokramcse/olla-nest.git
cd olla-nest

# 2. Configure
cp .env.example .env
# Edit .env — set ENCRYPTION_KEY and OLLAMA_URL

# 3. Build all modules
mvn clean package -DskipTests

# 4. Run both services
java --enable-native-access=ALL-UNNAMED \
     -jar olla-nest-admin/target/olla-nest-admin-2026.1.9.jar &

java --enable-native-access=ALL-UNNAMED \
     -jar olla-nest-user/target/olla-nest-user-2026.1.9.jar &
```

Open **http://localhost:8080** (Admin) · **http://localhost:8081** (Employee workspace)

---

## Environment Variables

All configuration is done via environment variables or a `.env` file. Copy `.env.example` to `.env` and edit before first boot:

| Variable | Default | Required | Description |
|---|---|---|---|
| `ENCRYPTION_KEY` | — | **Yes** | Secret key for AES-256-GCM API key encryption |
| `ADMIN_PORT` | `8080` | No | Admin control panel HTTP port |
| `USER_PORT` | `8081` | No | Employee workspace HTTP port |
| `OLLAMA_URL` | `http://localhost:11434` | No | URL of your Ollama instance |
| `DATA_DIR` | `./data` | No | Directory for SQLite DB and backups |
| `DEFAULT_ADMIN_EMAIL` | `admin@ollanest.local` | No | Admin email seeded on first boot |
| `DEFAULT_ADMIN_PASSWORD` | *(auto-generated)* | No | Leave blank — a secure password is printed to the console on first boot |
| `DEFAULT_USER_PASSWORD` | `CHANGE_ME_ON_FIRST_BOOT` | No | Default password assigned to new user accounts |
| `COOKIE_SECURE` | `false` | No | Set `true` when running behind HTTPS/TLS |
| `TRUSTED_PROXY` | *(empty)* | No | Trusted proxy IP for `X-Forwarded-For` rate-limit resolution |
| `STATIC_DIR` | `./public` | No | Directory served as the static frontend |
| `ADMIN_BASE_URL` | `http://localhost:8080` | No | **Admin** public base URL — allowed WebSocket origin and SSO `redirect_uri` base |
| `BASE_URL` | `http://localhost:8081` | No | **User** public base URL — allowed WebSocket origin and SSO `redirect_uri` base |
| `SESSION_COOKIE_NAME` | admin `olla_nest_session` · user `olla_nest_user_session` | No | Session cookie name; read independently by each service |

> **First boot:** On the first startup, the server checks whether any users exist. If none do, it seeds a default admin account. If `DEFAULT_ADMIN_PASSWORD` is not set (or is the sentinel value `CHANGE_ME_ON_FIRST_BOOT`), a random 16-character password is generated and printed clearly to the server log. Copy it from the log and log in immediately. You can change it from **Admin → Users**.

> **Ports are fully configurable.** Each service reads its port from `ADMIN_PORT` / `USER_PORT`, so it can run on any port without code changes. When you move a service to a non-default port, also set its base URL (`ADMIN_BASE_URL` / `BASE_URL`) so the WebSocket origin check and SSO callbacks stay correct.

> **Independent sessions, same host:** Cookies are scoped by **host, not port**. The admin and user apps therefore use **different** session cookie names by default (`olla_nest_session` vs `olla_nest_user_session`) so they never share a session when both run on `localhost`. You can be logged into both at once in one browser; login/logout in one does not affect the other. Changing ports does **not** require changing cookie names — only override `SESSION_COOKIE_NAME` if you run multiple deployments on the same host and need to disambiguate further.

---

## Running the Servers

### Option 1 — Direct JAR (recommended for production)

```bash
# Terminal 1 — Admin panel (port 8080)
ENCRYPTION_KEY=your-secret-key \
OLLAMA_URL=http://localhost:11434 \
java --enable-native-access=ALL-UNNAMED \
     -jar olla-nest-admin/target/olla-nest-admin-2026.1.9.jar

# Terminal 2 — Employee workspace (port 8081)
ENCRYPTION_KEY=your-secret-key \
OLLAMA_URL=http://localhost:11434 \
java --enable-native-access=ALL-UNNAMED \
     -jar olla-nest-user/target/olla-nest-user-2026.1.9.jar
```

> **`--enable-native-access=ALL-UNNAMED`** — SQLite JDBC loads its native binary via `System.load()`. Java 21+ prints a WARNING without this flag; Java 24+ will block it entirely. The flag silences the warning cleanly.

### Option 2 — Maven (development)

```bash
# Admin (terminal 1):
mvn spring-boot:run -pl olla-nest-admin

# User (terminal 2):
mvn spring-boot:run -pl olla-nest-user
```

> The `<jvmArguments>` in each module's POM already passes `--enable-native-access=ALL-UNNAMED` automatically.

### Option 3 — Eclipse IDE

1. **File → Import → Maven → Existing Maven Projects** → select the project folder
2. Right-click `OllaNestAdminApplication.java` → **Run As → Run Configurations** → add env vars → **Run**
3. Repeat for `OllaNestUserApplication.java`

After installing the **Spring Tools 4** plugin *(Eclipse Marketplace → search "Spring Tools")*, use the Spring Boot Dashboard panel for one-click start/stop of each module.

### Option 4 — IntelliJ IDEA

1. **File → Open** → select the project folder (auto-detects Maven multi-module)
2. Open `OllaNestAdminApplication.java`, click the green ▶ Run button
3. Open `OllaNestUserApplication.java`, click the green ▶ Run button
4. Go to **Run → Edit Configurations** → add env vars under **Environment variables** for each

---

## Building Production JARs

```bash
mvn clean package -DskipTests
```

Output:
- `olla-nest-admin/target/olla-nest-admin-2026.1.9.jar`
- `olla-nest-user/target/olla-nest-user-2026.1.9.jar`

Both are **fat JARs** — each contains the embedded Tomcat server and all dependencies. Copy them to any machine with Java 21+ and run them. They share the same `data/olla-nest.sqlite` file.

---

## Running as a System Service (macOS / Linux)

### macOS — launchd (two plist files, one per service)

Create `/Library/LaunchDaemons/com.ollanest.admin.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.ollanest.admin</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/java</string>
    <string>--enable-native-access=ALL-UNNAMED</string>
    <string>-jar</string>
    <string>/opt/olla-nest/olla-nest-admin-2026.1.9.jar</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>ENCRYPTION_KEY</key><string>your-secret-key</string>
    <key>OLLAMA_URL</key><string>http://localhost:11434</string>
    <key>DATA_DIR</key><string>/opt/olla-nest/data</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/var/log/olla-nest-admin.log</string>
  <key>StandardErrorPath</key><string>/var/log/olla-nest-admin-error.log</string>
</dict>
</plist>
```

Create a matching `com.ollanest.user.plist` with `olla-nest-user-2026.1.9.jar`.

```bash
sudo launchctl load /Library/LaunchDaemons/com.ollanest.admin.plist
sudo launchctl load /Library/LaunchDaemons/com.ollanest.user.plist
```

### Linux — systemd (two unit files, one per service)

Create `/etc/systemd/system/olla-nest-admin.service`:

```ini
[Unit]
Description=Olla Nest Admin Panel
After=network.target

[Service]
Type=simple
User=ollanest
WorkingDirectory=/opt/olla-nest
ExecStart=/usr/bin/java --enable-native-access=ALL-UNNAMED \
    -jar /opt/olla-nest/olla-nest-admin-2026.1.9.jar
Environment=ENCRYPTION_KEY=your-secret-key
Environment=OLLAMA_URL=http://localhost:11434
Environment=DATA_DIR=/opt/olla-nest/data
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Create a matching `olla-nest-user.service` pointing to `olla-nest-user-2026.1.9.jar`.

```bash
sudo systemctl enable olla-nest-admin olla-nest-user
sudo systemctl start  olla-nest-admin olla-nest-user
sudo journalctl -u olla-nest-admin -f   # view admin logs
sudo journalctl -u olla-nest-user  -f   # view user logs
```

---

## Updating to a New Version

1. Stop the running server
2. Pull the latest code: `git pull origin main`
3. Rebuild: `mvn clean package -DskipTests`
4. Start the new JAR

Flyway will automatically apply any new database migrations on startup. No manual SQL needed.

---

## Data Directory

| Path | Contents |
|---|---|
| `data/olla-nest.sqlite` | Main SQLite database |
| `data/backups/` | Daily backups (last 7 kept) |
| `data/workspace/` | Default local workspace folder |

**Backup manually:**
```bash
# Trigger an immediate VACUUM INTO backup via the Admin API
curl -b cookies.txt -X POST http://localhost:8080/api/admin/settings/backup \
     -H "X-Requested-With: XMLHttpRequest"
```

**Restore from backup:**
```bash
cp data/backups/olla-nest-2026-05-21T02-00-00.sqlite data/olla-nest.sqlite
```

---

## Voice STT — Local Whisper Server Setup

Olla Nest auto-starts a local `faster-whisper` HTTP server on port 8765 when it launches. You only need to run the setup script **once** to create the Python virtual environment.

> **Why one-time?** The setup script installs Python packages into `scripts/venv/`. After that, `WhisperServerManager` (a Spring `@Component`) starts `scripts/whisper_server.py` automatically using the venv Python on every boot.

### macOS

```bash
bash scripts/start_whisper.sh
```

Requires Homebrew. The script installs `python@3.11` and `ffmpeg` via `brew` if missing.

### Linux (Ubuntu / Debian)

```bash
bash scripts/start_whisper.sh
```

Installs `python3.11`, `python3.11-venv`, `ffmpeg`, and `pkg-config` via `apt-get`.

### Linux (RHEL 8+ / Rocky / AlmaLinux)

```bash
bash scripts/start_whisper.sh
```

Uses `dnf` to install dependencies.

### Linux (Alpine)

```bash
bash scripts/start_whisper.sh
```

Uses `apk`.

### Windows (CMD)

```cmd
scripts\start_whisper.bat
```

Installs Python 3.11 and ffmpeg via `winget` if missing.

### Windows (PowerShell / Windows Server)

```powershell
.\scripts\start_whisper.ps1
```

### Verify it's running

```bash
curl http://localhost:8765/health
# → {"status":"ok","model":"base","port":8765}
```

Or look for `[whisper] Process started (PID …)` in the Olla Nest startup log.

### Python version constraint

`faster-whisper` depends on `av` (PyAV), which has no binary wheels for Python 3.13 or 3.14. The setup scripts enforce Python 3.9–3.12 and prefer 3.11 (all wheels available). If you have Python 3.13/3.14 as your system default, the script will auto-install Python 3.11 via your package manager.

### Choosing the STT provider

Go to **Admin → Settings → Voice STT Provider**:
- **Local** (default) — free, uses the on-device faster-whisper server
- **OpenAI** — paid ($0.006/min); enter your OpenAI API key in the OpenAI section

---

## Ollama Connectivity

| Scenario | OLLAMA_URL |
|---|---|
| Ollama on same machine | `http://localhost:11434` |
| Ollama on another machine | `http://192.168.x.x:11434` |
| Ollama on a remote server | `http://your-server.internal:11434` |

If the dashboard shows **Ollama offline**, go to **Admin → Settings → Model Sources**, update the URL, click **Test connection**, and **Save URL**.

---

---

## HTTPS / TLS

Olla Nest does not handle TLS directly. Place both services behind a reverse proxy (Nginx, Caddy, or Traefik) for HTTPS.

Once TLS is in place, set `COOKIE_SECURE=true` so the session cookie is only sent over HTTPS.

Example Nginx config (two upstreams):
```nginx
# Admin panel — admin.yourcompany.com
server {
    listen 443 ssl;
    server_name admin.yourcompany.com;

    ssl_certificate     /etc/ssl/certs/your.crt;
    ssl_certificate_key /etc/ssl/private/your.key;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_buffering off;
        proxy_read_timeout 300s;
    }
}

# Employee workspace — ai.yourcompany.com
server {
    listen 443 ssl;
    server_name ai.yourcompany.com;

    ssl_certificate     /etc/ssl/certs/your.crt;
    ssl_certificate_key /etc/ssl/private/your.key;

    location / {
        proxy_pass http://localhost:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        # Required for SSE streaming and WebSocket terminal:
        proxy_buffering off;
        proxy_read_timeout 300s;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

---

## Health Check

```bash
curl http://localhost:8080/api/admin/health
```

Returns server uptime, DB stats, JVM memory usage, and Ollama status.

## Changing the Ports

Either service can run on any port. Set the port **and** the matching base URL so the WebSocket origin check and SSO callbacks resolve correctly:

```bash
ADMIN_PORT=9000 ADMIN_BASE_URL=http://localhost:9000 \
    java --enable-native-access=ALL-UNNAMED \
    -jar olla-nest-admin/target/olla-nest-admin-2026.1.9.jar

USER_PORT=9001 BASE_URL=http://localhost:9001 \
    java --enable-native-access=ALL-UNNAMED \
    -jar olla-nest-user/target/olla-nest-user-2026.1.9.jar
```

Session cookie names are **independent of the port** — they are fixed per service (`olla_nest_session` for admin, `olla_nest_user_session` for user), so changing ports never causes a session clash. The two apps remain independently logged-in in the same browser regardless of which ports they bind. Override `SESSION_COOKIE_NAME` only if you run more than one instance of the same service on a single host.
