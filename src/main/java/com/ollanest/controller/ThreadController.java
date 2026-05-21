package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/threads")
public class ThreadController extends BaseController {

    private final JdbcTemplate db;
    private final ChatService chatService;

    public ThreadController(JdbcTemplate db, ChatService chatService) {
        this.db = db;
        this.chatService = chatService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listThreads(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);
        List<Map<String, Object>> activeSessions = db.queryForList(
            "SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 1 ORDER BY updated_at DESC", user.id);
        List<Map<String, Object>> historySessions = db.queryForList(
            "SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 0 ORDER BY pinned DESC, updated_at DESC", user.id);
        Map<String, Object> active = activeSessions.isEmpty() ? null : chatService.buildChatObject(activeSessions.get(0));
        List<Object> history = new ArrayList<>();
        for (Map<String, Object> s : historySessions) history.add(chatService.buildChatObject(s));
        return ResponseEntity.ok(Map.of("active", active != null ? active : Map.of(), "history", history));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteThread(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);
        List<Map<String, Object>> sessions = db.queryForList(
            "SELECT id FROM chat_sessions WHERE id = ? AND user_id = ?", id, user.id);
        if (sessions.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Thread not found"));
        db.update("DELETE FROM chat_messages WHERE session_id = ?", id);
        db.update("DELETE FROM chat_sessions WHERE id = ? AND user_id = ?", id, user.id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateThread(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);
        List<Map<String, Object>> sessions = db.queryForList(
            "SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?", id, user.id);
        if (sessions.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Thread not found"));
        String now = Instant.now().toString();
        if (body.containsKey("title")) db.update("UPDATE chat_sessions SET title = ? WHERE id = ?", body.get("title"), id);
        if (body.containsKey("pinned")) db.update("UPDATE chat_sessions SET pinned = ? WHERE id = ?", Boolean.TRUE.equals(body.get("pinned")) ? 1 : 0, id);
        if (body.containsKey("archived")) db.update("UPDATE chat_sessions SET archived = ? WHERE id = ?", Boolean.TRUE.equals(body.get("archived")) ? 1 : 0, id);
        if (body.containsKey("unread")) db.update("UPDATE chat_sessions SET unread = ? WHERE id = ?", Boolean.TRUE.equals(body.get("unread")) ? 1 : 0, id);
        db.update("UPDATE chat_sessions SET updated_at = ? WHERE id = ?", now, id);
        Map<String, Object> updated = db.queryForList("SELECT * FROM chat_sessions WHERE id = ?", id).get(0);
        return ResponseEntity.ok(Map.of("ok", true, "thread", chatService.buildChatObject(updated)));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Map<String, Object>> activateThread(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);
        List<Map<String, Object>> sessions = db.queryForList(
            "SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?", id, user.id);
        if (sessions.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Thread not found"));
        chatService.archiveCurrentChat(user.id);
        db.update("UPDATE chat_sessions SET is_active = 1, unread = 0, updated_at = ? WHERE id = ?",
            Instant.now().toString(), id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/fork")
    public ResponseEntity<Map<String, Object>> forkThread(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
        if (authError != null) return authError;
        User user = getUser(req);
        List<Map<String, Object>> sessions = db.queryForList(
            "SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?", id, user.id);
        if (sessions.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Thread not found"));
        Map<String, Object> src = sessions.get(0);
        chatService.archiveCurrentChat(user.id);
        String now = Instant.now().toString();
        String newId = chatService.uid("chat");
        db.update("INSERT INTO chat_sessions (id, user_id, title, pinned, archived, unread, is_active, created_at, updated_at) VALUES (?, ?, ?, 0, 0, 0, 1, ?, ?)",
            newId, user.id, "Fork of " + src.get("title"), now, now);
        List<Map<String, Object>> srcMsgs = db.queryForList(
            "SELECT * FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC", id);
        for (Map<String, Object> msg : srcMsgs) {
            db.update("INSERT INTO chat_messages (id, session_id, role, content, mode, model_id, model_name, route_reason, live, artifacts_json, extracted_files_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                chatService.uid("msg"), newId, msg.get("role"), msg.get("content"), msg.get("mode"),
                msg.get("model_id"), msg.get("model_name"), msg.get("route_reason"), msg.get("live"),
                msg.get("artifacts_json"), msg.get("extracted_files_json"), msg.get("created_at"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
