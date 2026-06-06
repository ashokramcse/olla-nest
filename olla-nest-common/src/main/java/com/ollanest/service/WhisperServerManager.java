package com.ollanest.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manages the lifecycle of the local faster-whisper HTTP server process.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Rather than requiring users to manually start a separate Python process for
 * voice transcription, {@code WhisperServerManager} launches
 * {@code scripts/whisper_server.py} automatically when Olla Nest starts and
 * shuts it down cleanly when the application stops.
 *
 * <h3>Cross-platform support</h3>
 * <p>
 * Supports macOS, Linux (Ubuntu/Debian/RHEL/CentOS/Alpine), Windows, and
 * Windows Server. The virtual-environment Python binary path differs by OS:
 * <ul>
 * <li>macOS / Linux — {@code scripts/venv/bin/python}</li>
 * <li>Windows / Windows Server — {@code scripts\venv\Scripts\python.exe}</li>
 * </ul>
 *
 * <h3>Startup behaviour</h3>
 * <ul>
 * <li>Locates the project root by walking up from the running JAR / class
 * directory until it finds {@code scripts/whisper_server.py}.</li>
 * <li>Resolves the virtual-environment Python interpreter for the current OS.
 * If the venv is absent the server is not started and a clear warning with
 * platform-specific setup instructions is logged.</li>
 * <li>Skips startup if a server is already listening on the configured port
 * ({@code WHISPER_PORT}, default {@code 8765}).</li>
 * <li>Streams the Python process stdout/stderr to the application log so
 * operators can see Whisper model-load progress.</li>
 * </ul>
 *
 * <h3>Shutdown behaviour</h3>
 * <p>
 * {@link #stop()} is annotated with {@link PreDestroy} so Spring calls it
 * automatically on graceful shutdown. The process receives {@code SIGTERM} via
 * {@link Process#destroy()}; if it has not exited within 5 seconds it is
 * force-killed.
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.5</b> — initial creation; auto-start local Whisper STT
 * server on port 8765 as part of application startup.</li>
 * <li><b>v2026.1.6</b> — full cross-platform support: macOS, Linux VPS,
 * Windows, Windows Server; OS-specific Python venv path resolution;
 * platform-specific setup instructions in warning messages.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.5
 * @version v2026.1.6
 */
@Component
public class WhisperServerManager {

	private static final Logger log = LoggerFactory.getLogger(WhisperServerManager.class);

	/** Port the local Whisper server listens on. */
	private static final int WHISPER_PORT = 8765;

	/** Relative path from project root to the server script (forward slashes — Java resolves on all OS). */
	private static final String SCRIPT_RELATIVE = "scripts/whisper_server.py";

	/** {@code true} when running on any Windows variant. */
	private static final boolean IS_WINDOWS =
			System.getProperty("os.name", "").toLowerCase().contains("win");

	/**
	 * Venv Python binary path, relative to project root.
	 * <ul>
	 * <li>Windows — {@code scripts/venv/Scripts/python.exe}</li>
	 * <li>macOS / Linux — {@code scripts/venv/bin/python}</li>
	 * </ul>
	 * The venv must be created by {@code start_whisper.sh} (or {@code .bat} /
	 * {@code .ps1} on Windows) which enforces Python 3.11 so that all
	 * faster-whisper binary wheels are available.
	 */
	private static final String PYTHON_RELATIVE = IS_WINDOWS
			? "scripts/venv/Scripts/python.exe"
			: "scripts/venv/bin/python";

	private Process whisperProcess;

	/**
	 * Starts the local Whisper server at application startup.
	 *
	 * <p>
	 * Called automatically by Spring after all beans are initialised (via
	 * constructor injection — this bean has no hard dependencies, so Spring
	 * creates it early). The server process is started in a background thread
	 * so the main startup sequence is not blocked while the Whisper model loads.
	 *
	 * @since v2026.1.5
	 */
	public WhisperServerManager() {
		Thread t = new Thread(this::start, "whisper-server-start");
		t.setDaemon(true);
		t.start();
	}

	// ── Startup ──────────────────────────────────────────────────────────────

	/**
	 * Locates and launches the local Whisper STT server subprocess.
	 *
	 * <p>
	 * Runs on a daemon thread so it does not block Spring context startup.
	 * The method performs the following steps in order:
	 * <ol>
	 * <li>Sleeps 500 ms to allow Spring to finish wiring beans before logging.</li>
	 * <li>Checks whether a Whisper server is already listening on
	 *     {@link #WHISPER_PORT}; skips launch if so.</li>
	 * <li>Walks parent directories from the working directory to find the
	 *     project root (identified by {@code scripts/whisper_server.py}).</li>
	 * <li>Verifies that the Python virtual environment exists at
	 *     {@code scripts/venv/}; logs a platform-specific setup hint and returns
	 *     if it is missing.</li>
	 * <li>Starts the Python subprocess via {@link ProcessBuilder}, streams its
	 *     stdout to the application log on a second daemon thread, and stores
	 *     a reference to the {@link Process} so {@link #stop()} can terminate
	 *     it cleanly on shutdown.</li>
	 * </ol>
	 *
	 * <p>
	 * Any exception is caught and logged at {@code WARN} level so that a missing
	 * Whisper installation never prevents the main application from starting.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.5
	 */
	private void start() {
		try {
			// Give Spring a moment to finish context wiring before logging
			Thread.sleep(500);

			if (isAlreadyRunning()) {
				log.info("[whisper] Server already running on port {} — skipping launch", WHISPER_PORT);
				return;
			}

			Path projectRoot = findProjectRoot();
			if (projectRoot == null) {
				log.warn("[whisper] Could not locate project root (scripts/whisper_server.py not found) — skipping auto-start");
				return;
			}

			Path pythonPath = projectRoot.resolve(PYTHON_RELATIVE);
			Path scriptPath = projectRoot.resolve(SCRIPT_RELATIVE);

			if (!Files.exists(pythonPath)) {
				log.warn("[whisper] Virtual environment not found at {} — voice STT will be unavailable.", pythonPath);
				log.warn("[whisper] Run the one-time setup for your platform:");
				if (IS_WINDOWS) {
					log.warn("[whisper]   Windows / Windows Server:");
					log.warn("[whisper]     scripts\\start_whisper.bat");
				} else if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
					log.warn("[whisper]   macOS:");
					log.warn("[whisper]     bash scripts/start_whisper.sh");
				} else {
					log.warn("[whisper]   Linux (Ubuntu/Debian/RHEL/CentOS/Alpine):");
					log.warn("[whisper]     bash scripts/start_whisper.sh");
				}
				return;
			}

			log.info("[whisper] Starting local Whisper STT server on port {}…", WHISPER_PORT);

			ProcessBuilder pb = new ProcessBuilder(pythonPath.toString(), scriptPath.toString());
			pb.environment().put("WHISPER_PORT", String.valueOf(WHISPER_PORT));
			pb.redirectErrorStream(true);
			pb.directory(projectRoot.toFile());

			whisperProcess = pb.start();

			// Stream process output to application log
			Process proc = whisperProcess;
			Thread logger = new Thread(() -> {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(proc.getInputStream()))) {
					String line;
					while ((line = br.readLine()) != null) {
						// Python script already prefixes lines with [whisper] — log as-is
						log.info("{}", line);
					}
				} catch (Exception ignored) {
				}
			}, "whisper-server-log");
			logger.setDaemon(true);
			logger.start();

			log.info("[whisper] Process started (PID {})", proc.pid());

		} catch (Exception e) {
			log.warn("[whisper] Could not auto-start Whisper server: {}", e.getMessage());
		}
	}

	// ── Shutdown ─────────────────────────────────────────────────────────────

	/**
	 * Stops the Whisper server process on application shutdown.
	 *
	 * @since v2026.1.5
	 */
	/**
	 * Stops the managed Whisper server process.
	 *
	 * <p>Sends {@code SIGTERM} via {@link Process#destroy()}; if the process has not
	 * exited within 5 seconds it is force-killed with {@link Process#destroyForcibly()}.
	 * Safe to call when the process was never started (no-op).
	 *
	 * @since v2026.1.5
	 */
	@PreDestroy
	public void stop() {
		if (whisperProcess != null && whisperProcess.isAlive()) {
			log.info("[whisper] Stopping local Whisper server…");
			whisperProcess.destroy();
			try {
				if (!whisperProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
					whisperProcess.destroyForcibly();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			log.info("[whisper] Whisper server stopped.");
		}
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	/**
	 * Returns {@code true} if something is already listening on
	 * {@link #WHISPER_PORT}.
	 */
	private boolean isAlreadyRunning() {
		try {
			HttpURLConnection conn = (HttpURLConnection)
					URI.create("http://localhost:" + WHISPER_PORT + "/health").toURL().openConnection();
			conn.setConnectTimeout(800);
			conn.setReadTimeout(800);
			conn.setRequestMethod("GET");
			int code = conn.getResponseCode();
			conn.disconnect();
			return code == 200;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Walks up from the JVM working directory to find the project root —
	 * the first ancestor directory that contains
	 * {@code scripts/whisper_server.py}.
	 *
	 * @return the project root path, or {@code null} if not found
	 */
	private Path findProjectRoot() {
		Path candidate = Paths.get("").toAbsolutePath();
		for (int depth = 0; depth < 6; depth++) {
			if (Files.exists(candidate.resolve(SCRIPT_RELATIVE))) {
				return candidate;
			}
			Path parent = candidate.getParent();
			if (parent == null)
				break;
			candidate = parent;
		}
		return null;
	}
}
