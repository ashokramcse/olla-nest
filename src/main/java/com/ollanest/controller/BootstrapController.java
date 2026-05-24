package com.ollanest.controller;

import com.ollanest.config.AppConfig;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Provides the minimal data the frontend needs before the user has logged in.
 *
 * <p>
 * GET {@code /api/bootstrap} is the first API call made by the browser on page
 * load. It returns:
 * <ul>
 * <li>{@code ready} — always {@code true}; indicates the server is up</li>
 * <li>{@code firstBoot} — {@code true} when the admin account still has the
 * factory-default password, prompting the UI to show the setup wizard</li>
 * </ul>
 *
 * <p>
 * This endpoint is intentionally unauthenticated. Since the security hardening
 * update (CRIT-6), it does <em>not</em> return the admin email address or any
 * other sensitive configuration to prevent information leakage to anonymous
 * callers.
 *
 * <p>
 * <b>Design decisions:</b>
 * <ul>
 * <li>First-boot detection is done by BCrypt-checking the stored hash against
 * the known default password rather than storing a separate flag, so it is
 * automatically {@code false} once the admin changes their password.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0 — initial Java Spring Boot migration
 * @version v2026.1.0 — security hardening: removed admin email from
 *          unauthenticated response (CRIT-6)
 */
@RestController
@RequestMapping("/api/bootstrap")
public class BootstrapController {

	/** Used to query the admin account's password hash for first-boot detection. */
	private final JdbcTemplate db;

	/** Provides the default admin password string for BCrypt comparison. */
	private final AppConfig appConfig;

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
		List<Map<String, Object>> rows = db.queryForList("SELECT password_hash FROM users WHERE id = 'u-admin'");
		if (!rows.isEmpty()) {
			String hash = (String) rows.get(0).get("password_hash");
			if (hash != null && BCrypt.checkpw(appConfig.getDefaultAdminPassword(), hash)) {
				return ResponseEntity.ok(Map.of("ready", true, "firstBoot", true));
			}
		}
		return ResponseEntity.ok(Map.of("ready", true, "firstBoot", false));
	}
}
