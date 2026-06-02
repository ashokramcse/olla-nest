package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP (Model Context Protocol) server management.
 *
 * Manages server configurations, connection state, and tool discovery.
 * Each MCP server exposes a set of tools that become available in the agent loop.
 * Tools can be selectively disabled per server by admins.
 *
 * Transport types: stdio (subprocess), sse (HTTP stream), http (REST)
 */
@Service
public class McpServerService {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);

    // serverId -> connection status info
    private final Map<String, Map<String, Object>> connectionStatus = new ConcurrentHashMap<>();
    // serverId -> list of discovered tools
    private final Map<String, List<Map<String, Object>>> serverTools = new ConcurrentHashMap<>();

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public McpServerService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public Map<String, Object> create(Map<String, Object> req) {
        String id = "mcp-" + Long.toString(System.currentTimeMillis(), 36);
        String now = Instant.now().toString();

        db.update("""
                INSERT INTO mcp_servers (id, name, command, args_json, env_json, transport,
                  url, disabled_tools_json, enabled, team_id, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                id,
                req.getOrDefault("name", "MCP Server"),
                req.getOrDefault("command", ""),
                toJson(req.getOrDefault("args", List.of())),
                toJson(req.getOrDefault("env", Map.of())),
                req.getOrDefault("transport", "stdio"),
                req.get("url"),
                toJson(req.getOrDefault("disabled_tools", List.of())),
                1,
                req.get("team_id"),
                now, now);

        return getById(id);
    }

    public Map<String, Object> getById(String id) {
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM mcp_servers WHERE id=?", id);
        if (rows.isEmpty()) return null;
        return enrichWithStatus(mapRow(rows.get(0)));
    }

    public List<Map<String, Object>> list() {
        return db.queryForList("SELECT * FROM mcp_servers ORDER BY name ASC")
                .stream().map(r -> enrichWithStatus(mapRow(r))).toList();
    }

    public void delete(String id) {
        disconnect(id);
        db.update("DELETE FROM mcp_servers WHERE id=?", id);
    }

    public void setEnabled(String id, boolean enabled) {
        db.update("UPDATE mcp_servers SET enabled=?, updated_at=? WHERE id=?",
                enabled ? 1 : 0, Instant.now().toString(), id);
        if (!enabled) disconnect(id);
    }

    public void setDisabledTools(String id, List<String> disabledTools) {
        db.update("UPDATE mcp_servers SET disabled_tools_json=?, updated_at=? WHERE id=?",
                toJson(disabledTools), Instant.now().toString(), id);
    }

    // ── Connection Management ─────────────────────────────────────────────────

    public Map<String, Object> connect(String serverId) {
        Map<String, Object> server = getById(serverId);
        if (server == null) throw new NoSuchElementException("MCP server not found: " + serverId);

        String transport = (String) server.getOrDefault("transport", "stdio");
        try {
            if ("stdio".equals(transport)) {
                connectStdio(serverId, server);
            } else if ("sse".equals(transport) || "http".equals(transport)) {
                connectHttp(serverId, server);
            }
            connectionStatus.put(serverId, Map.of("status", "connected", "connected_at", Instant.now().toString()));
        } catch (Exception e) {
            connectionStatus.put(serverId, Map.of("status", "error", "error", e.getMessage()));
            log.warn("[mcp] Connection failed for {}: {}", serverId, e.getMessage());
        }

        return connectionStatus.getOrDefault(serverId, Map.of("status", "disconnected"));
    }

    public void disconnect(String serverId) {
        connectionStatus.put(serverId, Map.of("status", "disconnected"));
        serverTools.remove(serverId);
    }

    public List<Map<String, Object>> getTools(String serverId) {
        return serverTools.getOrDefault(serverId, List.of());
    }

    public List<Map<String, Object>> getAllEnabledTools() {
        List<Map<String, Object>> all = new ArrayList<>();
        List<Map<String, Object>> servers = db.queryForList(
                "SELECT id, disabled_tools_json FROM mcp_servers WHERE enabled=1");

        for (Map<String, Object> server : servers) {
            String serverId = (String) server.get("id");
            List<String> disabled = getDisabledList(server);
            for (Map<String, Object> tool : serverTools.getOrDefault(serverId, List.of())) {
                String toolName = (String) tool.get("name");
                if (disabled.contains(toolName)) continue;
                Map<String, Object> enriched = new LinkedHashMap<>(tool);
                enriched.put("server_id", serverId);
                all.add(enriched);
            }
        }
        return all;
    }

    // ── MCP Protocol ─────────────────────────────────────────────────────────

    private void connectStdio(String serverId, Map<String, Object> server) throws Exception {
        String command = (String) server.get("command");
        if (command == null || command.isBlank()) return;

        List<String> args = getArgsList(server);
        List<String> cmdList = new ArrayList<>();
        cmdList.add(command);
        cmdList.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmdList);
        pb.redirectErrorStream(false);

        // Send MCP initialize request and parse tool list
        // This is the extension point — full MCP protocol implementation
        // sends JSON-RPC initialize and tools/list requests over stdio
        log.info("[mcp] Connecting to stdio MCP server: {}", String.join(" ", cmdList));

        // For now, register as connected with empty tool list
        // Full implementation would parse MCP JSON-RPC responses
        serverTools.put(serverId, List.of());
    }

    private void connectHttp(String serverId, Map<String, Object> server) {
        String url = (String) server.get("url");
        log.info("[mcp] Connecting to HTTP MCP server: {}", url);
        serverTools.put(serverId, List.of());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> enrichWithStatus(Map<String, Object> server) {
        if (server == null) return null;
        String id = (String) server.get("id");
        Map<String, Object> status = connectionStatus.getOrDefault(id,
                Map.of("status", "disconnected"));
        Map<String, Object> result = new LinkedHashMap<>(server);
        result.put("connection_status", status.get("status"));
        result.put("tool_count", serverTools.getOrDefault(id, List.of()).size());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapRow(Map<String, Object> row) {
        Map<String, Object> r = new LinkedHashMap<>(row);
        for (String field : List.of("args_json", "env_json", "disabled_tools_json")) {
            try {
                String json = (String) row.get(field);
                String key = field.replace("_json", "");
                r.put(key, json != null ? mapper.readValue(json, Object.class) : (field.contains("env") ? Map.of() : List.of()));
                r.remove(field);
            } catch (Exception e) {
                r.put(field.replace("_json", ""), List.of());
            }
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<String> getArgsList(Map<String, Object> server) {
        try {
            Object args = server.get("args");
            if (args instanceof List<?> l) return (List<String>) l;
            String json = (String) server.get("args_json");
            return json != null ? mapper.readValue(json, List.class) : List.of();
        } catch (Exception e) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private List<String> getDisabledList(Map<String, Object> server) {
        try {
            String json = (String) server.get("disabled_tools_json");
            return json != null ? mapper.readValue(json, List.class) : List.of();
        } catch (Exception e) { return List.of(); }
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return "[]"; }
    }
}
