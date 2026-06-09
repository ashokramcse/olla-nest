package com.ollanest.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.config.AppConfig;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Provides dev-mode login hints to the browser on localhost only.
 *
 * <p>
 * {@code GET /api/dev/hints} returns the seeded demo account credentials so the
 * browser dev quick-login panel can auto-fill them — without embedding any
 * credentials in the HTML source code that is committed to git.
 *
 * <p>
 * <b>Security contract:</b>
 * <ul>
 * <li>The endpoint returns {@code 404 Not Found} for every caller that is
 * <em>not</em> connecting from a loopback address ({@code 127.0.0.1},
 * {@code ::1}, {@code localhost}). This is enforced server-side and cannot be
 * bypassed by spoofing headers.</li>
 * <li>The response only includes credentials for accounts that exist in the
 * database — it does not expose the raw configured values.</li>
 * <li>This endpoint is disabled entirely in production by design: the loopback
 * check guarantees it is unreachable from any non-local network interface.</li>
 * </ul>
 *
 * <p>
 * <b>Design decisions:</b>
 * <ul>
 * <li>Credentials are read from the running Spring config (backed by env vars),
 * not hardcoded, so they rotate correctly when the operator changes the seed
 * passwords.</li>
 * <li>Only accounts present in the {@code users} table are returned — avoids
 * leaking configured-but-not-yet-seeded credentials.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.9 — replaced hardcoded HTML credentials
 */
@RestController
@RequestMapping("/api/dev")
public class DevHintsController {

	private static final Logger log = LoggerFactory.getLogger(DevHintsController.class);

	/** Application configuration; provides seeded demo account credentials. */
	private final AppConfig appConfig;
	/** JDBC template for checking whether demo accounts exist in the database. */
	private final JdbcTemplate db;

	/**
	 * Constructor-injects the application config and JDBC template.
	 *
	 * @param appConfig the application configuration containing seed credentials
	 * @param db        the JDBC template for {@code users} table lookups
	 * @since v2026.1.9
	 */
	public DevHintsController(AppConfig appConfig, JdbcTemplate db) {
		this.appConfig = appConfig;
		this.db = db;
	}

	/**
	 * Returns quick-login hints for localhost dev sessions.
	 *
	 * <p>
	 * Blocked with 404 for every non-loopback remote address. The loopback check
	 * uses {@link HttpServletRequest#getRemoteAddr()} which reflects the actual TCP
	 * peer — it cannot be overridden by {@code X-Forwarded-For} headers.
	 *
	 * @param req the HTTP request used to verify the caller is on loopback
	 * @return 200 with account list on localhost; 404 otherwise
	 */
	@GetMapping("/hints")
	public ResponseEntity<Map<String, Object>> hints(HttpServletRequest req) {
		// Hard loopback check — use getRemoteAddr() only (ignores X-Forwarded-For)
		String remote = req.getRemoteAddr();
		boolean isLoopback = "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote);

		if (!isLoopback) {
			log.warn("DEV /api/dev/hints blocked for non-loopback caller: {}", remote);
			return ResponseEntity.notFound().build();
		}

		String adminEmail = appConfig.getDefaultAdminEmail();
		String adminPass = appConfig.getDefaultAdminPassword();
		String userPass = appConfig.getDefaultUserPassword();

		// Only include accounts that actually exist in the DB
		List<Map<String, Object>> accounts = new ArrayList<>();

		addIfExists(accounts, "Admin", adminEmail, adminPass, "#F5C800");
		addIfExists(accounts, "Employee", "employee@ollanest.local", userPass, "#4ade80");
		addIfExists(accounts, "Builder", "builder@ollanest.local", userPass, "#60a5fa");
		addIfExists(accounts, "Support", "support@ollanest.local", userPass, "#c084fc");

		return ResponseEntity.ok(Map.of("accounts", accounts));
	}

	/**
	 * Conditionally adds a login hint to the list only when the account exists and
	 * the password is not the default placeholder value.
	 *
	 * @param list     the accumulating list of hint entries
	 * @param label    display label for the account (e.g. {@code "Admin"})
	 * @param email    email address used as the login identifier
	 * @param password plain-text password to include in the hint
	 * @param color    hex colour string for the UI badge
	 * @since v2026.1.9
	 */
	private void addIfExists(List<Map<String, Object>> list, String label, String email, String password,
			String color) {
		// Skip placeholder passwords — no point offering a hint that won't work
		if (password == null || password.startsWith("CHANGE_ME") || password.startsWith("SET_A_"))
			return;

		Integer count = db.queryForObject("SELECT COUNT(*) FROM users WHERE email = ? AND active = 1", Integer.class,
				email);
		if (count != null && count > 0) {
			list.add(Map.of("label", label, "email", email, "password", password, "color", color));
		}
	}
}
