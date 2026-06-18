package com.ollanest.controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.config.AppConfig;

/**
 * Provides the minimal data the frontend needs before the user has logged in.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * {@code GET /api/bootstrap} is the first API call the browser makes on page
 * load, before any session exists. It tells the UI the server is up and whether
 * to show the first-boot setup wizard, returning:
 * <ul>
 * <li>{@code ready} — always {@code true}; the server is up</li>
 * <li>{@code firstBoot} — {@code true} when the admin account still has the
 * factory-default password, prompting the setup wizard</li>
 * </ul>
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Intentionally unauthenticated, so since the CRIT-6 hardening it returns
 * <em>no</em> sensitive data (no admin email, paths, or config) to anonymous
 * callers.</li>
 * <li>First-boot is detected by BCrypt-checking the stored hash against the
 * known default password rather than a separate flag, so it becomes
 * {@code false} automatically once the admin changes their password.</li>
 * <li>The BCrypt check (~250ms at cost 12) is cached for 60s so it does not run
 * on every page load (PERF-1).</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration; CRIT-6 hardening removed
 * the admin email from the unauthenticated response</li>
 * <li>v2026.1.9 — PERF-1: cache the first-boot BCrypt check</li>
 * </ul>
 *
 * <pre>
 *   GET /api/bootstrap — readiness + first-boot flag (unauthenticated)
 * </pre>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.9
 */
@RestController
@RequestMapping("/api/bootstrap")
public class BootstrapController {

	/** Used to query the admin account's password hash for first-boot detection. */
	private final JdbcTemplate db;

	/** Provides the default admin password string for BCrypt comparison. */
	private final AppConfig appConfig;

	/**
	 * Cached first-boot result. BCrypt.checkpw takes ~250ms at cost 12; caching
	 * eliminates that latency on all subsequent bootstrap calls. The cache is
	 * invalidated after 60 seconds to detect a password change without a restart.
	 */
	private final AtomicBoolean cachedFirstBoot = new AtomicBoolean(false);

	/** Epoch-millis timestamp at which {@link #cachedFirstBoot} expires. */
	private final AtomicLong cacheExpiry = new AtomicLong(0);

	/** Time-to-live for the cached first-boot result (60 seconds). */
	private static final long CACHE_TTL_MS = 60_000L;

	/**
	 * Constructor-injects the JDBC template and application configuration.
	 *
	 * @param db        the JDBC template for DB queries
	 * @param appConfig the application configuration bean
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	public BootstrapController(JdbcTemplate db, AppConfig appConfig) {
		this.db = db;
		this.appConfig = appConfig;
	}

	/**
	 * Returns the application readiness flag and first-boot indicator.
	 *
	 * <p>
	 * Queries the {@code users} table for the {@code u-admin} account's password
	 * hash and BCrypt-checks it against the configured default password. If the
	 * check passes, the admin has not yet changed the factory password and
	 * {@code firstBoot} is set to {@code true}.
	 *
	 * <p>
	 * Never returns admin email, server paths, or any other sensitive data — this
	 * endpoint is accessible without authentication.
	 *
	 * @return 200 OK with {@code {ready: true, firstBoot: boolean}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 * @version v2026.1.0 — security hardening: removed sensitive fields from
	 *          response
	 */
	@GetMapping
	public ResponseEntity<Map<String, Object>> bootstrap() {
		boolean firstBoot = computeFirstBoot();
		return ResponseEntity.ok(Map.of("ready", true, "firstBoot", firstBoot));
	}

	/**
	 * Computes the first-boot flag with a 60-second TTL cache to avoid running
	 * BCrypt.checkpw (≈250ms) on every page load.
	 *
	 * <p>
	 * Thread safety: multiple concurrent callers may all compute the value on cache
	 * miss; this is acceptable since BCrypt is idempotent and the result is
	 * consistent. The cache is intentionally not locked to keep the hot path fast.
	 *
	 * @return {@code true} if the admin password is still the factory default
	 * @since v2026.1.9 — performance fix: cache BCrypt check (PERF-1)
	 */
	private boolean computeFirstBoot() {
		long now = System.currentTimeMillis();
		if (now < cacheExpiry.get()) {
			return cachedFirstBoot.get();
		}
		List<Map<String, Object>> rows = db.queryForList("SELECT password_hash FROM users WHERE id = 'u-admin'");
		boolean result = false;
		if (!rows.isEmpty()) {
			String hash = (String) rows.get(0).get("password_hash");
			result = hash != null && BCrypt.checkpw(appConfig.getDefaultAdminPassword(), hash);
		}
		cachedFirstBoot.set(result);
		cacheExpiry.set(now + CACHE_TTL_MS);
		return result;
	}
}
