package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Chat helpers: buildContextMessages, buildSystemPrompt, getActiveChat, etc.
 * Mirrors src/services/chat.js logic.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final JdbcTemplate db;
    private final WorkspaceService workspaceService;
    private final DatabaseService databaseService;
    private final ObjectMapper mapper;

    public ChatService(JdbcTemplate db, WorkspaceService workspaceService, DatabaseService databaseService, ObjectMapper mapper) {
        this.db = db;
        this.workspaceService = workspaceService;
        this.databaseService = databaseService;
        this.mapper = mapper;
    }

    public int estimateTokens(String text) {
        return (int) Math.ceil((text != null ? text.length() : 0) / 4.0);
    }

    public long getModelContextWindow(String modelName) {
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT context_size FROM models WHERE model_ref = ? OR name = ? LIMIT 1", modelName, modelName);
        if (!rows.isEmpty()) {
            Object cs = rows.get(0).get("context_size");
            if (cs != null && ((Number) cs).longValue() > 0) return ((Number) cs).longValue();
        }
        List<Map<String, Object>> apiRows = db.queryForList(
            "SELECT context_window FROM api_models WHERE model_id = ? LIMIT 1", modelName);
        if (!apiRows.isEmpty()) {
            Object cw = apiRows.get(0).get("context_window");
            if (cw != null && ((Number) cw).longValue() > 0) return ((Number) cw).longValue();
        }
        return 8192;
    }

    public List<Map<String, Object>> buildContextMessages(String sessionId, String systemPrompt, String userMessage, String modelName, List<String> images) {
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
            if (budget - tokens < 0) break;
            budget -= tokens;
            kept.addFirst(msg);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> sysMsg = new LinkedHashMap<>();
        sysMsg.put("role", "system"); sysMsg.put("content", systemPrompt);
        result.add(sysMsg);

        for (Map<String, Object> h : kept) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", h.get("role")); m.put("content", h.get("content"));
            result.add(m);
        }

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user"); userMsg.put("content", userMessage);
        if (images != null && !images.isEmpty()) userMsg.put("images", images);
        result.add(userMsg);
        return result;
    }

    public String buildSystemPrompt(String mode, RouterService.RouteResult route, Map<String, Object> workspace, String projectKnowledge) {
        List<String> base = new ArrayList<>();
        base.add("You are Olla Nest, a company AI workspace assistant.");
        base.add("Answer the user's request directly and completely.");
        base.add("Do not answer with only the selected model name.");
        base.add("Do not include hidden thinking, <think> blocks, or internal reasoning traces.");
        base.add("Selected model route: " + (route.selected != null ? route.selected.name : "auto") + ".");
        base.add("Routing reason: " + route.reason);

        if (workspace != null && workspace.get("workspaceRoot") != null) {
            String wsRoot = (String) workspace.get("workspaceRoot");
            base.add("Active project folder: " + wsRoot);
            base.add("Write permission mode: " + workspace.getOrDefault("permissionMode", "default"));
            base.add("When building, fixing, or generating files, treat the active project folder as the working directory.");
            base.add("IMPORTANT: When generating code files, always specify the filename in the code fence header using the format: ```language:filename.ext — for example: ```html:index.html or ```jsx:src/App.jsx or ```css:styles.css. Use relative paths from the project root. This allows files to be saved directly to the workspace.");
            List<String> files = workspaceService.listWorkspaceFiles(wsRoot);
            if (!files.isEmpty()) {
                StringBuilder sb = new StringBuilder("Current project files:\n");
                for (String f : files) sb.append("  ").append(f).append("\n");
                base.add(sb.toString().trim());
            } else {
                base.add("Current project files: (empty — this is a new project)");
            }
        }

        if (projectKnowledge != null && !projectKnowledge.trim().isEmpty()) {
            base.add("\nProject Knowledge (injected by admin):\n" + projectKnowledge.trim());
        }

        Map<String, String> modeInstructions = new LinkedHashMap<>();
        modeInstructions.put("ask", "Give a clear, useful answer with enough detail to be acted on.");
        modeInstructions.put("build", "Build the requested output. Return the implementation as one complete, runnable file in a fenced code block. For UI pages, prefer a complete React JSX component when React is implied, otherwise a complete HTML file with embedded CSS and JavaScript. Do not return only a plan.");
        modeInstructions.put("review", "Review the request for issues, risks, improvements, and missing pieces. Lead with actionable findings.");
        modeInstructions.put("fix", "Diagnose the problem and provide the fix with exact steps or code changes. Be specific.");
        modeInstructions.put("learn", "Teach the concept clearly with examples and simple explanation.");
        modeInstructions.put("debug", "Identify the root cause of the error or unexpected behavior. Show the exact line or logic that is wrong, explain why it fails, then provide a specific corrected version. Include a checklist of other things to verify.");
        modeInstructions.put("test", "Write comprehensive tests for the provided code or feature. Include unit tests, edge cases, and error cases. Use the most appropriate test framework for the language. Add brief comments explaining what each test covers.");
        modeInstructions.put("docs", "Generate complete documentation for the provided code, feature, or project. Include: purpose, parameters or props, return values, usage examples, and any important notes. For a project, write a professional README with setup, usage, and API reference sections.");
        modeInstructions.put("plan", "Break this down into a clear implementation plan. Include: recommended tech stack with reasoning, folder/file structure, step-by-step build order, key decisions the developer needs to make, and estimated complexity per phase. Be opinionated and specific.");

        String instruction = modeInstructions.getOrDefault(mode, modeInstructions.get("ask"));
        return String.join("\n", base) + "\nMode: " + mode + "\nInstruction: " + instruction;
    }

    public Map<String, Object> getActiveChat(String userId) {
        List<Map<String, Object>> sessions = db.queryForList(
            "SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 1 ORDER BY updated_at DESC LIMIT 1", userId);
        if (sessions.isEmpty()) {
            String now = Instant.now().toString();
            String sessionId = uid("chat");
            db.update("INSERT INTO chat_sessions (id, user_id, title, pinned, archived, unread, is_active, created_at, updated_at) VALUES (?, ?, ?, 0, 0, 0, 1, ?, ?)",
                sessionId, userId, "New Chat", now, now);
            db.update("INSERT INTO chat_messages (id, session_id, role, content, model_name, live, created_at) VALUES (?, ?, ?, ?, ?, 1, ?)",
                uid("msg"), sessionId, "assistant",
                "Ready. Ask once, and Auto Router will choose the best approved model for your task.",
                "Olla Nest", now);
            sessions = db.queryForList("SELECT * FROM chat_sessions WHERE id = ?", sessionId);
        }
        return buildChatObject(sessions.get(0));
    }

    public Map<String, Object> getActiveChatSession(String userId) {
        List<Map<String, Object>> sessions = db.queryForList(
            "SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 1 ORDER BY updated_at DESC LIMIT 1", userId);
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    public void archiveCurrentChat(String userId) {
        Map<String, Object> session = getActiveChatSession(userId);
        if (session == null) return;
        String sessionId = (String) session.get("id");
        List<Map<String, Object>> userMsgs = db.queryForList(
            "SELECT id FROM chat_messages WHERE session_id = ? AND role = 'user' LIMIT 1", sessionId);
        if (userMsgs.isEmpty()) return;
        db.update("UPDATE chat_sessions SET is_active = 0, updated_at = ? WHERE id = ?",
            Instant.now().toString(), sessionId);
    }

    public Map<String, Object> buildChatObject(Map<String, Object> session) {
        String sessionId = (String) session.get("id");
        List<Map<String, Object>> msgs = db.queryForList(
            "SELECT * FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC", sessionId);

        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("id", sessionId);
        chat.put("userId", session.get("user_id"));
        chat.put("title", session.get("title"));
        chat.put("pinned", boolVal(session, "pinned"));
        chat.put("archived", boolVal(session, "archived"));
        chat.put("unread", boolVal(session, "unread"));
        chat.put("isActive", boolVal(session, "is_active"));
        List<Map<String, Object>> parsedMsgs = new ArrayList<>();
        for (Map<String, Object> m : msgs) parsedMsgs.add(parseMessage(m));
        chat.put("messages", parsedMsgs);
        chat.put("createdAt", session.get("created_at"));
        chat.put("updatedAt", session.get("updated_at"));
        return chat;
    }

    public Map<String, Object> parseMessage(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("id"));
        m.put("role", row.get("role"));
        m.put("content", row.get("content"));
        Object mode = row.get("mode");
        if (mode != null) m.put("mode", mode);
        Object modelId = row.get("model_id");
        if (modelId != null) m.put("modelId", modelId);
        Object modelName = row.get("model_name");
        if (modelName != null) m.put("modelName", modelName);
        Object routeReason = row.get("route_reason");
        if (routeReason != null) m.put("routeReason", routeReason);
        Object liveVal = row.get("live");
        m.put("live", liveVal == null || (liveVal instanceof Number ? ((Number) liveVal).intValue() != 0 : Boolean.parseBoolean(liveVal.toString())));
        m.put("artifacts", safeJsonList(str(row, "artifacts_json")));
        m.put("extractedFiles", safeJsonList(str(row, "extracted_files_json")));
        m.put("createdAt", row.get("created_at"));
        return m;
    }

    public void appendAudit(String actor, String action, String detail, Map<String, Object> extra) {
        try {
            String extraJson = mapper.writeValueAsString(extra != null ? extra : Map.of());
            db.update("INSERT INTO audit_events (id, actor, action, detail, extra_json, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                uid("audit"), actor, action, detail != null ? detail : "", extraJson, Instant.now().toString());
        } catch (Exception e) {
            log.warn("[audit] Failed to write audit event: {}", e.getMessage());
        }
    }

    public void appendTrace(String userId, String sessionId, String message, String mode, String selectedModelId,
                            List<String> tags, List<Map<String, Object>> candidates, boolean live) {
        try {
            db.update("INSERT INTO router_traces (id, user_id, session_id, message, mode, selected_model_id, tags_json, candidates_json, live, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                uid("trace"), userId, sessionId, message != null ? message : "", mode != null ? mode : "",
                selectedModelId,
                mapper.writeValueAsString(tags != null ? tags : List.of()),
                mapper.writeValueAsString(candidates != null ? candidates : List.of()),
                live ? 1 : 0, Instant.now().toString());
        } catch (Exception e) {
            log.warn("[trace] Failed to write trace: {}", e.getMessage());
        }
    }

    // Rate limit tracking (in-memory, per-user per minute)
    private final Map<String, long[]> rateLimitMap = new ConcurrentHashMap<String, long[]>();
    private static class ConcurrentHashMap<K, V> extends java.util.concurrent.ConcurrentHashMap<K, V> {}

    public boolean checkChatRateLimit(String userId, long limitPerMinute) {
        if (limitPerMinute <= 0) return true;
        long now = System.currentTimeMillis();
        long windowStart = now - 60000;
        rateLimitMap.compute(userId, (k, v) -> {
            if (v == null) v = new long[]{0, now};
            if (v[1] < windowStart) { v[0] = 0; v[1] = now; }
            return v;
        });
        long[] counter = rateLimitMap.get(userId);
        if (counter[0] >= limitPerMinute) return false;
        counter[0]++;
        return true;
    }

    public String uid(String prefix) {
        return prefix + "-" + Long.toString(System.currentTimeMillis(), 36) + "-"
            + Long.toString((long)(Math.random() * 36L * 36L * 36L * 36L * 36L * 36L), 36);
    }

    @SuppressWarnings("unchecked")
    private List<Object> safeJsonList(String json) {
        try {
            if (json == null || json.isBlank()) return new ArrayList<>();
            return mapper.readValue(json, new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString() : null;
    }

    private boolean boolVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return "1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString());
    }
}
