package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Session history enhancements — topic analysis, session forking, history truncation.
 *
 * These operations work on the chat_sessions and chat_messages tables and are
 * additive to the existing ChatService functionality.
 */
@Service
public class SessionEnhancementService {

    private static final Logger log = LoggerFactory.getLogger(SessionEnhancementService.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final DatabaseService databaseService;

    public SessionEnhancementService(JdbcTemplate db, ObjectMapper mapper, DatabaseService databaseService) {
        this.db = db;
        this.mapper = mapper;
        this.databaseService = databaseService;
    }

    // ── Topic analysis ────────────────────────────────────────────────────────

    /**
     * LLM-based topic detection for a session. Stores detected topics in
     * session metadata. Runs async.
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
     * Fork a conversation at a given message index. Creates a new session with
     * all messages up to (and including) that index.
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
        String newId = "sess-fork-" + Long.toString(System.currentTimeMillis(), 36);
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

    /** Truncate messages from a given message index onward (delete newer messages). */
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
