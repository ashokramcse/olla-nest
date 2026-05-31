package com.ollanest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled SQLite backup service using the {@code VACUUM INTO} statement.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest stores all application state (sessions, chats, models, audit log)
 * in a single SQLite file. This service takes a consistent online snapshot of
 * that file once per day without requiring the application to stop or acquiring
 * any exclusive lock, using SQLite's built-in {@code VACUUM INTO} command. It
 * also performs a rolling retention of the last seven backups to bound disk
 * usage.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>{@code VACUUM INTO} is preferred over file-copy because it rewrites the
 * database into a clean, defragmented SQLite file atomically, even while other
 * connections are active.</li>
 * <li>Backup filenames embed an ISO-8601-like timestamp
 * ({@code yyyy-MM-dd'T'HH-mm-ss}) so they sort lexicographically in
 * chronological order, allowing simple {@link Arrays#sort} to identify the
 * oldest files for pruning.</li>
 * <li>Errors in the scheduled job are swallowed after logging so they never
 * propagate to the scheduler thread and disable future runs.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial migration from Node.js/better-sqlite3 backup cron
 * job</li>
 * <li>v2026.1.4 — added {@link #runBackup()} as a public method callable from
 * the admin REST endpoint for on-demand backups</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.10 — L-3: backup filenames now use UTC timestamps
 */
@Service
public class BackupService {

	private static final Logger log = LoggerFactory.getLogger(BackupService.class);

	/** JDBC template bound to the application's SQLite data source. */
	private final JdbcTemplate db;

	/**
	 * Guard preventing concurrent backup execution.
	 *
	 * <p>
	 * {@code VACUUM INTO} holds a shared read lock on the database for its
	 * duration. Running two concurrent backups would produce conflicting
	 * filenames (same second timestamp) and could exhaust disk space with
	 * two simultaneous full-copy writes. Using a CAS flag ensures only one
	 * backup runs at a time; additional requests are rejected immediately.
	 */
	private final AtomicBoolean backupInProgress = new AtomicBoolean(false);

	/**
	 * Returns {@code true} if a backup is currently running.
	 * Exposed for monitoring and testing.
	 *
	 * @return {@code true} if backup is in progress
	 * @since v2026.1.0 — SOC 2 hardening
	 */
	public boolean isBackupInProgress() {
		return backupInProgress.get();
	}

	/**
	 * Root data directory; resolved from the {@code app.data-dir} property or the
	 * {@code APP_DATA_DIR} environment variable, defaulting to {@code ./data}.
	 * Backups are written to {@code <dataDir>/backups/}.
	 */
	@Value("${app.data-dir:./data}")
	private String dataDir;

	/**
	 * Constructs the service with the application's primary {@link JdbcTemplate}.
	 *
	 * @param db the JDBC template wired to the SQLite data source
	 * @since v2026.1.0
	 */
	public BackupService(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * Scheduled entry point that triggers a backup every day at 03:00 local time.
	 *
	 * <p>
	 * Any exception thrown by {@link #runBackup()} is caught and logged at
	 * {@code ERROR} level so the scheduler does not suppress future executions.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.0
	 */
	@Scheduled(cron = "0 0 3 * * *") // Run daily at 3 AM
	public void scheduledBackup() {
		try {
			runBackup();
		} catch (Exception e) {
			log.error("[backup] Scheduled backup failed: {}", e.getMessage());
		}
	}

	/**
	 * Performs an immediate, on-demand backup of the SQLite database using
	 * {@code VACUUM INTO} and prunes old backups so that at most seven are
	 * retained.
	 *
	 * <p>
	 * The backup file is written to
	 * {@code <dataDir>/backups/olla-nest-<timestamp>.sqlite}. The timestamp format
	 * is {@code yyyy-MM-dd'T'HH-mm-ss}. After writing the new backup, all
	 * {@code .sqlite} files in the backup directory are sorted lexicographically
	 * and any files beyond the seven most recent are deleted.
	 *
	 * @return a {@link java.util.Map} with:
	 *         <ul>
	 *         <li>{@code ok=true, file=<absolute-path>} on success</li>
	 *         <li>{@code ok=false, error=<message>} on failure</li>
	 *         </ul>
	 * @since v2026.1.4
	 */
	public java.util.Map<String, Object> runBackup() {
		// Reject concurrent backup attempts — VACUUM INTO is a heavy full-file copy
		if (!backupInProgress.compareAndSet(false, true)) {
			log.warn("[backup] Backup already in progress — rejecting concurrent request");
			return java.util.Map.of("ok", false, "error", "Backup already in progress");
		}

		File backupDir = new File(dataDir, "backups");
		if (!backupDir.exists())
			backupDir.mkdirs();

		String ts = LocalDateTime.now(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'"));
		File dest = new File(backupDir, "olla-nest-" + ts + ".sqlite");

		try {
			db.execute("VACUUM INTO '" + dest.getAbsolutePath().replace("'", "''") + "'");

			// Keep only last 7 backups
			File[] backups = backupDir.listFiles(f -> f.getName().endsWith(".sqlite"));
			if (backups != null && backups.length > 7) {
				Arrays.sort(backups, Comparator.comparing(File::getName));
				for (int i = 0; i < backups.length - 7; i++) {
					backups[i].delete();
				}
			}
			log.info("[backup] Backup written to {}", dest.getAbsolutePath());
			return java.util.Map.of("ok", true, "file", dest.getAbsolutePath());
		} catch (Exception e) {
			log.error("[backup] Backup failed: {}", e.getMessage());
			return java.util.Map.of("ok", false, "error", e.getMessage());
		} finally {
			backupInProgress.set(false);
		}
	}
}
