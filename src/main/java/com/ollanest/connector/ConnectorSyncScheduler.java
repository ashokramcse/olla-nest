package com.ollanest.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.ollanest.service.CryptoService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Hourly background connector sync.
 * Iterates all enabled connector_configs, calls the matching connector's sync(),
 * and records results in connector_sync_log.
 */
@Component
public class ConnectorSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConnectorSyncScheduler.class);

    private final JdbcTemplate db;
    private final ConnectorRegistry registry;
    private final CryptoService cryptoService;

    public ConnectorSyncScheduler(JdbcTemplate db, ConnectorRegistry registry, CryptoService cryptoService) {
        this.db = db;
        this.registry = registry;
        this.cryptoService = cryptoService;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 30_000)
    public void scheduledSync() {
        List<Map<String, Object>> enabled = db.queryForList(
                "SELECT * FROM connector_configs WHERE enabled = 1");
        if (enabled.isEmpty()) return;
        log.info("[connectors] Starting scheduled sync for {} connector(s)", enabled.size());

        for (Map<String, Object> cfg : enabled) {
            String id   = (String) cfg.get("id");
            String type = (String) cfg.get("type");
            BaseConnector connector = registry.get(type);
            if (connector == null) {
                log.warn("[connectors] No connector registered for type '{}'", type);
                continue;
            }

            String logId = "csl-" + Long.toString(System.currentTimeMillis(), 36);
            db.update("INSERT INTO connector_sync_log (id, connector_id, started_at, status) VALUES (?,?,?,?)",
                    logId, id, Instant.now().toString(), "running");
            db.update("UPDATE connector_configs SET sync_status='syncing', updated_at=? WHERE id=?",
                    Instant.now().toString(), id);

            try {
                String credEnc = (String) cfg.get("credentials_enc");
                String creds   = (credEnc != null && !credEnc.isBlank()) ? cryptoService.decryptKey(credEnc) : "{}";
                BaseConnector.SyncResult result = connector.sync(cfg, creds);

                if (result.isOk()) {
                    db.update("UPDATE connector_configs SET sync_status='ok', last_synced_at=?, sync_error=NULL, docs_total=docs_total+?, updated_at=? WHERE id=?",
                            Instant.now().toString(), result.synced(), Instant.now().toString(), id);
                    db.update("UPDATE connector_sync_log SET finished_at=?, docs_synced=?, status='ok' WHERE id=?",
                            Instant.now().toString(), result.synced(), logId);
                    log.info("[connectors] {} synced {} docs, skipped {}", type, result.synced(), result.skipped());
                } else {
                    db.update("UPDATE connector_configs SET sync_status='error', sync_error=?, updated_at=? WHERE id=?",
                            result.error(), Instant.now().toString(), id);
                    db.update("UPDATE connector_sync_log SET finished_at=?, error=?, status='error' WHERE id=?",
                            Instant.now().toString(), result.error(), logId);
                    log.error("[connectors] {} sync error: {}", type, result.error());
                }
            } catch (Exception e) {
                log.error("[connectors] {} unexpected error: {}", type, e.getMessage());
                db.update("UPDATE connector_configs SET sync_status='error', sync_error=?, updated_at=? WHERE id=?",
                        e.getMessage(), Instant.now().toString(), id);
                db.update("UPDATE connector_sync_log SET finished_at=?, error=?, status='error' WHERE id=?",
                        Instant.now().toString(), e.getMessage(), logId);
            }
        }
        // Clean old logs (keep 30 days)
        db.update("DELETE FROM connector_sync_log WHERE started_at < datetime('now', '-30 days')");
    }
}
