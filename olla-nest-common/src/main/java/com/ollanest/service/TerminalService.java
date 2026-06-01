package com.ollanest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler that bridges each connected client to a dedicated shell
 * process, providing an in-browser terminal experience.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The Node.js implementation used {@code node-pty} to spawn a PTY-attached
 * shell. The Java migration replaces {@code node-pty} with Java's
 * {@link ProcessBuilder}, which is sufficient for interactive use within the
 * workspace terminal without requiring a native PTY library. Each WebSocket
 * connection receives its own shell process, and a daemon reader thread
 * forwards the process's combined stdout/stderr stream to the WebSocket client
 * in real time.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Process stdout and stderr are merged via
 * {@link ProcessBuilder#redirectErrorStream(boolean)} so a single reader thread
 * handles both streams.</li>
 * <li>The reader thread is marked as a daemon thread so it does not prevent JVM
 * shutdown if a session is somehow not closed cleanly.</li>
 * <li>Processes are stored in a {@link ConcurrentHashMap} keyed by the
 * WebSocket session ID and forcibly destroyed when the session closes,
 * preventing zombie shell processes.</li>
 * <li>Both text and binary WebSocket frames are accepted and forwarded verbatim
 * to the shell's stdin, supporting terminal emulators that may send binary
 * control sequences.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial migration; replaces Node.js {@code node-pty}
 * integration</li>
 * <li>v2026.1.4 — WebSocket upgrade gated by
 * {@link com.ollanest.config.WebSocketAuthInterceptor} (CRIT-1 security fix) —
 * no code changes in this class</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.4
 */
@Service
public class TerminalService extends AbstractWebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(TerminalService.class);

	/** CRIT-2 MITIGATION: JDBC for terminal session audit logging. */
	private final JdbcTemplate db;

	/** Live shell processes keyed by WebSocket session ID. */
	private final ConcurrentHashMap<String, Process> processes = new ConcurrentHashMap<>();

	/** Stdin streams of live processes, keyed by WebSocket session ID. */
	private final ConcurrentHashMap<String, OutputStream> stdinMap = new ConcurrentHashMap<>();

	/** User ID associated with each terminal session (for audit logs). */
	private final ConcurrentHashMap<String, String> sessionUserMap = new ConcurrentHashMap<>();

	/** Input buffer for each session — used to capture commands for audit logging. */
	private final ConcurrentHashMap<String, StringBuilder> inputBuffers = new ConcurrentHashMap<>();

	public TerminalService(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * Called by Spring WebSocket after a new connection is successfully
	 * established.
	 *
	 * <p>
	 * Spawns a shell process appropriate for the host OS ({@code cmd.exe} on
	 * Windows, {@code /bin/bash -i} on POSIX systems), redirects stderr into
	 * stdout, and starts a daemon reader thread that forwards process output to the
	 * WebSocket client.
	 *
	 * @param session the newly established WebSocket session
	 * @throws Exception if the shell process cannot be started or if the initial
	 *                   WebSocket send fails
	 * @since v2026.1.0
	 */
	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		String os = System.getProperty("os.name", "").toLowerCase();
		ProcessBuilder pb;
		if (os.contains("win")) {
			pb = new ProcessBuilder("cmd.exe");
		} else {
			pb = new ProcessBuilder("/bin/bash", "-i");
		}
		pb.environment().put("TERM", "xterm-256color");
		pb.redirectErrorStream(true);

		Process process = pb.start();
		processes.put(session.getId(), process);
		stdinMap.put(session.getId(), process.getOutputStream());

		// Reader thread: forward process stdout to WebSocket
		Thread reader = new Thread(() -> {
			try {
				InputStream is = process.getInputStream();
				byte[] buf = new byte[4096];
				int n;
				while ((n = is.read(buf)) != -1) {
					if (!session.isOpen())
						break;
					String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
					session.sendMessage(new TextMessage(chunk));
				}
			} catch (Exception e) {
				// Terminal closed
			} finally {
				try {
					session.close();
				} catch (Exception ignored) {
				}
			}
		});
		reader.setDaemon(true);
		reader.start();

		// CRIT-2 MITIGATION: Capture user for audit trail
		Object userAttr = session.getAttributes().get("authenticatedUserId");
		String userId = userAttr != null ? userAttr.toString() : "unknown";
		sessionUserMap.put(session.getId(), userId);
		inputBuffers.put(session.getId(), new StringBuilder());

		// Audit log: terminal session opened
		auditTerminalEvent(userId, "terminal.session.open",
			"Terminal session started: wsSessionId=" + session.getId());

		log.info("[terminal] Session started: wsId={} userId={}", session.getId(), userId);
	}

	/**
	 * Forwards an incoming text WebSocket frame to the shell's stdin.
	 *
	 * @param session the WebSocket session that sent the message
	 * @param message the text frame containing terminal input (keystrokes,
	 *                commands)
	 * @throws Exception if the stdin write fails
	 * @since v2026.1.0
	 */
	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
		OutputStream stdin = stdinMap.get(session.getId());
		if (stdin != null) {
			String payload = message.getPayload();
			stdin.write(payload.getBytes(StandardCharsets.UTF_8));
			stdin.flush();

			// CRIT-2 MITIGATION: Buffer input for command-level audit logging.
			// Log each newline-terminated command to the audit trail.
			StringBuilder buf = inputBuffers.get(session.getId());
			if (buf != null) {
				for (char c : payload.toCharArray()) {
					if (c == '\n' || c == '\r') {
						String cmd = buf.toString().trim();
						if (!cmd.isEmpty()) {
							String userId = sessionUserMap.getOrDefault(session.getId(), "unknown");
							auditTerminalEvent(userId, "terminal.command",
								"Terminal input: " + truncateForLog(cmd));
						}
						buf.setLength(0);
					} else {
						buf.append(c);
					}
				}
			}
		}
	}

	/**
	 * Forwards an incoming binary WebSocket frame to the shell's stdin.
	 *
	 * <p>
	 * Accepts binary frames to support terminal emulators that encode control
	 * sequences as raw bytes rather than text.
	 *
	 * @param session the WebSocket session that sent the message
	 * @param message the binary frame
	 * @throws Exception if the stdin write fails
	 * @since v2026.1.0
	 */
	@Override
	protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
		OutputStream stdin = stdinMap.get(session.getId());
		if (stdin != null) {
			stdin.write(message.getPayload().array());
			stdin.flush();
		}
	}

	/**
	 * Called by Spring WebSocket when a connection is closed normally or by the
	 * peer.
	 *
	 * <p>
	 * Forcibly destroys the associated shell process and removes both maps' entries
	 * to prevent resource leaks.
	 *
	 * @param session     the closed WebSocket session
	 * @param closeStatus the close status code and reason phrase
	 * @throws Exception never thrown; declared for API compatibility
	 * @since v2026.1.0
	 */
	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
		String id = session.getId();
		// Close stdin BEFORE destroyForcibly — prevents broken-pipe fd leaks on some
		// JVM/OS combinations where the stream is not released until closed explicitly.
		OutputStream stdin = stdinMap.remove(id);
		if (stdin != null) {
			try { stdin.close(); } catch (IOException ignored) { /* broken pipe is expected */ }
		}
		Process process = processes.remove(id);
		if (process != null) {
			process.destroyForcibly();
		}
		String userId = sessionUserMap.remove(id);
		inputBuffers.remove(id);
		auditTerminalEvent(userId != null ? userId : "unknown", "terminal.session.close",
			"Terminal session closed: wsSessionId=" + id + " status=" + closeStatus.getCode());
		log.info("[terminal] Session closed: wsId={} userId={}", id, userId);
	}

	/**
	 * Called by Spring WebSocket when a transport-level error occurs.
	 *
	 * <p>
	 * Logs the error at {@code WARN} level and delegates to
	 * {@link #afterConnectionClosed(WebSocketSession, CloseStatus)} to ensure the
	 * associated process is cleaned up.
	 *
	 * @param session   the session on which the error occurred
	 * @param exception the transport-level exception
	 * @throws Exception if cleanup fails
	 * @since v2026.1.0
	 */
	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
		log.warn("[terminal] Transport error on {}: {}", session.getId(), exception.getMessage());
		afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
	}

	// ── Private helpers ──────────────────────────────────────────────────────

	/** Writes a terminal audit event to the audit_events table. Silent on failure. */
	private void auditTerminalEvent(String userId, String action, String detail) {
		try {
			String id = "audit-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ Long.toHexString(new java.security.SecureRandom().nextLong() & Long.MAX_VALUE);
			db.update(
				"INSERT INTO audit_events (id, actor, action, detail, extra_json, created_at) VALUES (?,?,?,?,?,?)",
				id, userId, action, detail, "{}", Instant.now().toString());
		} catch (Exception e) {
			log.warn("[terminal] Failed to write audit event: {}", e.getMessage());
		}
	}

	/** Truncates a command string to 500 chars for audit log entries. */
	private static String truncateForLog(String cmd) {
		return cmd.length() > 500 ? cmd.substring(0, 500) + "…[truncated]" : cmd;
	}
}
