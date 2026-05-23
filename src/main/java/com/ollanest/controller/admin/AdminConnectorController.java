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
 * Admin REST API for managing connector integrations.
 *
 * <p>All endpoints require admin role.
 *
 * <pre>
 *   GET    /api/admin/connectors                 — list all connectors
 *   GET    /api/admin/connectors/types           — list registered connector types
 *   POST   /api/admin/connectors                 — create connector
 *   PATCH  /api/admin/connectors/{id}            — update connector config/enable
 *   DELETE /api/admin/connectors/{id}            — delete connector
 *   POST   /api/admin/connectors/{id}/sync       — trigger manual sync
 *   POST   /api/admin/connectors/{id}/test       — test credentials
 *   GET    /api/admin/connectors/{id}/logs       — recent sync logs
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/connectors")
public class AdminConnectorController extends BaseController {

    private final JdbcTemplate db;
    private final ConnectorRegistry registry;
    private final ConnectorSyncScheduler scheduler;
    private final CryptoService cryptoService;
    private final ObjectMapper mapper;

    public AdminConnectorController(JdbcTemplate db, ConnectorRegistry registry,
                                     ConnectorSyncScheduler scheduler,
                                     CryptoService cryptoService, ObjectMapper mapper) {
        this.db          = db;
        this.registry    = registry;
        this.scheduler   = scheduler;
        this.cryptoService = cryptoService;
        this.mapper      = mapper;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id, name, type, enabled, auth_type, config_json, " +
                "last_synced_at, sync_status, sync_error, docs_total, created_at FROM connector_configs ORDER BY name");
        // Never return encrypted credentials
        return ResponseEntity.ok(Map.of("ok", true, "connectors", rows));
    }

    @GetMapping("/types")
    public ResponseEntity<Map<String, Object>> types(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        return ResponseEntity.ok(Map.of("ok", true, "types", registry.types()));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        User admin = getUser(req);

        String id = "conn-" + body.get("type") + "-" + Long.toString(System.currentTimeMillis(), 36);
        String credEnc = "";
        if (body.containsKey("credentials") && body.get("credentials") != null) {
            try {
                String credJson = body.get("credentials") instanceof String
                        ? (String) body.get("credentials")
                        : mapper.writeValueAsString(body.get("credentials"));
                credEnc = cryptoService.encryptKey(credJson);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid credentials: " + e.getMessage()));
            }
        }

        String cfgJson = "{}";
        if (body.containsKey("config")) {
            try { cfgJson = body.get("config") instanceof String ? (String) body.get("config") : mapper.writeValueAsString(body.get("config")); }
            catch (Exception ignore) {}
        }

        String now = Instant.now().toString();
        db.update("INSERT INTO connector_configs (id, name, type, enabled, auth_type, credentials_enc, config_json, sync_status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, body.get("name"), body.get("type"), 1,
                body.getOrDefault("authType", "api_key"), credEnc, cfgJson, "idle", now, now);

        return ResponseEntity.ok(Map.of("ok", true, "id", id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;

        String now = Instant.now().toString();
        if (body.containsKey("enabled"))
            db.update("UPDATE connector_configs SET enabled=?, updated_at=? WHERE id=?",
                    Boolean.TRUE.equals(body.get("enabled")) ? 1 : 0, now, id);
        if (body.containsKey("name"))
            db.update("UPDATE connector_configs SET name=?, updated_at=? WHERE id=?", body.get("name"), now, id);
        if (body.containsKey("config")) {
            try {
                String cfgJson = body.get("config") instanceof String ? (String) body.get("config") : mapper.writeValueAsString(body.get("config"));
                db.update("UPDATE connector_configs SET config_json=?, updated_at=? WHERE id=?", cfgJson, now, id);
            } catch (Exception ignore) {}
        }
        if (body.containsKey("credentials") && body.get("credentials") != null) {
            try {
                String credJson = body.get("credentials") instanceof String ? (String) body.get("credentials") : mapper.writeValueAsString(body.get("credentials"));
                db.update("UPDATE connector_configs SET credentials_enc=?, updated_at=? WHERE id=?", cryptoService.encryptKey(credJson), now, id);
            } catch (Exception ignore) {}
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        db.update("DELETE FROM connector_configs WHERE id = ?", id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<Map<String, Object>> sync(
            @PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;

        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM connector_configs WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Connector not found"));

        Map<String, Object> cfg = rows.get(0);
        String type = (String) cfg.get("type");
        BaseConnector connector = registry.get(type);
        if (connector == null) return ResponseEntity.status(400).body(Map.of("error", "No connector for type: " + type));

        // Run sync in background thread
        Thread.ofVirtual().start(() -> {
            String logId = "csl-" + Long.toString(System.currentTimeMillis(), 36);
            db.update("INSERT INTO connector_sync_log (id, connector_id, started_at, status) VALUES (?,?,?,?)", logId, id, Instant.now().toString(), "running");
            db.update("UPDATE connector_configs SET sync_status='syncing', updated_at=? WHERE id=?", Instant.now().toString(), id);
            try {
                String credEnc = (String) cfg.get("credentials_enc");
                String creds   = (credEnc != null && !credEnc.isBlank()) ? cryptoService.decryptKey(credEnc) : "{}";
                BaseConnector.SyncResult result = connector.sync(cfg, creds);
                if (result.isOk()) {
                    db.update("UPDATE connector_configs SET sync_status='ok', last_synced_at=?, sync_error=NULL, docs_total=docs_total+?, updated_at=? WHERE id=?", Instant.now().toString(), result.synced(), Instant.now().toString(), id);
                    db.update("UPDATE connector_sync_log SET finished_at=?, docs_synced=?, status='ok' WHERE id=?", Instant.now().toString(), result.synced(), logId);
                } else {
                    db.update("UPDATE connector_configs SET sync_status='error', sync_error=?, updated_at=? WHERE id=?", result.error(), Instant.now().toString(), id);
                    db.update("UPDATE connector_sync_log SET finished_at=?, error=?, status='error' WHERE id=?", Instant.now().toString(), result.error(), logId);
                }
            } catch (Exception e) {
                db.update("UPDATE connector_configs SET sync_status='error', sync_error=?, updated_at=? WHERE id=?", e.getMessage(), Instant.now().toString(), id);
                db.update("UPDATE connector_sync_log SET finished_at=?, error=?, status='error' WHERE id=?", Instant.now().toString(), e.getMessage(), logId);
            }
        });

        return ResponseEntity.ok(Map.of("ok", true, "message", "Sync started in background"));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(
            @PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;

        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM connector_configs WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Connector not found"));
        Map<String, Object> cfg = rows.get(0);
        String type = (String) cfg.get("type");
        BaseConnector connector = registry.get(type);
        if (connector == null) return ResponseEntity.status(400).body(Map.of("error", "No connector for type: " + type));

        try {
            String credEnc = (String) cfg.get("credentials_enc");
            String creds   = (credEnc != null && !credEnc.isBlank()) ? cryptoService.decryptKey(credEnc) : "{}";
            boolean ok = connector.testConnection(cfg, creds);
            return ResponseEntity.ok(Map.of("ok", ok, "message", ok ? "Connection successful" : "Connection failed"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<Map<String, Object>> logs(
            @PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        List<Map<String, Object>> logs = db.queryForList(
                "SELECT * FROM connector_sync_log WHERE connector_id = ? ORDER BY started_at DESC LIMIT 20", id);
        return ResponseEntity.ok(Map.of("ok", true, "logs", logs));
    }
}
