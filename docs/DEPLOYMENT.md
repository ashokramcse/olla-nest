# Deployment

Olla Nest is a standalone Java Spring Boot application. It runs as a single `java -jar` process — no Docker, no Node.js, no external services beyond Ollama.

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

# 3. Build
mvn clean package -DskipTests

# 4. Run
java -jar target/olla-nest-*.jar
```

Open **http://localhost:3000**

---

## Environment Variables

All configuration is done via environment variables or a `.env` file. Copy `.env.example` to `.env` and edit before first boot:

| Variable | Default | Required | Description |
|---|---|---|---|
| `ENCRYPTION_KEY` | — | **Yes** | Secret key for AES-256-GCM API key encryption |
| `PORT` | `3000` | No | HTTP port the server listens on |
| `OLLAMA_URL` | `http://localhost:11434` | No | URL of your Ollama instance |
| `DATA_DIR` | `./data` | No | Directory for SQLite DB and backups |
| `STATIC_DIR` | `./public` | No | Directory for static frontend files |
| `DEFAULT_ADMIN_EMAIL` | `admin@ollanest.local` | No | Admin email seeded on first boot |
| `DEFAULT_ADMIN_PASSWORD` | *(auto-generated)* | No | Leave blank — a secure password is printed to the console on first boot |
| `DEFAULT_USER_PASSWORD` | `CHANGE_ME_ON_FIRST_BOOT` | No | Default password assigned to new user accounts |
| `COOKIE_SECURE` | `false` | No | Set `true` when running behind HTTPS/TLS |
| `TRUSTED_PROXY` | *(empty)* | No | Trusted proxy IP for X-Forwarded-For header |

> **First boot:** On the first startup, the server checks whether any users exist. If none do, it seeds a default admin account. If `DEFAULT_ADMIN_PASSWORD` is not set (or is the sentinel value `CHANGE_ME_ON_FIRST_BOOT`), a random 16-character password is generated and printed clearly to the server log. Copy it from the log and log in immediately. You can change it from **Admin → Users**.

---

## Running the Server

### Option 1 — Direct JAR (recommended for production)

```bash
ENCRYPTION_KEY=your-secret-key \
OLLAMA_URL=http://localhost:11434 \
java -jar target/olla-nest-*.jar
```

### Option 2 — Maven (development)

```bash
mvn spring-boot:run
```

### Option 3 — Eclipse IDE

1. **File → Import → Maven → Existing Maven Projects** → select the project folder
2. Right-click `OllaNestApplication.java` → **Run As → Run Configurations**
3. Go to the **Environment** tab → add your env vars
4. Click **Run**

After installing the **Spring Tools 4** plugin *(Eclipse Marketplace → search "Spring Tools")*, you can use the Spring Boot Dashboard panel for one-click start/stop.

### Option 4 — IntelliJ IDEA

1. **File → Open** → select the project folder (auto-detects Maven)
2. Open `OllaNestApplication.java`
3. Click the green ▶ Run button
4. Go to **Run → Edit Configurations** → add env vars under **Environment variables**

---

## Building a Production JAR

```bash
mvn clean package -DskipTests
```

Output: `target/olla-nest-2026.1.0.jar`

This is a **fat JAR** — it contains the embedded Tomcat server and all dependencies. Copy it to any machine with Java 21+ and run it.

---

## Running as a System Service (macOS / Linux)

### macOS — launchd

Create `/Library/LaunchDaemons/com.ollanest.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.ollanest</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/java</string>
    <string>-jar</string>
    <string>/opt/olla-nest/olla-nest-2026.1.0.jar</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>ENCRYPTION_KEY</key><string>your-secret-key</string>
    <key>OLLAMA_URL</key><string>http://localhost:11434</string>
    <key>DATA_DIR</key><string>/opt/olla-nest/data</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/var/log/olla-nest.log</string>
  <key>StandardErrorPath</key><string>/var/log/olla-nest-error.log</string>
</dict>
</plist>
```

```bash
sudo launchctl load /Library/LaunchDaemons/com.ollanest.plist
```

### Linux — systemd

Create `/etc/systemd/system/olla-nest.service`:

```ini
[Unit]
Description=Olla Nest AI Workspace
After=network.target

[Service]
Type=simple
User=ollanest
WorkingDirectory=/opt/olla-nest
ExecStart=/usr/bin/java -jar /opt/olla-nest/olla-nest-2026.1.0.jar
Environment=ENCRYPTION_KEY=your-secret-key
Environment=OLLAMA_URL=http://localhost:11434
Environment=DATA_DIR=/opt/olla-nest/data
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable olla-nest
sudo systemctl start olla-nest
sudo journalctl -u olla-nest -f   # view logs
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
# Trigger an immediate backup via the Admin API
curl -b cookies.txt -X POST http://localhost:3000/api/admin/settings/backup
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

## Changing the Port

```bash
PORT=8080 java -jar target/olla-nest-*.jar
```

Or in `application.properties`:
```properties
server.port=8080
```

---

## HTTPS / TLS

Olla Nest does not handle TLS directly. Place it behind a reverse proxy (Nginx, Caddy, or Traefik) for HTTPS.

Once TLS is in place, set `COOKIE_SECURE=true` so the session cookie is only sent over HTTPS.

Example Nginx config:
```nginx
server {
    listen 443 ssl;
    server_name ai.yourcompany.com;

    ssl_certificate     /etc/ssl/certs/your.crt;
    ssl_certificate_key /etc/ssl/private/your.key;

    location / {
        proxy_pass http://localhost:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        # Required for SSE streaming:
        proxy_buffering off;
        proxy_read_timeout 300s;
    }
}
```

---

## Health Check

```bash
curl http://localhost:3000/api/admin/health
```

Returns server uptime, DB stats, JVM memory usage, and Ollama status.
