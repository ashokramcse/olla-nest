package com.ollanest.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ollanest.model.User;
import com.ollanest.service.AgentLoopService;
import com.ollanest.service.DatabaseService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * REST controller driving the agentic chat loop: starting, cancelling, and
 * reporting the status of agent runs.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * An "agent run" is a multi-step loop where the model can call tools (including
 * shell access) until it produces a final answer. This controller exposes that
 * loop over HTTP, streaming incremental output via SSE while delegating the
 * actual orchestration to {@link AgentLoopService}. Model and endpoint defaults
 * are resolved from persisted settings through {@link DatabaseService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Shell tool access is gated: only admins or users holding the
 * {@code bash:use} right may run tools that execute commands.</li>
 * <li>{@link #run} returns an {@link SseEmitter} with a five-minute timeout
 * that, on expiry, cancels the underlying loop to avoid orphaned work.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — documented as part of the project-wide Javadoc pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController extends BaseController {

	/** Service orchestrating the multi-step agent loop and tool calls. */
	private final AgentLoopService agentLoopService;

	/** Service used to resolve persisted settings (model, endpoint URL). */
	private final DatabaseService databaseService;

	/**
	 * Constructor-injects the agent loop and database services.
	 *
	 * @param agentLoopService the service orchestrating agent runs
	 * @param databaseService  the service used to read persisted settings
	 * @since v2026.2.1
	 */
	public AgentController(AgentLoopService agentLoopService, DatabaseService databaseService) {
		this.agentLoopService = agentLoopService;
		this.databaseService = databaseService;
	}

	/**
	 * Starts an agent run for a session, streaming output over SSE.
	 *
	 * <p>
	 * Resolves the model and Ollama endpoint from the request and persisted
	 * settings, determines whether the caller may use shell tools, and launches the
	 * loop. The emitter cancels the run if it times out.
	 *
	 * @param req       the HTTP request, used to resolve the authenticated user
	 * @param sessionId the chat session the run belongs to
	 * @param body      request payload; {@code messages} is the conversation and
	 *                  optional {@code model} overrides the default
	 * @return an {@link SseEmitter} streaming the agent's incremental output
	 * @since v2026.2.1
	 */
	@PostMapping("/run/{sessionId}")
	public SseEmitter run(HttpServletRequest req, @PathVariable String sessionId,
			@RequestBody Map<String, Object> body) {
		User user = requireAuth(req);

		SseEmitter emitter = new SseEmitter(300_000L); // 5-minute timeout

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> messages = (List<Map<String, Object>>) body.getOrDefault("messages", List.of());
		String ollamaUrl = databaseService.getSetting("ollamaUrl", "http://localhost:11434");
		String model = (String) body.getOrDefault("model", databaseService.getSetting("defaultModel", "llama3.2"));
		boolean canBash = "admin".equals(user.role) || hasRight(user, "bash:use");

		emitter.onTimeout(() -> {
			agentLoopService.cancel(sessionId);
			try {
				emitter.complete();
			} catch (Exception ignore) {
			}
		});

		agentLoopService.runLoop(sessionId, user.id, messages, ollamaUrl, model, canBash, emitter);
		return emitter;
	}

	/**
	 * Cancels the agent run for a session.
	 *
	 * @param req       the HTTP request; authentication is required
	 * @param sessionId the session whose run should be cancelled
	 * @return an OK response acknowledging the cancellation
	 * @since v2026.2.1
	 */
	@PostMapping("/cancel/{sessionId}")
	public ResponseEntity<?> cancel(HttpServletRequest req, @PathVariable String sessionId) {
		requireAuth(req);
		agentLoopService.cancel(sessionId);
		return ok(Map.of("ok", true));
	}

	/**
	 * Reports whether an agent run is in progress for a session.
	 *
	 * @param req       the HTTP request; authentication is required
	 * @param sessionId the session to query
	 * @return an OK response whose {@code running} flag reflects the loop state
	 * @since v2026.2.1
	 */
	@GetMapping("/status/{sessionId}")
	public ResponseEntity<?> status(HttpServletRequest req, @PathVariable String sessionId) {
		requireAuth(req);
		return ok(Map.of("running", agentLoopService.isRunning(sessionId)));
	}

	/**
	 * Null-safe check for whether a user holds a named right.
	 *
	 * @param user  the user to check
	 * @param right the right identifier (e.g. {@code "bash:use"})
	 * @return {@code true} if the user holds the right; {@code false} otherwise
	 * @since v2026.2.1
	 */
	private boolean hasRight(User user, String right) {
		try {
			return user.rights != null && user.rights.contains(right);
		} catch (Exception e) {
			return false;
		}
	}
}
