package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.ChatService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages chat thread (session) lifecycle operations for the authenticated
 * user.
 *
 * <p>
 * All endpoints are scoped to the current user's own sessions — IDOR protection
 * is enforced by including {@code user_id = ?} in every DB query. Endpoints:
 * <ul>
 * <li>{@code GET /api/threads} — list active and history sessions</li>
 * <li>{@code DELETE /api/threads/{id}} — delete a thread and all its
 * messages</li>
 * <li>{@code PATCH /api/threads/{id}} — update title, pinned, archived, or
 * unread</li>
 * <li>{@code POST /api/threads/{id}/activate} — make a history thread
 * active</li>
 * <li>{@code POST /api/threads/{id}/fork} — create a new thread copied from an
 * existing one</li>
 * </ul>
 *
 * <p>
 * <b>Design decisions:</b>
 * <ul>
 * <li>The PATCH handler uses an allowlist of known updatable columns so the
 * request body cannot overwrite arbitrary DB columns.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0 — initial Java Spring Boot migration
 */
@RestController
@RequestMapping("/api/threads")
public class ThreadController extends BaseController {

	/** JDBC template for all thread and message DB queries. */
	private final JdbcTemplate db;

	/** Used to build chat objects and archive the current active session. */
	private final ChatService chatService;

	/**
	 * Constructor-injects the JDBC template and chat service.
	 *
	 * @param db          the JDBC template
	 * @param chatService the chat session helper
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public ThreadController(JdbcTemplate db, ChatService chatService) {
		this.db = db;
		this.chatService = chatService;
	}

	/**
	 * Lists the current active thread and the user's chat history threads.
	 *
	 * <p>
	 * Returns at most the 50 most-recently-updated history sessions, sorted by
	 * pinned first, then by update time.
	 *
	 * @param req the current HTTP request (for auth check)
	 * @return 200 OK with {@code {active: chat|{}, history: [chat]}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@GetMapping
	public ResponseEntity<Map<String, Object>> listThreads(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);
		List<Map<String, Object>> activeSessions = db.queryForList(
				"SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 1 " + "ORDER BY updated_at DESC",
				user.id);
		List<Map<String, Object>> historySessions = db
				.queryForList("SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 0 "
						+ "ORDER BY pinned DESC, updated_at DESC", user.id);
		Map<String, Object> active = activeSessions.isEmpty() ? null
				: chatService.buildChatObject(activeSessions.get(0));
		List<Object> history = new ArrayList<>();
		for (Map<String, Object> s : historySessions)
			history.add(chatService.buildChatObject(s));
		return ResponseEntity.ok(Map.of("active", active != null ? active : Map.of(), "history", history));
	}

	/**
	 * Deletes a thread and all of its messages.
	 *
	 * <p>
	 * Returns 404 if the thread does not exist or belongs to another user.
	 *
	 * @param id  the thread ID to delete
	 * @param req the current HTTP request (for auth check)
	 * @return 200 OK {@code {ok: true}}, or 404 if not found
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Map<String, Object>> deleteThread(@PathVariable String id, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);
		List<Map<String, Object>> sessions = db
				.queryForList("SELECT id FROM chat_sessions WHERE id = ? AND user_id = ?", id, user.id);
		if (sessions.isEmpty())
			return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Thread not found"));
		db.update("DELETE FROM chat_messages WHERE session_id = ?", id);
		db.update("DELETE FROM chat_sessions WHERE id = ? AND user_id = ?", id, user.id);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Updates mutable fields of a thread: {@code title}, {@code pinned},
	 * {@code archived}, and/or {@code unread}.
	 *
	 * <p>
	 * Only the fields present in the request body are updated. Unknown fields are
	 * ignored. Returns 404 if the thread belongs to another user.
	 *
	 * @param id   the thread ID to update
	 * @param body request body with optional keys: title, pinned, archived, unread
	 * @param req  the current HTTP request (for auth check)
	 * @return 200 OK with {@code {ok: true, thread: chat}}, or 404 if not found
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PatchMapping("/{id}")
	public ResponseEntity<Map<String, Object>> updateThread(@PathVariable String id,
			@RequestBody Map<String, Object> body, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);
		List<Map<String, Object>> sessions = db.queryForList("SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?",
				id, user.id);
		if (sessions.isEmpty())
			return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Thread not found"));
		String now = Instant.now().toString();
		if (body.containsKey("title")) {
			db.update("UPDATE chat_sessions SET title = ? WHERE id = ?", body.get("title"), id);
		}
		if (body.containsKey("pinned")) {
			db.update("UPDATE chat_sessions SET pinned = ? WHERE id = ?",
					Boolean.TRUE.equals(body.get("pinned")) ? 1 : 0, id);
		}
		if (body.containsKey("archived")) {
			db.update("UPDATE chat_sessions SET archived = ? WHERE id = ?",
					Boolean.TRUE.equals(body.get("archived")) ? 1 : 0, id);
		}
		if (body.containsKey("unread")) {
			db.update("UPDATE chat_sessions SET unread = ? WHERE id = ?",
					Boolean.TRUE.equals(body.get("unread")) ? 1 : 0, id);
		}
		db.update("UPDATE chat_sessions SET updated_at = ? WHERE id = ?", now, id);
		Map<String, Object> updated = db.queryForList("SELECT * FROM chat_sessions WHERE id = ?", id).get(0);
		return ResponseEntity.ok(Map.of("ok", true, "thread", chatService.buildChatObject(updated)));
	}

	/**
	 * Activates a history thread, making it the current active session.
	 *
	 * <p>
	 * Archives the currently active session (if any), then sets the specified
	 * thread as active and clears its unread flag.
	 *
	 * @param id  the thread ID to activate
	 * @param req the current HTTP request (for auth check)
	 * @return 200 OK {@code {ok: true}}, or 404 if the thread is not found
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PostMapping("/{id}/activate")
	public ResponseEntity<Map<String, Object>> activateThread(@PathVariable String id, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);
		List<Map<String, Object>> sessions = db.queryForList("SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?",
				id, user.id);
		if (sessions.isEmpty())
			return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Thread not found"));
		chatService.archiveCurrentChat(user.id);
		db.update("UPDATE chat_sessions SET is_active = 1, unread = 0, updated_at = ? WHERE id = ?",
				Instant.now().toString(), id);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Forks an existing thread by copying all of its messages into a new session.
	 *
	 * <p>
	 * Archives the current active session, creates a new active session titled
	 * {@code "Fork of <source title>"}, and copies all messages from the source
	 * thread in chronological order.
	 *
	 * @param id  the source thread ID to fork
	 * @param req the current HTTP request (for auth check)
	 * @return 200 OK {@code {ok: true}}, or 404 if the source thread is not found
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PostMapping("/{id}/fork")
	public ResponseEntity<Map<String, Object>> forkThread(@PathVariable String id, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);
		List<Map<String, Object>> sessions = db.queryForList("SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?",
				id, user.id);
		if (sessions.isEmpty())
			return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Thread not found"));
		Map<String, Object> src = sessions.get(0);
		chatService.archiveCurrentChat(user.id);
		String now = Instant.now().toString();
		String newId = chatService.uid("chat");
		db.update(
				"INSERT INTO chat_sessions (id, user_id, title, pinned, archived, unread, "
						+ "is_active, created_at, updated_at) VALUES (?, ?, ?, 0, 0, 0, 1, ?, ?)",
				newId, user.id, "Fork of " + src.get("title"), now, now);
		List<Map<String, Object>> srcMsgs = db
				.queryForList("SELECT * FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC", id);
		for (Map<String, Object> msg : srcMsgs) {
			db.update(
					"INSERT INTO chat_messages (id, session_id, role, content, mode, model_id, "
							+ "model_name, route_reason, live, artifacts_json, extracted_files_json, "
							+ "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
					chatService.uid("msg"), newId, msg.get("role"), msg.get("content"), msg.get("mode"),
					msg.get("model_id"), msg.get("model_name"), msg.get("route_reason"), msg.get("live"),
					msg.get("artifacts_json"), msg.get("extracted_files_json"), msg.get("created_at"));
		}
		return ResponseEntity.ok(Map.of("ok", true));
	}
}
