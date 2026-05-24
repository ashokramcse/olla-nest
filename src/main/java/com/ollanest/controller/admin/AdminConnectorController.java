package com.ollanest.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.connector.BaseConnector;
import com.ollanest.connector.ConnectorRegistry;
import com.ollanest.connector.ConnectorSyncScheduler;
import com.ollanest.controller.BaseController;
import com.ollanest.model.User;
import com.ollanest.service.CryptoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Admin REST API for managing external connector integrations.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest supports pluggable data connectors (e.g. Confluence, Jira,
 * SharePoint) that periodically sync external documents into the workspace
 * knowledge base. This controller provides the admin UI with full CRUD over
 * connector configurations, on-demand sync triggering, credential testing, and
 * per-connector sync history.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Credentials are encrypted at rest via AES-256-GCM ({@link CryptoService})
 * and are <em>never</em> included in any API response — only the derived
 * metadata is returned.</li>
 * <li>Syncs run in a Java virtual thread so they do not block the HTTP thread.
 * The endpoint returns immediately with a {@code 200 OK /
 *       "Sync started in background"} acknowledgement.</li>
 * <li>The {@link ConnectorRegistry} is the single source of truth for which
 * connector types are available at runtime; the {@code /types} endpoint simply
 * delegates to it.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration</li>
 * </ul>
 *
 * <pre>
 *   GET    /api/admin/connectors              — list all connector configs
 *   GET    /api/admin/connectors/types        — list registered connector types
 *   POST   /api/admin/connectors              — create a new connector
 *   PATCH  /api/admin/connectors/{id}         — update config / enable flag
 *   DELETE /api/admin/connectors/{id}         — delete a connector
 *   POST   /api/admin/connectors/{id}/sync    — trigger a manual sync (async)
 *   POST   /api/admin/connectors/{id}/test    — test connector credentials
 *   GET    /api/admin/connectors/{id}/logs    — fetch recent sync log entries
 * </pre>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.0
 */
@RestController
@RequestMapping("/api/admin/connectors")
public class AdminConnectorController extends BaseController {

	/** JDBC template used for all connector config and sync-log queries. */
	private final JdbcTemplate db;

	/** Registry of all runtime-available connector implementations. */
	private final ConnectorRegistry registry;

	/**
	 * Scheduler that owns scheduled sync jobs (unused directly here but wired for
	 * DI).
	 */
	private final ConnectorSyncScheduler scheduler;

	/** AES-256-GCM service used to encrypt and decrypt connector credentials. */
	private final CryptoService cryptoService;

	/**
	 * Shared JSON mapper for serialising/deserialising config and credential maps.
	 */
	private final ObjectMapper mapper;

	/**
	 * Constructor-injects all required dependencies.
	 *
	 * @param db            the JDBC template
	 * @param registry      the connector type registry
	 * @param scheduler     the background sync scheduler
	 * @param cryptoService the credential encryption service
	 * @param mapper        the shared JSON object mapper
	 * @since v2026.1.0
	 */
	public AdminConnectorController(JdbcTemplate db, ConnectorRegistry registry, ConnectorSyncScheduler scheduler,
			CryptoService cryptoService, ObjectMapper mapper) {
		this.db = db;
		this.registry = registry;
		this.scheduler = scheduler;
		this.cryptoService = cryptoService;
		this.mapper = mapper;
	}

	/**
	 * Lists all connector configurations. Credentials are never included.
	 *
	 * <p>
	 * HTTP: {@code GET /api/admin/connectors} — admin-only.
	 *
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {ok: true, connectors: [connector]}}; each row
	 *         contains id, name, type, enabled, auth_type, config_json,
	 *         last_synced_at, sync_status, sync_error, docs_total, created_at; or
	 *         401/403 if not an admin
	 * @since v2026.1.0
	 */
	@GetMapping
	public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		List<Map<String, Object>> rows = db.queryForList("SELECT id, name, type, enabled, auth_type, config_json, "
				+ "last_synced_at, sync_status, sync_error, docs_total, created_at FROM connector_configs ORDER BY name");
		// Never return encrypted credentials
		return ResponseEntity.ok(Map.of("ok", true, "connectors", rows));
	}

	/**
	 * Returns the list of connector types registered in the
	 * {@link ConnectorRegistry}.
	 *
	 * <p>
	 * HTTP: {@code GET /api/admin/connectors/types} — admin-only.
	 *
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {ok: true, types: [typeString]}}; or 401/403
	 * @since v2026.1.0
	 */
	@GetMapping("/types")
	public ResponseEntity<Map<String, Object>> types(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		return ResponseEntity.ok(Map.of("ok", true, "types", registry.types()));
	}

	/**
	 * Creates a new connector configuration.
	 *
	 * <p>
	 * HTTP: {@code POST /api/admin/connectors} — admin-only.
	 *
	 * <p>
	 * The connector is created in {@code enabled} state with sync status
	 * {@code "idle"}. If {@code credentials} is provided (as an object or JSON
	 * string) it is encrypted before storage. The generated ID has the form
	 * {@code conn-<type>-<base36timestamp>}.
	 *
	 * @param body request body with fields:
	 *             <ul>
	 *             <li>{@code name} (required) — human-readable label</li>
	 *             <li>{@code type} (required) — must match a registered type</li>
	 *             <li>{@code credentials} (optional) — object or JSON string with
	 *             auth data</li>
	 *             <li>{@code config} (optional) — connector-specific config
	 *             object</li>
	 *             <li>{@code authType} (optional, default {@code "api_key"})</li>
	 *             </ul>
	 * @param req  the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {ok: true, id: newId}}; or 400 if credentials
	 *         cannot be serialised; or 401/403 if not an admin
	 * @since v2026.1.0
	 */
	@PostMapping
	public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		User admin = getUser(req);

		String id = "conn-" + body.get("type") + "-" + Long.toString(System.currentTimeMillis(), 36);
		String credEnc = "";
		if (body.containsKey("credentials") && body.get("credentials") != null) {
			try {
				String credJson = body.get("credentials") instanceof String ? (String) body.get("credentials")
						: mapper.writeValueAsString(body.get("credentials"));
				credEnc = cryptoService.encryptKey(credJson);
			} catch (Exception e) {
				return ResponseEntity.badRequest().body(Map.of("error", "Invalid credentials: " + e.getMessage()));
			}
		}

		String cfgJson = "{}";
		if (body.containsKey("config")) {
			try {
				cfgJson = body.get("config") instanceof String ? (String) body.get("config")
						: mapper.writeValueAsString(body.get("config"));
			} catch (Exception ignore) {
			}
		}

		String now = Instant.now().toString();
		db.update(
				"INSERT INTO connector_configs (id, name, type, enabled, auth_type, credentials_enc, config_json, sync_status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
				id, body.get("name"), body.get("type"), 1, body.getOrDefault("authType", "api_key"), credEnc, cfgJson,
				"idle", now, now);

		return ResponseEntity.ok(Map.of("ok", true, "id", id));
	}

	/**
	 * Updates a connector's name, enabled flag, config, or credentials.
	 *
	 * <p>
	 * HTTP: {@code PATCH /api/admin/connectors/{id}} — admin-only.
	 *
	 * <p>
	 * Only fields present in the request body are updated. Credentials, if
	 * supplied, are re-encrypted before saving.
	 *
	 * @param id   the connector ID to update
	 * @param body request body with any combination of: {@code enabled} (boolean),
	 *             {@code name} (string), {@code config} (object),
	 *             {@code credentials} (object or string)
	 * @param req  the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {ok: true}}; or 401/403 if not an admin
	 * @since v2026.1.0
	 */
	@PatchMapping("/{id}")
	public ResponseEntity<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body,
			HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;

		String now = Instant.now().toString();
		if (body.containsKey("enabled"))
			db.update("UPDATE connector_configs SET enabled=?, updated_at=? WHERE id=?",
					Boolean.TRUE.equals(body.get("enabled")) ? 1 : 0, now, id);
		if (body.containsKey("name"))
			db.update("UPDATE connector_configs SET name=?, updated_at=? WHERE id=?", body.get("name"), now, id);
		if (body.containsKey("config")) {
			try {
				String cfgJson = body.get("config") instanceof String ? (String) body.get("config")
						: mapper.writeValueAsString(body.get("config"));
				db.update("UPDATE connector_configs SET config_json=?, updated_at=? WHERE id=?", cfgJson, now, id);
			} catch (Exception ignore) {
			}
		}
		if (body.containsKey("credentials") && body.get("credentials") != null) {
			try {
				String credJson = body.get("credentials") instanceof String ? (String) body.get("credentials")
						: mapper.writeValueAsString(body.get("credentials"));
				db.update("UPDATE connector_configs SET credentials_enc=?, updated_at=? WHERE id=?",
						cryptoService.encryptKey(credJson), now, id);
			} catch (Exception ignore) {
			}
		}
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Permanently deletes a connector configuration.
	 *
	 * <p>
	 * HTTP: {@code DELETE /api/admin/connectors/{id}} — admin-only.
	 *
	 * @param id  the connector ID to delete
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {ok: true}}; or 401/403 if not an admin
	 * @since v2026.1.0
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Map<String, Object>> delete(@PathVariable String id, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		db.update("DELETE FROM connector_configs WHERE id = ?", id);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Triggers an asynchronous document sync for a connector.
	 *
	 * <p>
	 * HTTP: {@code POST /api/admin/connectors/{id}/sync} — admin-only.
	 *
	 * <p>
	 * The sync runs on a Java virtual thread so this endpoint returns immediately.
	 * A log row is created in {@code connector_sync_log} before the thread starts.
	 * On completion (success or error) both the config row and the log row are
	 * updated.
	 *
	 * @param id  the connector ID to sync
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with
	 *         {@code {ok: true, message: "Sync started in background"}}; or 404 if
	 *         the connector does not exist; or 400 if no {@link BaseConnector} is
	 *         registered for the connector's type; or 401/403 if not an admin
	 * @since v2026.1.0
	 */
	@PostMapping("/{id}/sync")
	public ResponseEntity<Map<String, Object>> sync(@PathVariable String id, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;

		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM connector_configs WHERE id = ?", id);
		if (rows.isEmpty())
			return ResponseEntity.status(404).body(Map.of("error", "Connector not found"));

		Map<String, Object> cfg = rows.get(0);
		String type = (String) cfg.get("type");
		BaseConnector connector = registry.get(type);
		if (connector == null)
			return ResponseEntity.status(400).body(Map.of("error", "No connector for type: " + type));

		// Run sync in background thread
		Thread.ofVirtual().start(() -> {
			String logId = "csl-" + Long.toString(System.currentTimeMillis(), 36);
			db.update("INSERT INTO connector_sync_log (id, connector_id, started_at, status) VALUES (?,?,?,?)", logId,
					id, Instant.now().toString(), "running");
			db.update("UPDATE connector_configs SET sync_status='syncing', updated_at=? WHERE id=?",
					Instant.now().toString(), id);
			try {
				String credEnc = (String) cfg.get("credentials_enc");
				String creds = (credEnc != null && !credEnc.isBlank()) ? cryptoService.decryptKey(credEnc) : "{}";
				BaseConnector.SyncResult result = connector.sync(cfg, creds);
				if (result.isOk()) {
					db.update(
							"UPDATE connector_configs SET sync_status='ok', last_synced_at=?, sync_error=NULL, docs_total=docs_total+?, updated_at=? WHERE id=?",
							Instant.now().toString(), result.synced(), Instant.now().toString(), id);
					db.update("UPDATE connector_sync_log SET finished_at=?, docs_synced=?, status='ok' WHERE id=?",
							Instant.now().toString(), result.synced(), logId);
				} else {
					db.update("UPDATE connector_configs SET sync_status='error', sync_error=?, updated_at=? WHERE id=?",
							result.error(), Instant.now().toString(), id);
					db.update("UPDATE connector_sync_log SET finished_at=?, error=?, status='error' WHERE id=?",
							Instant.now().toString(), result.error(), logId);
				}
			} catch (Exception e) {
				db.update("UPDATE connector_configs SET sync_status='error', sync_error=?, updated_at=? WHERE id=?",
						e.getMessage(), Instant.now().toString(), id);
				db.update("UPDATE connector_sync_log SET finished_at=?, error=?, status='error' WHERE id=?",
						Instant.now().toString(), e.getMessage(), logId);
			}
		});

		return ResponseEntity.ok(Map.of("ok", true, "message", "Sync started in background"));
	}

	/**
	 * Tests a connector's stored credentials synchronously.
	 *
	 * <p>
	 * HTTP: {@code POST /api/admin/connectors/{id}/test} — admin-only.
	 *
	 * <p>
	 * Decrypts the stored credentials and delegates to
	 * {@link BaseConnector#testConnection(Map, String)}. Any exception is caught
	 * and returned as {@code ok: false} rather than propagating a 500.
	 *
	 * @param id  the connector ID to test
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {ok: boolean, message: string}}; or 404 if the
	 *         connector does not exist; or 400 if no connector implementation is
	 *         registered for the type; or 401/403 if not an admin
	 * @since v2026.1.0
	 */
	@PostMapping("/{id}/test")
	public ResponseEntity<Map<String, Object>> test(@PathVariable String id, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;

		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM connector_configs WHERE id = ?", id);
		if (rows.isEmpty())
			return ResponseEntity.status(404).body(Map.of("error", "Connector not found"));
		Map<String, Object> cfg = rows.get(0);
		String type = (String) cfg.get("type");
		BaseConnector connector = registry.get(type);
		if (connector == null)
			return ResponseEntity.status(400).body(Map.of("error", "No connector for type: " + type));

		try {
			String credEnc = (String) cfg.get("credentials_enc");
			String creds = (credEnc != null && !credEnc.isBlank()) ? cryptoService.decryptKey(credEnc) : "{}";
			boolean ok = connector.testConnection(cfg, creds);
			return ResponseEntity.ok(Map.of("ok", ok, "message", ok ? "Connection successful" : "Connection failed"));
		} catch (Exception e) {
			return ResponseEntity.ok(Map.of("ok", false, "message", e.getMessage()));
		}
	}

	/**
	 * Returns the 20 most recent sync log entries for a connector.
	 *
	 * <p>
	 * HTTP: {@code GET /api/admin/connectors/{id}/logs} — admin-only.
	 *
	 * <p>
	 * Results are ordered newest-first. Each row includes started_at, finished_at,
	 * status, docs_synced, and error (if any).
	 *
	 * @param id  the connector ID
	 * @param req the current HTTP request (for admin auth check)
	 * @return 200 OK with {@code {ok: true, logs: [logEntry]}}; or 401/403
	 * @since v2026.1.0
	 */
	@GetMapping("/{id}/logs")
	public ResponseEntity<Map<String, Object>> logs(@PathVariable String id, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		List<Map<String, Object>> logs = db.queryForList(
				"SELECT * FROM connector_sync_log WHERE connector_id = ? ORDER BY started_at DESC LIMIT 20", id);
		return ResponseEntity.ok(Map.of("ok", true, "logs", logs));
	}
}
