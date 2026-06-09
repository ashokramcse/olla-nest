package com.ollanest.connector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ollanest.service.CryptoService;

/**
 * Hourly background scheduler that drives incremental sync for all enabled
 * connectors.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest connectors (GitHub, Slack, Notion, Google Drive, etc.) must be kept
 * up-to-date with their upstream data sources so that the RAG retrieval layer
 * can surface fresh content. {@code ConnectorSyncScheduler} is the single
 * background driver for that process: every hour it loads all
 * {@code connector_configs} rows where {@code enabled = 1}, decrypts their
 * stored credentials, calls the appropriate {@link BaseConnector#sync}
 * implementation, and writes the outcome back to {@code connector_configs} and
 * {@code connector_sync_log}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Uses Spring's {@link Scheduled} with {@code fixedDelay = 3 600 000 ms} (1
 * hour) so that the interval is measured from the end of the previous sync, not
 * its start — preventing overlapping runs on slow connectors.</li>
 * <li>An {@code initialDelay = 30 000 ms} (30 seconds) gives the application
 * time to finish startup before the first sync attempt.</li>
 * <li>Each connector run is wrapped in a try/catch so that one failing
 * connector does not prevent the remaining connectors from syncing.</li>
 * <li>A {@code connector_sync_log} row is written for every connector run
 * (started, finished, docs_synced, status, error) to support audit and
 * debugging.</li>
 * <li>Log entries older than 30 days are pruned at the end of each scheduler
 * invocation to prevent unbounded table growth.</li>
 * <li>Credentials stored as AES-256-GCM ciphertext in {@code credentials_enc}
 * are decrypted at run time via {@link CryptoService}; the plaintext is never
 * persisted or logged.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.4</b> — initial creation; hourly fixed-delay scheduling;
 * per-connector try/catch isolation; sync-log write-back; 30-day log
 * pruning.</li>
 * <li><b>v2026.1.10</b> — MED-4: run connectors in parallel virtual threads
 * with 10-minute overall cap; L-8: add random suffix to logId to prevent ID
 * collision under concurrent runs.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.4
 * @version v2026.1.10
 * @see BaseConnector
 * @see ConnectorRegistry
 */
@Component
public class ConnectorSyncScheduler {

	/** SLF4J logger for scheduler diagnostics. */
	private static final Logger log = LoggerFactory.getLogger(ConnectorSyncScheduler.class);

	/**
	 * JDBC template for reading {@code connector_configs} and writing
	 * {@code connector_sync_log}.
	 */
	private final JdbcTemplate db;

	/**
	 * Registry used to look up the {@link BaseConnector} implementation for each
	 * config row.
	 */
	private final ConnectorRegistry registry;

	/**
	 * Crypto service for decrypting {@code credentials_enc} before passing to
	 * connectors.
	 */
	private final CryptoService cryptoService;

	/**
	 * Constructs the scheduler with the dependencies it needs to iterate and
	 * execute connectors.
	 *
	 * @param db            JDBC template for connector config and sync-log access
	 * @param registry      the connector registry for type-based dispatch
	 * @param cryptoService service for decrypting stored connector credentials
	 * @since v2026.1.4
	 */
	public ConnectorSyncScheduler(JdbcTemplate db, ConnectorRegistry registry, CryptoService cryptoService) {
		this.db = db;
		this.registry = registry;
		this.cryptoService = cryptoService;
	}

	/**
	 * Runs an incremental sync for every enabled connector configuration.
	 *
	 * <p>
	 * Execution sequence for each connector:
	 * <ol>
	 * <li>Look up the {@link BaseConnector} for the config row's {@code type}; skip
	 * if no implementation is registered.</li>
	 * <li>Insert a {@code connector_sync_log} row with
	 * {@code status = 'running'}.</li>
	 * <li>Update {@code connector_configs.sync_status} to {@code 'syncing'}.</li>
	 * <li>Decrypt {@code credentials_enc} (falls back to {@code "{}"} if
	 * absent).</li>
	 * <li>Call {@link BaseConnector#sync(Map, String)} with the config row and
	 * decrypted credentials.</li>
	 * <li>On success: update {@code sync_status = 'ok'}, increment
	 * {@code docs_total}, and finalise the sync log row.</li>
	 * <li>On failure (returned error or thrown exception): update
	 * {@code sync_status = 'error'} and record the error message.</li>
	 * </ol>
	 * After all connectors run, prune {@code connector_sync_log} rows older than 30
	 * days.
	 *
	 * <p>
	 * The method is scheduled with {@code fixedDelay = 3 600 000 ms} and
	 * {@code initialDelay = 30 000 ms}. These values are not configurable at
	 * runtime and require a restart to change.
	 *
	 * @since v2026.1.4
	 */
	@Scheduled(fixedDelay = 3_600_000, initialDelay = 30_000)
	public void scheduledSync() {
		List<Map<String, Object>> enabled = db.queryForList("SELECT * FROM connector_configs WHERE enabled = 1");
		if (enabled.isEmpty())
			return;
		log.info("[connectors] Starting scheduled sync for {} connector(s)", enabled.size());

		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		List<Future<?>> futures = new ArrayList<>();

		for (Map<String, Object> cfg : enabled) {
			futures.add(executor.submit(() -> syncOneConnector(cfg)));
		}

		// Wait for all with overall 10-minute cap
		for (Future<?> f : futures) {
			try {
				f.get(10, TimeUnit.MINUTES);
			} catch (TimeoutException e) {
				log.warn("[connectors] Connector sync exceeded 10-minute cap, cancelling remaining");
				f.cancel(true);
			} catch (Exception e) {
				log.error("[connectors] Connector sync task failed: {}", e.getMessage());
			}
		}
		executor.shutdownNow();

		// Prune log entries older than 30 days to prevent unbounded table growth
		db.update("DELETE FROM connector_sync_log WHERE started_at < datetime('now', '-30 days')");
	}

	/**
	 * Runs the incremental sync for a single connector configuration row.
	 *
	 * <p>
	 * Inserts a {@code connector_sync_log} row, calls
	 * {@link BaseConnector#sync(Map, String)}, and writes the outcome back to both
	 * {@code connector_configs} and {@code connector_sync_log}. All exceptions are
	 * caught and recorded so that one failing connector does not prevent the others
	 * from running.
	 *
	 * @param cfg the {@code connector_configs} row to sync
	 * @since v2026.1.10
	 */
	private void syncOneConnector(Map<String, Object> cfg) {
		String id = (String) cfg.get("id");
		String type = (String) cfg.get("type");
		BaseConnector connector = registry.get(type);
		if (connector == null) {
			log.warn("[connectors] No connector registered for type '{}'", type);
			return;
		}

		// L-8: append random suffix to prevent ID collision under concurrent runs
		String logId = "csl-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ Long.toString((long) (Math.random() * 1_000_000), 36);
		db.update("INSERT INTO connector_sync_log (id, connector_id, started_at, status) VALUES (?,?,?,?)", logId, id,
				Instant.now().toString(), "running");
		db.update("UPDATE connector_configs SET sync_status='syncing', updated_at=? WHERE id=?",
				Instant.now().toString(), id);

		try {
			String credEnc = (String) cfg.get("credentials_enc");
			String creds = (credEnc != null && !credEnc.isBlank()) ? cryptoService.decryptKey(credEnc) : "{}";
			BaseConnector.SyncResult result = connector.sync(cfg, creds);

			if (result.isOk()) {
				db.update(
						"UPDATE connector_configs " + "SET sync_status='ok', last_synced_at=?, sync_error=NULL, "
								+ "docs_total=docs_total+?, updated_at=? WHERE id=?",
						Instant.now().toString(), result.synced(), Instant.now().toString(), id);
				db.update("UPDATE connector_sync_log " + "SET finished_at=?, docs_synced=?, status='ok' WHERE id=?",
						Instant.now().toString(), result.synced(), logId);
				log.info("[connectors] {} synced {} docs, skipped {}", type, result.synced(), result.skipped());
			} else {
				db.update(
						"UPDATE connector_configs " + "SET sync_status='error', sync_error=?, updated_at=? WHERE id=?",
						result.error(), Instant.now().toString(), id);
				db.update("UPDATE connector_sync_log " + "SET finished_at=?, error=?, status='error' WHERE id=?",
						Instant.now().toString(), result.error(), logId);
				log.error("[connectors] {} sync error: {}", type, result.error());
			}
		} catch (Exception e) {
			log.error("[connectors] {} unexpected error: {}", type, e.getMessage());
			db.update("UPDATE connector_configs " + "SET sync_status='error', sync_error=?, updated_at=? WHERE id=?",
					e.getMessage(), Instant.now().toString(), id);
			db.update("UPDATE connector_sync_log " + "SET finished_at=?, error=?, status='error' WHERE id=?",
					Instant.now().toString(), e.getMessage(), logId);
		}
	}
}
