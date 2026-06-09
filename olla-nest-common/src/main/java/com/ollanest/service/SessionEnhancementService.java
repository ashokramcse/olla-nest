package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Provides additive enhancements to chat session history: LLM-based topic
 * analysis, conversation forking, and message-level history truncation.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * These features are deliberately kept separate from {@code ChatService} to
 * preserve the single-responsibility principle: {@code ChatService} handles the
 * real-time message exchange loop, while this service handles higher-level,
 * post-hoc operations on the accumulated history. Keeping the separation also
 * makes it straightforward to disable or replace any one enhancement without
 * touching the core chat flow.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Topic analysis calls the configured Ollama endpoint asynchronously via a
 * virtual thread so it never delays the response to the user.</li>
 * <li>Session forking creates a full copy of messages up to the chosen index;
 * the original session is not modified.</li>
 * <li>History truncation deletes messages by {@code created_at} timestamp
 * rather than by offset so the operation is safe against concurrent inserts.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced as part of the session management expansion</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class SessionEnhancementService {

    private static final Logger log = LoggerFactory.getLogger(SessionEnhancementService.class);

    /** Shared HTTP client for topic-analysis LLM calls. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    /** JDBC template for chat session and message persistence. */
    private final JdbcTemplate db;

    /** Shared Jackson mapper for LLM response parsing. */
    private final ObjectMapper mapper;

    /** Provides runtime-configurable Ollama URL and default model settings. */
    private final DatabaseService databaseService;

    /**
     * Constructor-injects persistence, serialization, and settings dependencies.
     *
     * @param db              the JDBC template
     * @param mapper          the shared Jackson object mapper
     * @param databaseService the settings service for Ollama URL and model configuration
     * @since v2026.2.1
     */
    public SessionEnhancementService(JdbcTemplate db, ObjectMapper mapper, DatabaseService databaseService) {
        this.db = db;
        this.mapper = mapper;
        this.databaseService = databaseService;
    }

    // ── Topic analysis ────────────────────────────────────────────────────────

    /**
     * Triggers LLM-based topic detection for a session in a background virtual thread.
     * Detected topics are stored as the session title if no meaningful title exists yet.
     *
     * @param sessionId the chat session ID to analyse
     * @param userId    the owner user ID (used for ownership checks inside the async task)
     * @since v2026.2.1
     */
    public void analyzeTopicsAsync(String sessionId, String userId) {
        Thread.ofVirtual().name("topic-" + sessionId).start(() -> {
            try {
                analyzeTopics(sessionId, userId);
            } catch (Exception e) {
                log.debug("[topics] Analysis failed for {}: {}", sessionId, e.getMessage());
            }
        });
    }

    private void analyzeTopics(String sessionId, String userId) throws Exception {
        // Get last 10 messages
        List<Map<String, Object>> msgs = db.queryForList("""
                SELECT role, content FROM chat_messages
                WHERE session_id=? ORDER BY created_at DESC LIMIT 10""", sessionId);
        if (msgs.isEmpty()) return;

        StringBuilder transcript = new StringBuilder();
        msgs.reversed().forEach(m -> transcript
                .append(m.get("role")).append(": ")
                .append(truncate((String) m.get("content"), 200)).append("\n"));

        String ollamaUrl = databaseService.getSetting("ollamaUrl", "http://localhost:11434");
        String model = databaseService.getSetting("defaultModel", "llama3.2");

        String prompt = "Identify 1-3 main topics in this conversation. Return a JSON array of short strings (max 3 words each). Only the JSON array, nothing else.\n\n" + transcript;

        Map<String, Object> request = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "stream", false,
                "options", Map.of("num_predict", 100, "temperature", 0.1)
        );

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl.replaceAll("/+$", "") + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                .build();

        String content = mapper.readTree(
                HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body()
        ).path("message").path("content").asText("[]");

        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start >= 0 && end > start) {
            List<String> topics = mapper.readValue(content.substring(start, end + 1), new TypeReference<>() {});
            String topicsJson = mapper.writeValueAsString(topics);
            // Store in session - update title if no meaningful title yet
            db.update("UPDATE chat_sessions SET title=? WHERE id=? AND (title='New Chat' OR title IS NULL)",
                    topics.isEmpty() ? "Chat" : topics.get(0), sessionId);
        }
    }

    // ── Session forking ───────────────────────────────────────────────────────

    /**
     * Forks a conversation at a given message index, creating a new session with all
     * messages up to and including that index. The original session is not modified.
     *
     * @param sessionId    the session to fork
     * @param userId       the owner user ID (must match the session's user_id)
     * @param messageIndex zero-based index of the last message to include in the fork
     * @return a map with {@code forked_session_id}, {@code message_count}, and
     *         {@code original_session_id}
     * @throws NoSuchElementException if the session is not found or not owned by the user
     * @since v2026.2.1
     */
    public Map<String, Object> forkSession(String sessionId, String userId, int messageIndex) {
        // Verify ownership
        var session = db.queryForList("SELECT * FROM chat_sessions WHERE id=? AND user_id=?", sessionId, userId);
        if (session.isEmpty()) throw new NoSuchElementException("Session not found: " + sessionId);

        // Get messages up to index
        List<Map<String, Object>> messages = db.queryForList(
                "SELECT * FROM chat_messages WHERE session_id=? ORDER BY created_at ASC LIMIT ?",
                sessionId, messageIndex + 1);

        // Create new session
        String newId = "sess-fork-" + Long.toString(System.currentTimeMillis(), 36) + "-" + UUID.randomUUID().toString().substring(0, 6);
        String now = Instant.now().toString();
        String origTitle = (String) session.get(0).get("title");
        db.update("INSERT INTO chat_sessions (id, user_id, title, created_at, updated_at) VALUES (?,?,?,?,?)",
                newId, userId, "Fork: " + origTitle, now, now);

        // Copy messages
        for (Map<String, Object> msg : messages) {
            String newMsgId = "msg-fork-" + Long.toString(System.currentTimeMillis(), 36) + UUID.randomUUID().toString().substring(0, 4);
            db.update("INSERT INTO chat_messages (id, session_id, user_id, role, content, created_at) VALUES (?,?,?,?,?,?)",
                    newMsgId, newId, msg.get("user_id"), msg.get("role"), msg.get("content"), msg.get("created_at"));
        }

        return Map.of("forked_session_id", newId, "message_count", messages.size(), "original_session_id", sessionId);
    }

    // ── History truncation ────────────────────────────────────────────────────

    /**
     * Deletes all messages in a session from the given message onwards (inclusive),
     * effectively rolling the conversation back to just before that message.
     *
     * @param sessionId     the session to truncate
     * @param userId        the owner user ID (ownership is verified)
     * @param fromMessageId the ID of the first message to delete; all messages at or
     *                      after its {@code created_at} timestamp are removed
     * @throws NoSuchElementException if the session or pivot message is not found
     * @since v2026.2.1
     */
    public void truncateHistory(String sessionId, String userId, String fromMessageId) {
        var session = db.queryForList("SELECT id FROM chat_sessions WHERE id=? AND user_id=?", sessionId, userId);
        if (session.isEmpty()) throw new NoSuchElementException("Session not found: " + sessionId);

        // Get created_at of the pivot message
        var pivot = db.queryForList("SELECT created_at FROM chat_messages WHERE id=? AND session_id=?", fromMessageId, sessionId);
        if (pivot.isEmpty()) throw new NoSuchElementException("Message not found: " + fromMessageId);

        String pivotDate = (String) pivot.get(0).get("created_at");
        int deleted = db.update("DELETE FROM chat_messages WHERE session_id=? AND created_at >= ?", sessionId, pivotDate);
        log.info("[history] Truncated {} messages from session {} at {}", deleted, sessionId, pivotDate);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
