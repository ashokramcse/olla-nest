package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core chat service: session lifecycle management, context-window budgeting,
 * system-prompt assembly, message persistence, audit logging, and rate
 * limiting.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Encapsulates all chat-related business logic that was previously spread
 * across {@code src/services/chat.js} in the Node.js implementation.
 * Centralising these operations here keeps the streaming controllers thin and
 * ensures that message history trimming, system-prompt construction, audit
 * writes, and rate-limit enforcement are applied consistently regardless of
 * which API endpoint initiates a chat turn.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Context budgeting walks the message history newest-to-oldest and stops
 * when the estimated token budget (model context window minus reserved tokens
 * for the system prompt and current user message) is exhausted. This mirrors
 * the behaviour of the Node.js chat service.</li>
 * <li>Token estimates use a simple character-count / 4 heuristic, which is
 * intentionally conservative to avoid exceeding model limits.</li>
 * <li>The rate-limit map is a process-scoped
 * {@link java.util.concurrent.ConcurrentHashMap} keyed by user ID. It resets
 * the counter whenever more than 60 seconds have elapsed since the first
 * request in the current window.</li>
 * <li>The {@link #uid(String)} helper generates URL-safe, base-36 encoded IDs
 * that sort roughly chronologically, matching the format used by the Node.js ID
 * generator.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial migration from {@code src/services/chat.js}</li>
 * <li>v2026.1.4 — added RAG context support via
 * {@link #buildSystemPromptWithRag(String, RouterService.RouteResult, Map, String, String)};
 * added
 * {@link #appendTrace(String, String, String, String, String, List, List, boolean)}</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.4
 */
@Service
public class ChatService {

	private static final Logger log = LoggerFactory.getLogger(ChatService.class);

	/** JDBC template bound to the application's SQLite data source. */
	private final JdbcTemplate db;

	/** Provides workspace root and file-listing for system-prompt assembly. */
	private final WorkspaceService workspaceService;

	/**
	 * Jackson mapper used for serialising/deserialising JSON fields stored in
	 * SQLite.
	 */
	private final ObjectMapper mapper;

	/** Builds the system prompt string for each chat turn. */
	private final PromptTemplateService promptTemplateService;

	/**
	 * Constructs the service by injecting all required collaborators.
	 *
	 * @param db                    the JDBC template for SQLite access
	 * @param workspaceService      workspace root and file listing helper
	 * @param mapper                Jackson {@link ObjectMapper} for JSON fields
	 * @param promptTemplateService system-prompt builder
	 * @since v2026.1.0
	 */
	public ChatService(JdbcTemplate db, WorkspaceService workspaceService,
			ObjectMapper mapper, PromptTemplateService promptTemplateService) {
		this.db = db;
		this.workspaceService = workspaceService;
		this.mapper = mapper;
		this.promptTemplateService = promptTemplateService;
	}

	/**
	 * Estimates the number of tokens in the given text using a
	 * 4-characters-per-token heuristic.
	 *
	 * @param text the text to estimate; may be {@code null}
	 * @return the estimated token count, always &ge; 0
	 * @since v2026.1.0
	 */
	public int estimateTokens(String text) {
		return (int) Math.ceil((text != null ? text.length() : 0) / 4.0);
	}

	/**
	 * Looks up the context window size (in tokens) for the given model name.
	 *
	 * <p>
	 * Checks the {@code models} table first (Ollama models), then the
	 * {@code api_models} table (external API models). Falls back to 8192 tokens if
	 * neither table contains a positive value for the model.
	 *
	 * @param modelName the model reference string or display name to look up
	 * @return the context window size in tokens, or 8192 as a safe default
	 * @since v2026.1.0
	 */
	public long getModelContextWindow(String modelName) {
		List<Map<String, Object>> rows = db.queryForList(
				"SELECT context_size FROM models WHERE model_ref = ? OR name = ? LIMIT 1", modelName, modelName);
		if (!rows.isEmpty()) {
			Object cs = rows.get(0).get("context_size");
			if (cs != null && ((Number) cs).longValue() > 0)
				return ((Number) cs).longValue();
		}
		List<Map<String, Object>> apiRows = db
				.queryForList("SELECT context_window FROM api_models WHERE model_id = ? LIMIT 1", modelName);
		if (!apiRows.isEmpty()) {
			Object cw = apiRows.get(0).get("context_window");
			if (cw != null && ((Number) cw).longValue() > 0)
				return ((Number) cw).longValue();
		}
		return 8192;
	}

	/**
	 * Builds the ordered message list to send to the AI model for one chat turn.
	 *
	 * <p>
	 * The list is structured as:
	 * <ol>
	 * <li>A {@code system} message containing {@code systemPrompt}.</li>
	 * <li>Zero or more historical {@code user}/{@code assistant} messages from the
	 * session, trimmed to fit within the model's context window.</li>
	 * <li>The current {@code user} message, optionally with base64-encoded
	 * {@code images}.</li>
	 * </ol>
	 *
	 * <p>
	 * History trimming walks from the most recent message backwards, accumulating
	 * messages until the remaining token budget is exhausted.
	 *
	 * @param sessionId    the chat session whose history should be included
	 * @param systemPrompt the assembled system prompt for this turn
	 * @param userMessage  the current user message text
	 * @param modelName    the model reference used to look up the context window
	 *                     size
	 * @param images       optional list of base64-encoded image strings; may be
	 *                     {@code null} or empty
	 * @return an ordered list of message maps with {@code role} and {@code content}
	 *         keys, ready to pass to the model API
	 * @since v2026.1.0
	 */
	public List<Map<String, Object>> buildContextMessages(String sessionId, String systemPrompt, String userMessage,
			String modelName, List<String> images) {
		long tokenLimit = getModelContextWindow(modelName);
		int reserved = estimateTokens(systemPrompt) + estimateTokens(userMessage) + 512;
		long budget = Math.max(0, tokenLimit - reserved);

		List<Map<String, Object>> history = db.queryForList(
				"SELECT role, content FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC", sessionId);

		// Walk newest->oldest, keep what fits
		Deque<Map<String, Object>> kept = new ArrayDeque<>();
		for (int i = history.size() - 1; i >= 0; i--) {
			Map<String, Object> msg = history.get(i);
			int tokens = estimateTokens((String) msg.get("content"));
			if (budget - tokens < 0)
				break;
			budget -= tokens;
			kept.addFirst(msg);
		}

		List<Map<String, Object>> result = new ArrayList<>();
		Map<String, Object> sysMsg = new LinkedHashMap<>();
		sysMsg.put("role", "system");
		sysMsg.put("content", systemPrompt);
		result.add(sysMsg);

		for (Map<String, Object> h : kept) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("role", h.get("role"));
			m.put("content", h.get("content"));
			result.add(m);
		}

		Map<String, Object> userMsg = new LinkedHashMap<>();
		userMsg.put("role", "user");
		userMsg.put("content", userMessage);
		if (images != null && !images.isEmpty())
			userMsg.put("images", images);
		result.add(userMsg);
		return result;
	}

	/**
	 * Builds the system prompt without a RAG context block.
	 *
	 * <p>
	 * Delegates to
	 * {@link #buildSystemPromptWithRag(String, RouterService.RouteResult, Map, String, String)}
	 * with a {@code null} RAG context.
	 *
	 * @param mode             the active chat mode (e.g. {@code "ask"},
	 *                         {@code "build"})
	 * @param route            the router result containing the selected model and
	 *                         reason
	 * @param workspace        the user's resolved workspace map (may be
	 *                         {@code null})
	 * @param projectKnowledge free-text project knowledge to include in the prompt
	 * @return the assembled system prompt string
	 * @since v2026.1.0
	 */
	public String buildSystemPrompt(String mode, RouterService.RouteResult route, Map<String, Object> workspace,
			String projectKnowledge) {
		return buildSystemPromptWithRag(mode, route, workspace, projectKnowledge, null);
	}

	/**
	 * Builds the system prompt, optionally including a RAG retrieval context block.
	 *
	 * <p>
	 * Constructs the workspace section string (active folder path, permission mode,
	 * file list) then delegates final assembly to
	 * {@link PromptTemplateService#buildSystemPrompt}.
	 *
	 * @param mode             the active chat mode (e.g. {@code "ask"},
	 *                         {@code "build"})
	 * @param route            the router result containing the selected model and
	 *                         reason
	 * @param workspace        the user's resolved workspace map; may be
	 *                         {@code null}
	 * @param projectKnowledge free-text project knowledge; may be {@code null}
	 * @param ragContext       pre-formatted RAG retrieval snippets; may be
	 *                         {@code null}
	 * @return the assembled system prompt string
	 * @since v2026.1.4
	 */
	public String buildSystemPromptWithRag(String mode, RouterService.RouteResult route, Map<String, Object> workspace,
			String projectKnowledge, String ragContext) {
		String modelName = route.selected != null ? route.selected.name : "auto";
		String routeReason = route.reason;

		// Build workspace section string (same detail as before)
		StringBuilder wsSection = new StringBuilder();
		if (workspace != null && workspace.get("workspaceRoot") != null) {
			String wsRoot = (String) workspace.get("workspaceRoot");
			wsSection.append("Active project folder: ").append(wsRoot).append("\n");
			wsSection.append("Write permission mode: ").append(workspace.getOrDefault("permissionMode", "default"))
					.append("\n");
			wsSection.append(
					"When building, fixing, or generating files, treat the active project folder as the working directory.\n");
			wsSection.append(
					"IMPORTANT: When generating code files, always specify the filename in the code fence header using the format: ```language:filename.ext — for example: ```html:index.html or ```jsx:src/App.jsx or ```css:styles.css. Use relative paths from the project root. This allows files to be saved directly to the workspace.\n");
			List<String> files = workspaceService.listWorkspaceFiles(wsRoot);
			if (!files.isEmpty()) {
				wsSection.append("Current project files:\n");
				for (String f : files)
					wsSection.append("  ").append(f).append("\n");
			} else {
				wsSection.append("Current project files: (empty — this is a new project)");
			}
		}

		return promptTemplateService.buildSystemPrompt(mode, modelName, routeReason,
				wsSection.length() > 0 ? wsSection.toString().trim() : null, projectKnowledge, ragContext);
	}

	/**
	 * Returns the currently active chat session for the user, creating a fresh
	 * session (with a welcome assistant message) if none exists.
	 *
	 * @param userId the user's unique identifier
	 * @return a fully assembled chat map including messages, session metadata, and
	 *         boolean flags
	 * @since v2026.1.0
	 */
	public Map<String, Object> getActiveChat(String userId) {
		List<Map<String, Object>> sessions = db.queryForList(
				"SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 1 ORDER BY updated_at DESC LIMIT 1",
				userId);
		if (sessions.isEmpty()) {
			String now = Instant.now().toString();
			String sessionId = uid("chat");
			db.update(
					"INSERT INTO chat_sessions (id, user_id, title, pinned, archived, unread, is_active, created_at, updated_at) "
							+ "VALUES (?, ?, ?, 0, 0, 0, 1, ?, ?)",
					sessionId, userId, "New Chat", now, now);
			db.update(
					"INSERT INTO chat_messages (id, session_id, role, content, model_name, live, created_at) "
							+ "VALUES (?, ?, ?, ?, ?, 1, ?)",
					uid("msg"), sessionId, "assistant",
					"Ready. Ask once, and Auto Router will choose the best approved model for your task.", "Olla Nest",
					now);
			sessions = db.queryForList("SELECT * FROM chat_sessions WHERE id = ?", sessionId);
		}
		return buildChatObject(sessions.get(0));
	}

	/**
	 * Returns the raw session row for the user's active chat, or {@code null} if no
	 * active session exists.
	 *
	 * @param userId the user's unique identifier
	 * @return a {@link Map} of column name to value from the {@code chat_sessions}
	 *         table, or {@code null}
	 * @since v2026.1.0
	 */
	public Map<String, Object> getActiveChatSession(String userId) {
		List<Map<String, Object>> sessions = db.queryForList(
				"SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 1 ORDER BY updated_at DESC LIMIT 1",
				userId);
		return sessions.isEmpty() ? null : sessions.get(0);
	}

	/**
	 * Archives the user's current active chat session, provided it contains at
	 * least one user message. Sessions with only the initial assistant welcome
	 * message are not archived.
	 *
	 * @param userId the user whose active session should be archived
	 * @since v2026.1.0
	 */
	public void archiveCurrentChat(String userId) {
		Map<String, Object> session = getActiveChatSession(userId);
		if (session == null)
			return;
		String sessionId = (String) session.get("id");
		List<Map<String, Object>> userMsgs = db
				.queryForList("SELECT id FROM chat_messages WHERE session_id = ? AND role = 'user' LIMIT 1", sessionId);
		if (userMsgs.isEmpty())
			return;
		db.update("UPDATE chat_sessions SET is_active = 0, updated_at = ? WHERE id = ?", Instant.now().toString(),
				sessionId);
	}

	/**
	 * Builds a fully assembled chat object from a raw session row.
	 *
	 * <p>
	 * The returned map includes all session metadata fields ({@code id},
	 * {@code userId}, {@code title}, {@code pinned}, {@code archived},
	 * {@code unread}, {@code isActive}, {@code createdAt}, {@code updatedAt}) and a
	 * {@code messages} list produced by calling {@link #parseMessage(Map)} on each
	 * row.
	 *
	 * @param session a raw row from the {@code chat_sessions} table
	 * @return a complete chat map suitable for JSON serialisation
	 * @since v2026.1.0
	 */
	public Map<String, Object> buildChatObject(Map<String, Object> session) {
		String sessionId = (String) session.get("id");
		// LOW-8 FIX: Cap message history to prevent unbounded memory allocation for
		// sessions with thousands of messages. 500 messages covers all practical use
		// cases; the context budgeting in buildContextMessages() trims further anyway.
		List<Map<String, Object>> msgs = db.queryForList(
				"SELECT * FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC LIMIT 500", sessionId);

		Map<String, Object> chat = new LinkedHashMap<>();
		chat.put("id", sessionId);
		chat.put("userId", session.get("user_id"));
		chat.put("title", session.get("title"));
		chat.put("pinned", boolVal(session, "pinned"));
		chat.put("archived", boolVal(session, "archived"));
		chat.put("unread", boolVal(session, "unread"));
		chat.put("isActive", boolVal(session, "is_active"));
		List<Map<String, Object>> parsedMsgs = new ArrayList<>();
		for (Map<String, Object> m : msgs)
			parsedMsgs.add(parseMessage(m));
		chat.put("messages", parsedMsgs);
		chat.put("createdAt", session.get("created_at"));
		chat.put("updatedAt", session.get("updated_at"));
		return chat;
	}

	/**
	 * Converts a raw {@code chat_messages} row into a client-facing message map.
	 *
	 * <p>
	 * Normalises the {@code live} column (stored as an integer in SQLite) to a
	 * boolean, and deserialises the {@code artifacts_json} and
	 * {@code extracted_files_json} columns from their JSON string representation
	 * into {@link List} values.
	 *
	 * @param row a raw row from the {@code chat_messages} table
	 * @return a normalised message map suitable for JSON serialisation
	 * @since v2026.1.0
	 */
	public Map<String, Object> parseMessage(Map<String, Object> row) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", row.get("id"));
		m.put("role", row.get("role"));
		m.put("content", row.get("content"));
		Object mode = row.get("mode");
		if (mode != null)
			m.put("mode", mode);
		Object modelId = row.get("model_id");
		if (modelId != null)
			m.put("modelId", modelId);
		Object modelName = row.get("model_name");
		if (modelName != null)
			m.put("modelName", modelName);
		Object routeReason = row.get("route_reason");
		if (routeReason != null)
			m.put("routeReason", routeReason);
		Object liveVal = row.get("live");
		m.put("live", liveVal == null || (liveVal instanceof Number ? ((Number) liveVal).intValue() != 0
				: Boolean.parseBoolean(liveVal.toString())));
		m.put("artifacts", safeJsonList(str(row, "artifacts_json")));
		m.put("extractedFiles", safeJsonList(str(row, "extracted_files_json")));
		m.put("createdAt", row.get("created_at"));
		return m;
	}

	/**
	 * Appends an immutable audit event to the {@code audit_events} table.
	 *
	 * <p>
	 * Any serialisation or database error is logged at {@code WARN} level and
	 * silently discarded so that an audit failure never affects the calling
	 * request.
	 *
	 * @param actor  the actor identifier (user ID or system component name)
	 * @param action a short machine-readable action code, e.g. {@code "chat.send"}
	 * @param detail a human-readable description of the event; may be {@code null}
	 * @param extra  a map of additional key-value metadata to serialise as JSON;
	 *               may be {@code null}
	 * @since v2026.1.0
	 */
	public void appendAudit(String actor, String action, String detail, Map<String, Object> extra) {
		try {
			String extraJson = mapper.writeValueAsString(extra != null ? extra : Map.of());
			db.update(
					"INSERT INTO audit_events (id, actor, action, detail, extra_json, created_at) "
							+ "VALUES (?, ?, ?, ?, ?, ?)",
					uid("audit"), actor, action, detail != null ? detail : "", extraJson, Instant.now().toString());
		} catch (Exception e) {
			log.warn("[audit] Failed to write audit event: {}", e.getMessage());
		}
	}

	/**
	 * Appends a router trace record to the {@code router_traces} table for
	 * observability and debugging of routing decisions.
	 *
	 * <p>
	 * Any serialisation or database error is logged at {@code WARN} level and
	 * silently discarded.
	 *
	 * @param userId          the requesting user's ID
	 * @param sessionId       the chat session ID associated with this turn
	 * @param message         the user's message text; may be {@code null}
	 * @param mode            the active chat mode; may be {@code null}
	 * @param selectedModelId the ID of the model selected by the router; may be
	 *                        {@code null}
	 * @param tags            capability tags used during routing; may be
	 *                        {@code null}
	 * @param candidates      list of candidate model maps considered by the router;
	 *                        may be {@code null}
	 * @param live            {@code true} if this was a live streaming turn
	 * @since v2026.1.4
	 */
	public void appendTrace(String userId, String sessionId, String message, String mode, String selectedModelId,
			List<String> tags, List<Map<String, Object>> candidates, boolean live) {
		try {
			db.update("INSERT INTO router_traces "
					+ "(id, user_id, session_id, message, mode, selected_model_id, tags_json, candidates_json, live, created_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", uid("trace"), userId, sessionId,
					message != null ? message : "", mode != null ? mode : "", selectedModelId,
					mapper.writeValueAsString(tags != null ? tags : List.of()),
					mapper.writeValueAsString(candidates != null ? candidates : List.of()), live ? 1 : 0,
					Instant.now().toString());
		} catch (Exception e) {
			log.warn("[trace] Failed to write trace: {}", e.getMessage());
		}
	}

	// Rate limit tracking: per-user, 60-second sliding window.
	// Each entry is a 2-element AtomicLong[]: [count, windowStartMs].
	// Using AtomicLong prevents the race condition where two concurrent threads
	// could both read count < limit and both increment past the ceiling.
	private final ConcurrentHashMap<String, AtomicLong[]>
			rateLimitMap = new ConcurrentHashMap<>();

	/**
	 * Checks and updates the in-memory rate limit for a user within a 60-second
	 * sliding window.
	 *
	 * <p>
	 * Thread-safe: the read-increment-check is performed under {@code compute}
	 * so concurrent requests for the same user cannot both slip past the ceiling.
	 *
	 * @param userId         the user to check; must not be {@code null}
	 * @param limitPerMinute the maximum number of requests allowed per 60 seconds;
	 *                       values &le; 0 disable rate limiting (always returns
	 *                       {@code true})
	 * @return {@code true} if the request is within the rate limit, {@code false}
	 *         if the limit has been exceeded
	 * @since v2026.1.0
	 * @since v2026.1.9 — fixed race condition: counter operations are now atomic
	 */
	public boolean checkChatRateLimit(String userId, long limitPerMinute) {
		if (limitPerMinute <= 0)
			return true;
		long now = System.currentTimeMillis();
		long windowStart = now - 60_000L;
		// compute() is atomic per key — guarantees no two threads can both see
		// count < limit and both decide to allow the request.
		long[] result = new long[1];
		rateLimitMap.compute(userId, (k, entry) -> {
			if (entry == null) {
				entry = new AtomicLong[] {
					new AtomicLong(0),
					new AtomicLong(now)
				};
			}
			// Reset window if more than 60 s have elapsed
			if (entry[1].get() < windowStart) {
				entry[0].set(0);
				entry[1].set(now);
			}
			long current = entry[0].get();
			if (current < limitPerMinute) {
				entry[0].incrementAndGet();
				result[0] = 1; // allowed
			} else {
				result[0] = 0; // denied
			}
			return entry;
		});
		return result[0] == 1;
	}

	/**
	 * Scheduled cleanup that evicts stale rate-limit entries whose window has
	 * expired by more than 5 minutes.
	 *
	 * <p>
	 * Without this sweep the map would accumulate one entry per unique userId
	 * forever, leaking memory in long-running deployments. Runs every 10 minutes
	 * with a 5-minute initial delay to avoid startup noise.
	 *
	 * @since v2026.1.9
	 */
	@Scheduled(fixedDelay = 600_000, initialDelay = 300_000)
	public void cleanStaleRateLimitEntries() {
		long staleThreshold = System.currentTimeMillis() - 5 * 60_000L; // 5 min after window expiry
		int removed = 0;
		for (Iterator<Map.Entry<String,
				AtomicLong[]>> it =
				rateLimitMap.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, AtomicLong[]> e = it.next();
			// entry[1] = windowStartMs; if it expired more than 5 min ago, evict
			if (e.getValue()[1].get() < staleThreshold) {
				it.remove();
				removed++;
			}
		}
		if (removed > 0) {
			log.debug("[ratelimit] Evicted {} stale rate-limit entries", removed);
		}
	}

	/**
	 * Generates a URL-safe, base-36 encoded unique identifier with a given prefix.
	 *
	 * <p>
	 * The format is {@code <prefix>-<milliseconds-base36>-<random-base36>}, which
	 * sorts roughly chronologically and has negligible collision probability for
	 * the volumes expected in a single-tenant Olla Nest deployment.
	 *
	 * @param prefix a short alphanumeric prefix (e.g. {@code "chat"},
	 *               {@code "msg"})
	 * @return a unique, URL-safe identifier string
	 * @since v2026.1.0
	 */
	/** Cryptographically secure RNG used by {@link #uid(String)} for the random suffix. */
	private static final SecureRandom UID_RNG = new SecureRandom();

	/**
	 * @since v2026.1.9 — replaced {@code Math.random()} with {@link java.security.SecureRandom}
	 *        to eliminate the statistical collision risk under high-throughput burst loads
	 *        and to satisfy cryptographic unpredictability requirements for audit IDs.
	 */
	public String uid(String prefix) {
		// Use full 63-bit positive long for the random segment — eliminates birthday-paradox
		// collisions that were possible when only 36^6 ≈ 2.1B values were used.
		long rand = UID_RNG.nextLong() & Long.MAX_VALUE; // strip sign bit → always positive
		return prefix + "-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ Long.toString(rand, 36);
	}

	/**
	 * Deserialises a JSON array string into a {@link List}, returning an empty list
	 * on any parse error or blank input.
	 *
	 * @param json a JSON array string; may be {@code null} or blank
	 * @return the parsed list, or an empty list if parsing fails
	 */
	private List<Object> safeJsonList(String json) {
		try {
			if (json == null || json.isBlank())
				return new ArrayList<>();
			return mapper.readValue(json, new TypeReference<List<Object>>() {
			});
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	/**
	 * Returns the string value of a map entry, or {@code null} if the key is absent
	 * or its value is {@code null}.
	 *
	 * @param row the source map
	 * @param key the key to look up
	 * @return the value as a string, or {@code null}
	 */
	private String str(Map<String, Object> row, String key) {
		Object v = row.get(key);
		return v != null ? v.toString() : null;
	}

	/**
	 * Interprets a map entry as a boolean, supporting {@code Boolean},
	 * {@code Number} (non-zero = true), and {@code String} ({@code "1"} or
	 * {@code "true"}) values.
	 *
	 * @param row the source map
	 * @param key the key to look up
	 * @return the boolean interpretation of the value, or {@code false} if absent
	 */
	private boolean boolVal(Map<String, Object> row, String key) {
		Object v = row.get(key);
		if (v == null)
			return false;
		if (v instanceof Boolean)
			return (Boolean) v;
		if (v instanceof Number)
			return ((Number) v).intValue() != 0;
		return "1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString());
	}
}
