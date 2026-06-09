package com.ollanest.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manages Model Context Protocol (MCP) server configurations, connection state,
 * and tool discovery for the agent loop.
 *
 * <p>
 * Each MCP server exposes a set of tools via JSON-RPC that become available in
 * the {@link AgentLoopService}. Tools can be selectively disabled per server by
 * admins. Supported transports: {@code stdio} (subprocess), {@code sse} (HTTP
 * stream), {@code http} (REST).
 *
 * <h3>Why this class exists</h3>
 * <p>
 * MCP is an open standard for tool-augmented LLMs. By supporting MCP servers,
 * Olla Nest allows users to extend the agent loop with any third-party tool
 * server (file system, databases, browser automation, etc.) without modifying
 * application code.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Connection state and discovered tools are stored in memory only — they
 * are rebuilt on server restart via {@link #connect}.</li>
 * <li>The stdio transport extension point is wired for JSON-RPC but the full
 * protocol exchange ({@code initialize}, {@code tools/list}) is left for a
 * future implementation.</li>
 * <li>Disabling a server also disconnects it to ensure no stale tool list is
 * served.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced with MCP server CRUD, connection management, and
 * tool registry</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class McpServerService {

	private static final Logger log = LoggerFactory.getLogger(McpServerService.class);

	/** Maps server IDs to their current connection status information. */
	private final Map<String, Map<String, Object>> connectionStatus = new ConcurrentHashMap<>();

	/** Maps server IDs to the list of tools discovered from that server. */
	private final Map<String, List<Map<String, Object>>> serverTools = new ConcurrentHashMap<>();

	/** JDBC template for MCP server configuration persistence. */
	private final JdbcTemplate db;

	/** Shared Jackson mapper for args/env/tools JSON serialisation. */
	private final ObjectMapper mapper;

	/**
	 * Constructor-injects persistence and serialisation dependencies.
	 *
	 * @param db     JDBC template for the {@code mcp_servers} table
	 * @param mapper shared Jackson mapper
	 * @since v2026.2.1
	 */
	public McpServerService(JdbcTemplate db, ObjectMapper mapper) {
		this.db = db;
		this.mapper = mapper;
	}

	// ── CRUD ──────────────────────────────────────────────────────────────────

	/**
	 * Creates a new MCP server configuration.
	 *
	 * @param req server fields: {@code name}, {@code command}, {@code args},
	 *            {@code env}, {@code transport}, {@code url}, {@code team_id}
	 * @return the created server record with enriched status fields
	 * @since v2026.2.1
	 */
	public Map<String, Object> create(Map<String, Object> req) {
		String id = "mcp-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ UUID.randomUUID().toString().substring(0, 6);
		String now = Instant.now().toString();

		db.update("""
				INSERT INTO mcp_servers (id, name, command, args_json, env_json, transport,
				  url, disabled_tools_json, enabled, team_id, created_at, updated_at)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", id, req.getOrDefault("name", "MCP Server"),
				req.getOrDefault("command", ""), toJson(req.getOrDefault("args", List.of())),
				toJson(req.getOrDefault("env", Map.of())), req.getOrDefault("transport", "stdio"), req.get("url"),
				toJson(req.getOrDefault("disabled_tools", List.of())), 1, req.get("team_id"), now, now);

		return getById(id);
	}

	/**
	 * Returns the MCP server configuration by ID, enriched with connection status.
	 *
	 * @param id the server ID
	 * @return the server record, or {@code null} if not found
	 * @since v2026.2.1
	 */
	public Map<String, Object> getById(String id) {
		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM mcp_servers WHERE id=?", id);
		if (rows.isEmpty())
			return null;
		return enrichWithStatus(mapRow(rows.get(0)));
	}

	/**
	 * Returns all MCP server configurations ordered by name, each enriched with
	 * connection status.
	 *
	 * @return list of server record maps; never null
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> list() {
		return db.queryForList("SELECT * FROM mcp_servers ORDER BY name ASC").stream()
				.map(r -> enrichWithStatus(mapRow(r))).toList();
	}

	/**
	 * Disconnects and deletes the MCP server configuration with the given ID.
	 *
	 * @param id the server ID to delete
	 * @since v2026.2.1
	 */
	public void delete(String id) {
		disconnect(id);
		db.update("DELETE FROM mcp_servers WHERE id=?", id);
	}

	/**
	 * Enables or disables an MCP server. Disabling also disconnects the server.
	 *
	 * @param id      the server ID
	 * @param enabled {@code true} to enable, {@code false} to disable
	 * @since v2026.2.1
	 */
	public void setEnabled(String id, boolean enabled) {
		db.update("UPDATE mcp_servers SET enabled=?, updated_at=? WHERE id=?", enabled ? 1 : 0,
				Instant.now().toString(), id);
		if (!enabled)
			disconnect(id);
	}

	/**
	 * Sets the list of tool names that are disabled for the given MCP server.
	 *
	 * @param id            the server ID
	 * @param disabledTools list of tool names to disable; empty list re-enables all
	 * @since v2026.2.1
	 */
	public void setDisabledTools(String id, List<String> disabledTools) {
		db.update("UPDATE mcp_servers SET disabled_tools_json=?, updated_at=? WHERE id=?", toJson(disabledTools),
				Instant.now().toString(), id);
	}

	// ── Connection Management ─────────────────────────────────────────────────

	/**
	 * Initiates a connection to the MCP server and returns the resulting connection
	 * status.
	 *
	 * @param serverId the server ID to connect to
	 * @return connection status map with at least {@code status} key
	 * @throws java.util.NoSuchElementException if the server is not found
	 * @since v2026.2.1
	 */
	public Map<String, Object> connect(String serverId) {
		Map<String, Object> server = getById(serverId);
		if (server == null)
			throw new NoSuchElementException("MCP server not found: " + serverId);

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

	/**
	 * Disconnects the MCP server and clears its in-memory tool list.
	 *
	 * @param serverId the server ID to disconnect
	 * @since v2026.2.1
	 */
	public void disconnect(String serverId) {
		connectionStatus.put(serverId, Map.of("status", "disconnected"));
		serverTools.remove(serverId);
	}

	/**
	 * Returns the in-memory list of tools discovered from the given MCP server.
	 *
	 * @param serverId the server ID
	 * @return list of tool definition maps; empty list if not connected or no tools
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> getTools(String serverId) {
		return serverTools.getOrDefault(serverId, List.of());
	}

	/**
	 * Returns a flat list of all tools from all enabled MCP servers, respecting
	 * per-server disabled-tool lists. Each tool map includes a {@code server_id}
	 * field.
	 *
	 * @return list of all enabled tool definition maps; never null
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> getAllEnabledTools() {
		List<Map<String, Object>> all = new ArrayList<>();
		List<Map<String, Object>> servers = db
				.queryForList("SELECT id, disabled_tools_json FROM mcp_servers WHERE enabled=1");

		for (Map<String, Object> server : servers) {
			String serverId = (String) server.get("id");
			List<String> disabled = getDisabledList(server);
			for (Map<String, Object> tool : serverTools.getOrDefault(serverId, List.of())) {
				String toolName = (String) tool.get("name");
				if (disabled.contains(toolName))
					continue;
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
		if (command == null || command.isBlank())
			return;

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
		if (server == null)
			return null;
		String id = (String) server.get("id");
		Map<String, Object> status = connectionStatus.getOrDefault(id, Map.of("status", "disconnected"));
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
				r.put(key, json != null ? mapper.readValue(json, Object.class)
						: (field.contains("env") ? Map.of() : List.of()));
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
			if (args instanceof List<?> l)
				return (List<String>) l;
			String json = (String) server.get("args_json");
			return json != null ? mapper.readValue(json, List.class) : List.of();
		} catch (Exception e) {
			return List.of();
		}
	}

	@SuppressWarnings("unchecked")
	private List<String> getDisabledList(Map<String, Object> server) {
		try {
			String json = (String) server.get("disabled_tools_json");
			return json != null ? mapper.readValue(json, List.class) : List.of();
		} catch (Exception e) {
			return List.of();
		}
	}

	private String toJson(Object obj) {
		try {
			return mapper.writeValueAsString(obj);
		} catch (Exception e) {
			return "[]";
		}
	}
}
