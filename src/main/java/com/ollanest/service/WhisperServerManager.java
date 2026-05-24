package com.ollanest.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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
 * <h3>Startup behaviour</h3>
 * <ul>
 * <li>Locates the project root by walking up from the running JAR / class
 * directory until it finds {@code scripts/whisper_server.py}.</li>
 * <li>Resolves the virtual-environment Python interpreter at
 * {@code scripts/venv/bin/python}. If the venv is absent the server is not
 * started and a clear warning is logged.</li>
 * <li>Skips startup if a server is already listening on the configured port
 * ({@code WHISPER_PORT}, default {@code 8765}).</li>
 * <li>Streams the Python process stdout/stderr to the application log at
 * {@code INFO} level so operators can see Whisper model-load progress.</li>
 * </ul>
 *
 * <h3>Shutdown behaviour</h3>
 * <p>
 * {@link #stop()} is annotated with {@link PreDestroy} so Spring calls it
 * automatically on graceful shutdown. The process receives {@code SIGTERM} via
 * {@link Process#destroy()}; if it has not exited within 3 seconds it is
 * force-killed.
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.5</b> — initial creation; auto-start local Whisper STT
 * server on port 8765 as part of application startup.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.5
 * @version v2026.1.5
 */
@Component
public class WhisperServerManager {

	private static final Logger log = LoggerFactory.getLogger(WhisperServerManager.class);

	/** Port the local Whisper server listens on. */
	private static final int WHISPER_PORT = 8765;

	/** Relative path from project root to the server script. */
	private static final String SCRIPT_RELATIVE = "scripts/whisper_server.py";

	/** Relative path from project root to the venv Python binary. */
	private static final String PYTHON_RELATIVE = "scripts/venv/bin/python";

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
				log.warn("[whisper] Virtual environment not found at {} — run setup first:", pythonPath);
				log.warn("[whisper]   bash {}/scripts/start_whisper.sh", projectRoot);
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
						log.info("[whisper] {}", line);
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
	@PreDestroy
	public void stop() {
		if (whisperProcess != null && whisperProcess.isAlive()) {
			log.info("[whisper] Stopping local Whisper server…");
			whisperProcess.destroy();
			try {
				if (!whisperProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
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
					new URL("http://localhost:" + WHISPER_PORT + "/health").openConnection();
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
