package com.ollanest.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Integrates with the Bitwarden/Vaultwarden CLI ({@code bw}) to allow the agent
 * to retrieve secrets from the user's password vault.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Power users want the agent to look up credentials and API keys from their
 * Bitwarden vault without ever exposing the master password or session token to
 * the LLM. This service acts as the security boundary: it invokes {@code bw} as
 * a subprocess, extracts the {@code BW_SESSION} token from its output, encrypts
 * the token via {@link CryptoService} before persisting it to the database, and
 * provides a {@link #getItem} method that the agent can call with only an item
 * name.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The raw session token is never stored in plain text; it is encrypted by
 * {@link CryptoService#encryptKey} before being written to
 * {@code vault_config}.</li>
 * <li>A singleton row pattern ({@code id='singleton'}) is used because each
 * Olla Nest instance has at most one vault configuration.</li>
 * <li>Subprocess timeouts are enforced (30 s for unlock, 15 s for item
 * retrieval) and the process is force-killed on breach to prevent zombie
 * processes.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced as part of the secrets management expansion</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class VaultService {

	private static final Logger log = LoggerFactory.getLogger(VaultService.class);

	/**
	 * JDBC template for reading and updating the singleton {@code vault_config}
	 * row.
	 */
	private final JdbcTemplate db;

	/** Used to encrypt the {@code BW_SESSION} token before persisting it. */
	private final CryptoService cryptoService;

	/**
	 * Constructor-injects persistence and encryption dependencies.
	 *
	 * @param db            the JDBC template for vault config persistence
	 * @param cryptoService the encryption service for session token storage
	 * @since v2026.2.1
	 */
	public VaultService(JdbcTemplate db, CryptoService cryptoService) {
		this.db = db;
		this.cryptoService = cryptoService;
	}

	/**
	 * Returns the vault configuration (path, server URL, enabled flag), excluding
	 * the encrypted session token.
	 *
	 * @return the vault config map, or {@code {enabled: false}} if not configured
	 * @since v2026.2.1
	 */
	public Map<String, Object> getConfig() {
		var rows = db.queryForList("SELECT id, bw_path, server_url, enabled FROM vault_config WHERE id='singleton'");
		if (rows.isEmpty())
			return Map.of("enabled", false);
		Map<String, Object> r = new LinkedHashMap<>(rows.get(0));
		r.remove("session_enc"); // never expose
		return r;
	}

	/**
	 * Saves or updates the vault CLI configuration (path and optional server URL).
	 *
	 * @param bwPath    the filesystem path to the {@code bw} executable, or
	 *                  {@code null} to use the default ({@code "bw"})
	 * @param serverUrl the Vaultwarden server URL, or {@code null} to use the
	 *                  Bitwarden cloud
	 * @since v2026.2.1
	 */
	public void saveConfig(String bwPath, String serverUrl) {
		String now = Instant.now().toString();
		int updated = db.update("UPDATE vault_config SET bw_path=?, server_url=?, updated_at=? WHERE id='singleton'",
				bwPath != null ? bwPath : "bw", serverUrl, now);
		if (updated == 0) {
			db.update(
					"INSERT INTO vault_config (id, bw_path, server_url, enabled, updated_at) VALUES ('singleton',?,?,0,?)",
					bwPath != null ? bwPath : "bw", serverUrl, now);
		}
	}

	/**
	 * Unlocks the vault by invoking {@code bw unlock} and encrypting the resulting
	 * {@code BW_SESSION} token for storage.
	 *
	 * @param masterPassword the vault master password (never persisted)
	 * @return {@code {ok: true}} on success, or {@code {ok: false, error: ...}} on
	 *         failure
	 * @since v2026.2.1
	 */
	public Map<String, Object> unlock(String masterPassword) {
		String bwPath = getBwPath();
		try {
			ProcessBuilder pb = new ProcessBuilder(bwPath, "unlock", "--passwordenv", "BW_MASTER")
					.redirectErrorStream(true);
			pb.environment().put("BW_MASTER", masterPassword);
			String serverUrl = getServerUrl();
			if (serverUrl != null && !serverUrl.isBlank()) {
				pb.environment().put("BW_SERVER", serverUrl);
			}
			Process p = pb.start();
			boolean finished = p.waitFor(30, TimeUnit.SECONDS);
			if (!finished) {
				p.destroyForcibly();
				return Map.of("ok", false, "error", "Timeout unlocking vault");
			}

			String output = new String(p.getInputStream().readAllBytes());
			// Parse BW_SESSION from output: "export BW_SESSION="xxxx""
			String session = null;
			for (String line : output.split("\n")) {
				if (line.contains("BW_SESSION=")) {
					session = line.replaceAll(".*BW_SESSION=\"?([^\"\\s]+)\"?.*", "$1").trim();
					break;
				}
			}

			if (session == null || session.contains("BW_SESSION")) {
				return Map.of("ok", false, "error",
						"Failed to unlock: " + output.substring(0, Math.min(200, output.length())));
			}

			String encSession = cryptoService.encryptKey(session);
			db.update("UPDATE vault_config SET session_enc=?, enabled=1, updated_at=? WHERE id='singleton'", encSession,
					Instant.now().toString());
			return Map.of("ok", true, "message", "Vault unlocked successfully");

		} catch (Exception e) {
			log.warn("[vault] Unlock failed: {}", e.getMessage());
			return Map.of("ok", false, "error", e.getMessage());
		}
	}

	/**
	 * Locks the vault by clearing the stored session token and disabling vault
	 * access.
	 *
	 * @since v2026.2.1
	 */
	public void lock() {
		db.update("UPDATE vault_config SET session_enc=NULL, enabled=0, updated_at=? WHERE id='singleton'",
				Instant.now().toString());
	}

	/**
	 * Returns {@code true} if the vault is currently unlocked (has an active
	 * session token).
	 *
	 * @return {@code true} if the vault session is active; {@code false} otherwise
	 * @since v2026.2.1
	 */
	public boolean isUnlocked() {
		var rows = db.queryForList("SELECT session_enc, enabled FROM vault_config WHERE id='singleton'");
		if (rows.isEmpty())
			return false;
		return rows.get(0).get("session_enc") != null && Integer.valueOf(1).equals(rows.get(0).get("enabled"));
	}

	/**
	 * Retrieves a vault item by name using the active {@code BW_SESSION}.
	 *
	 * @param name the vault item name or ID to retrieve
	 * @return {@code {ok: true, item: ...}} on success, or
	 *         {@code {ok: false, error: ...}} if the vault is locked or the command
	 *         fails
	 * @since v2026.2.1
	 */
	public Map<String, Object> getItem(String name) {
		if (!isUnlocked())
			return Map.of("ok", false, "error", "Vault is locked");
		String session = getSession();
		if (session == null)
			return Map.of("ok", false, "error", "No active session");
		try {
			ProcessBuilder pb = new ProcessBuilder(getBwPath(), "get", "item", name).redirectErrorStream(true);
			pb.environment().put("BW_SESSION", session);
			Process p = pb.start();
			boolean finished = p.waitFor(15, TimeUnit.SECONDS);
			if (!finished) {
				p.destroyForcibly();
				return Map.of("ok", false, "error", "Timeout");
			}
			String output = new String(p.getInputStream().readAllBytes());
			return Map.of("ok", true, "item", output);
		} catch (Exception e) {
			return Map.of("ok", false, "error", e.getMessage());
		}
	}

	private String getBwPath() {
		var rows = db.queryForList("SELECT bw_path FROM vault_config WHERE id='singleton'");
		return rows.isEmpty() ? "bw" : (String) rows.get(0).getOrDefault("bw_path", "bw");
	}

	private String getServerUrl() {
		var rows = db.queryForList("SELECT server_url FROM vault_config WHERE id='singleton'");
		return rows.isEmpty() ? null : (String) rows.get(0).get("server_url");
	}

	private String getSession() {
		try {
			var rows = db.queryForList("SELECT session_enc FROM vault_config WHERE id='singleton'");
			if (rows.isEmpty() || rows.get(0).get("session_enc") == null)
				return null;
			return cryptoService.decryptKey((String) rows.get(0).get("session_enc"));
		} catch (Exception e) {
			return null;
		}
	}
}
