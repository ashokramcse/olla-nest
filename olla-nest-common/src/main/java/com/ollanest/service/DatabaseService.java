package com.ollanest.service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import com.ollanest.config.AppConfig;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Handles schema seeding (default data) after Flyway runs migrations.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Flyway owns DDL (table creation) but cannot know runtime defaults such as the
 * admin e-mail, the Ollama base URL, or the initial role and permission
 * catalogue. This service fills that gap: immediately after Spring finishes
 * wiring beans it runs {@link #seedDatabase()}, which inserts default rows into
 * every reference table that is still empty. The checks are idempotent — if a
 * table already has rows the seed is skipped — so restarts and rolling upgrades
 * are safe.
 *
 * <h3>Design notes</h3>
 * <p>
 * All DB access goes through {@link JdbcTemplate} using
 * {@code INSERT OR REPLACE} or {@code INSERT OR IGNORE} SQLite syntax so that
 * no external transaction management is needed. Passwords are hashed with
 * BCrypt (cost factor 12) at seed time; a first-boot sentinel value causes a
 * cryptographically random admin password to be generated and printed to the
 * log as a one-time bootstrap credential.
 * <p>
 * The {@link #getSetting(String, String)} / {@link #setSetting(String, String)}
 * pair acts as a lightweight key-value store layered on top of the
 * {@code settings} table, consumed by many other services.
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.0</b> — initial Java Spring Boot migration</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.10 — HIGH-6 startup validation that ENCRYPTION_KEY is not the insecure default
 */
@Service
public class DatabaseService {

	/** SLF4J logger for this class. */
	private static final Logger log = LoggerFactory.getLogger(DatabaseService.class);

	/** Spring JDBC template for direct SQL access to the SQLite database. */
	private final JdbcTemplate db;

	/** Application configuration (admin credentials, data directory, etc.). */
	private final AppConfig appConfig;

	/** Encryption key injected from environment — checked at startup to catch insecure defaults. */
	@Value("${encryption.key:change-me-in-production}")
	private String encryptionKey;

	/** Active Spring profiles — used to detect dev vs production environment. */
	@Autowired
	private Environment springEnv;

	/**
	 * Constructs the service with its required collaborators.
	 *
	 * @param db        Spring JDBC template bound to the application's SQLite data
	 *                  source
	 * @param appConfig application-level configuration properties
	 * @since v2026.1.0
	 */
	public DatabaseService(JdbcTemplate db, AppConfig appConfig) {
		this.db = db;
		this.appConfig = appConfig;
	}

	/**
	 * Entry point called by Spring immediately after dependency injection is
	 * complete.
	 *
	 * <p>
	 * Delegates to each individual seed method in dependency order: settings →
	 * departments → groups → permissions → roles → users. Any exception is caught
	 * and logged at ERROR level; startup continues regardless so that a seed
	 * failure does not prevent the application from running.
	 *
	 * @since v2026.1.0
	 */
	@PostConstruct
	public void seedDatabase() {
		// MED-2 FIX: Fail fast on insecure default encryption key in production.
		// In production (when the 'prod' profile is active or ENCRYPTION_KEY is
		// explicitly set to the sentinel), we must refuse to start rather than silently
		// using a well-known key that any attacker can use to decrypt all stored secrets.
		boolean isDevMode = isDevMode();
		if (encryptionKey == null || "change-me-in-production".equals(encryptionKey) || encryptionKey.length() < 32) {
			if (!isDevMode) {
				// Hard fail in non-dev — do not start with an insecure encryption key
				throw new IllegalStateException(
					"ENCRYPTION_KEY environment variable is not set or is too short. " +
					"Generate a key with: openssl rand -hex 32 " +
					"Refusing to start in production mode with an insecure default key.");
			}
			log.warn("=================================================================");
			log.warn("WARNING: ENCRYPTION_KEY is not set or is too short!");
			log.warn("Set ENCRYPTION_KEY env var to a 64-char hex string.");
			log.warn("Generate with: openssl rand -hex 32");
			log.warn("DEV MODE: Using insecure default — NEVER run in production!");
			log.warn("=================================================================");
		}
		try {
			seedSettings();
			seedDepartments();
			seedGroups();
			seedPermissions();
			seedRoles();
			seedUsers();
			log.info("[db] Database seeding complete.");
		} catch (Exception e) {
			log.error("[db] Seeding error: {}", e.getMessage(), e);
		}
		// Log security posture summary at startup for operator awareness
		logSecurityPosture(isDevMode);
	}

	/** Logs a startup security posture summary so operators see critical config status. */
	private void logSecurityPosture(boolean isDevMode) {
		log.info("╔══════════════════════════════════════════════════════╗");
		log.info("║          OLLA NEST — SECURITY POSTURE SUMMARY        ║");
		log.info("╠══════════════════════════════════════════════════════╣");
		log.info("║ Mode      : {}", isDevMode ? "DEVELOPMENT (warnings only)    ║" : "PRODUCTION (strict enforcement) ║");
		log.info("║ Encryption: {}", (encryptionKey != null && encryptionKey.length() >= 32
			&& !"change-me-in-production".equals(encryptionKey))
			? "KEY SET ✓                      ║" : "DEFAULT KEY ✗ (INSECURE)       ║");
		log.info("║ Cookie    : {}", "true".equals(System.getenv("COOKIE_SECURE"))
			? "Secure flag enabled ✓          ║" : "Secure flag OFF (HTTP mode)    ║");
		log.info("║ SAML      : {}", "true".equalsIgnoreCase(System.getProperty("saml.enabled",
			System.getenv("SAML_ENABLED")))
			? "ENABLED (no sig verify — risk!)║" : "DISABLED ✓ (safe default)      ║");
		log.info("╚══════════════════════════════════════════════════════╝");
	}

	/**
	 * Returns the number of rows currently in the named table.
	 *
	 * <p>
	 * Used by each seed method to determine whether the table is empty and
	 * therefore requires seeding. Returns {@code 0} when the count query returns
	 * {@code null}.
	 *
	 * @param table unquoted table name to count rows in
	 * @return row count, always &ge; 0
	 * @since v2026.1.0
	 */
	private int tableCount(String table) {
		// Table name cannot be parameterised in JDBC — validate against a known-safe
		// allow-list so this internal helper can never be misused as an injection vector.
		if (!table.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
			throw new IllegalArgumentException("Invalid table name: " + table);
		}
		Integer count = db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
		return count != null ? count : 0;
	}

	/**
	 * Reads a single string value from the {@code settings} table by key.
	 *
	 * <p>
	 * Returns {@code fallback} when the key does not exist, when the stored value
	 * is {@code null}, or when any SQL exception occurs (e.g. the table does not
	 * yet exist during early startup).
	 *
	 * @param key      the settings key to look up
	 * @param fallback value returned when the key is absent or the lookup fails
	 * @return the stored setting value, or {@code fallback}
	 * @since v2026.1.0
	 */
	public String getSetting(String key, String fallback) {
		try {
			List<Map<String, Object>> rows = db.queryForList("SELECT value FROM settings WHERE key = ?", key);
			if (rows.isEmpty())
				return fallback;
			String value = (String) rows.get(0).get("value");
			return value != null ? value : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	/**
	 * Reads a boolean setting from the {@code settings} table.
	 *
	 * <p>
	 * Interprets the stored string value as a boolean: {@code "true"} returns
	 * {@code true}, {@code "false"} returns {@code false}, and any other value
	 * (including absent or {@code null}) returns {@code fallback}.
	 *
	 * @param key      the settings key to look up
	 * @param fallback value returned when the key is absent or non-boolean
	 * @return the boolean interpretation of the setting, or {@code fallback}
	 * @since v2026.1.0
	 */
	public boolean getSettingBool(String key, boolean fallback) {
		String v = getSetting(key, null);
		if (v == null)
			return fallback;
		if ("true".equals(v))
			return true;
		if ("false".equals(v))
			return false;
		return fallback;
	}

	/**
	 * Persists a string setting into the {@code settings} table, replacing any
	 * existing value.
	 *
	 * <p>
	 * Uses SQLite {@code INSERT OR REPLACE} so this method is safe to call whether
	 * the key already exists or is new.
	 *
	 * @param key   the settings key; must not be {@code null}
	 * @param value the value to store; {@code null} is written as a SQL NULL
	 * @since v2026.1.0
	 */
	public void setSetting(String key, String value) {
		// settings.value has a NOT NULL constraint — coerce null to empty string so the
		// column invariant is never violated while preserving the "key is set" semantics.
		db.update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
				key, value != null ? value : "");
	}

	/**
	 * Seeds the {@code settings} table with application defaults.
	 *
	 * <p>
	 * Inserts the Ollama base URL (read from the {@code OLLAMA_URL} environment
	 * variable with a fallback of {@code http://localhost:11434}), the default
	 * workspace root, and a set of feature-flag defaults. Skipped entirely when the
	 * table already contains rows.
	 *
	 * @since v2026.1.0
	 */
	private void seedSettings() {
		if (tableCount("settings") > 0)
			return;
		String dataDir = appConfig.getDataDir();
		String defaultWorkspace = dataDir + "/workspace";
		String ollamaUrl = System.getenv("OLLAMA_URL");
		if (ollamaUrl == null || ollamaUrl.isBlank())
			ollamaUrl = "http://localhost:11434";

		Object[][] defaults = { { "activeUserId", "u-admin" }, { "routerEnabled", "true" },
				{ "allowApiModels", "false" }, { "localOnlyDefault", "true" }, { "localWritesEnabled", "true" },
				{ "workspaceRoot", defaultWorkspace }, { "localPermissionMode", "default" }, { "ollamaUrl", ollamaUrl },
				{ "apiModelProvider", "not-configured" }, { "sqlProvider", "sqlite" },
				{ "documentProvider", "json-document-store" }, { "realtimeProvider", "in-memory" }, };
		for (Object[] kv : defaults) {
			db.update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", kv[0], kv[1]);
		}
	}

	/**
	 * Seeds the {@code departments} table with three starter departments.
	 *
	 * <p>
	 * Inserts General, Product, and Support departments. Skipped when the table
	 * already has rows to avoid duplicate-key errors on restart.
	 *
	 * @since v2026.1.0
	 */
	private void seedDepartments() {
		if (tableCount("departments") > 0)
			return;
		String[][] depts = { { "dept-general", "General" }, { "dept-product", "Product" },
				{ "dept-support", "Support" }, };
		for (String[] d : depts) {
			db.update("INSERT INTO departments (id, name) VALUES (?, ?)", d[0], d[1]);
		}
	}

	/**
	 * Seeds the {@code groups} table with three starter groups.
	 *
	 * <p>
	 * Inserts All Employees, Builders, and Admins groups. Skipped when the table
	 * already has rows.
	 *
	 * @since v2026.1.0
	 */
	private void seedGroups() {
		if (tableCount("groups") > 0)
			return;
		String[][] groups = { { "group-all", "All Employees" }, { "group-builders", "Builders" },
				{ "group-admins", "Admins" }, };
		for (String[] g : groups) {
			db.update("INSERT INTO groups (id, name) VALUES (?, ?)", g[0], g[1]);
		}
	}

	/**
	 * Seeds the {@code permission_catalog} table with the full set of platform
	 * permissions.
	 *
	 * <p>
	 * Each permission has a unique key, a display category, a human-readable
	 * description, and a risk level ({@code low}, {@code medium}, {@code high}, or
	 * {@code critical}). Skipped when the table already has rows.
	 *
	 * @since v2026.1.0
	 */
	private void seedPermissions() {
		if (tableCount("permission_catalog") > 0)
			return;
		Object[][] perms = { { "chat:use", "AI Usage", "Use the AI workspace", "low" },
				{ "models:local:use", "Model Usage", "Use approved Ollama/local models", "low" },
				{ "models:external:use", "Model Usage", "Use external premium AI providers", "high" },
				{ "models:coding:use", "Model Usage", "Use coding models and coding workflows", "medium" },
				{ "models:reasoning:use", "Model Usage", "Use reasoning models", "medium" },
				{ "ollama:models:pull", "Ollama Governance", "Pull models into Ollama", "high" },
				{ "ollama:models:import", "Ollama Governance", "Import custom/GGUF models", "high" },
				{ "ollama:modelfile:create", "Ollama Governance", "Create models with Modelfiles", "high" },
				{ "workspace:build", "Local Work", "Create local workspace files and access terminal shell",
						"critical" },
			{ "sandbox:run", "Local Work", "Execute code snippets in the code sandbox (OS-level, requires trust)",
						"critical" },
				{ "files:upload", "AI Workflow", "Upload files to AI workflows", "medium" },
				{ "tools:call", "AI Workflow", "Use tool calling", "high" },
				{ "internet:use", "AI Workflow", "Use internet-enabled agents", "high" },
				{ "agents:run", "AI Workflow", "Run AI agents", "high" },
				{ "api:use", "Developer Access", "Use Olla Nest APIs", "medium" },
				{ "audit:read", "Governance", "Read audit logs", "medium" },
				{ "users:manage", "Administration", "Manage users", "high" },
				{ "models:manage", "Administration", "Manage model governance", "high" },
				{ "admin:manage", "Administration", "Manage platform settings", "critical" }, };
		for (Object[] p : perms) {
			db.update("INSERT INTO permission_catalog (key, category, description, risk_level) VALUES (?, ?, ?, ?)",
					p[0], p[1], p[2], p[3]);
		}
	}

	/**
	 * Seeds the {@code role_catalog} table with the full set of platform roles.
	 *
	 * <p>
	 * Each role stores its granted permission keys as a JSON array string. System
	 * roles (flagged {@code 1}) cannot be deleted by administrators via the UI.
	 * Skipped when the table already has rows.
	 *
	 * @since v2026.1.0
	 */
	private void seedRoles() {
		if (tableCount("role_catalog") > 0)
			return;
		Object[][] roles = { { "platform-owner", "Platform Owner", "Full control over the AI platform",
				"[\"admin:manage\",\"users:manage\",\"models:manage\",\"audit:read\",\"chat:use\",\"models:local:use\",\"models:external:use\",\"ollama:models:pull\",\"ollama:models:import\",\"ollama:modelfile:create\",\"api:use\",\"agents:run\"]",
				1 },
				{ "ai-infra-admin", "AI Infrastructure Admin", "Manage Ollama infrastructure and model sources",
						"[\"models:manage\",\"models:local:use\",\"ollama:models:pull\",\"ollama:models:import\",\"ollama:modelfile:create\",\"audit:read\"]",
						1 },
				{ "security-admin", "Security Admin", "Manage governance, audit, and risk",
						"[\"users:manage\",\"audit:read\",\"admin:manage\"]", 1 },
				{ "department-admin", "Department Admin", "Manage department users and access requests",
						"[\"users:manage\",\"audit:read\",\"chat:use\"]", 1 },
				{ "ai-developer", "AI Developer", "Build with coding models, tools, and local files",
						"[\"chat:use\",\"models:local:use\",\"models:coding:use\",\"workspace:build\",\"files:upload\",\"tools:call\",\"api:use\"]",
						1 },
				{ "ai-analyst", "AI Analyst", "Use analysis and reasoning workflows",
						"[\"chat:use\",\"models:local:use\",\"models:reasoning:use\",\"files:upload\"]", 1 },
				{ "engineering-user", "Engineering User", "Use coding and local AI models",
						"[\"chat:use\",\"models:local:use\",\"models:coding:use\",\"workspace:build\"]", 1 },
				{ "research-user", "Research User", "Use reasoning models and knowledge workflows",
						"[\"chat:use\",\"models:local:use\",\"models:reasoning:use\",\"files:upload\"]", 1 },
				{ "viewer", "Viewer", "Read-only AI workspace visibility", "[\"chat:use\"]", 1 }, };
		for (Object[] r : roles) {
			db.update(
					"INSERT INTO role_catalog (id, name, description, permissions, system_role) VALUES (?, ?, ?, ?, ?)",
					r[0], r[1], r[2], r[3], r[4]);
		}
	}

	/**
	 * Sentinel value that indicates the admin password has not been changed from
	 * its default.
	 *
	 * <p>
	 * When {@link AppConfig#getDefaultAdminPassword()} returns this value, a
	 * cryptographically random 16-character password is generated instead and
	 * printed to the log at WARN level.
	 */
	/**
	 * Returns true when running in a development environment (no 'prod' or
	 * 'production' Spring profile active, and no explicit PRODUCTION env var set).
	 * Used to decide whether to hard-fail on insecure defaults or merely warn.
	 */
	private boolean isDevMode() {
		// springEnv may be null in unit tests that construct DatabaseService directly
		if (springEnv != null) {
			String[] activeProfiles = springEnv.getActiveProfiles();
			for (String p : activeProfiles) {
				if ("prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p)) return false;
			}
		}
		// Also check explicit PRODUCTION env var (e.g. set by Docker/K8s)
		String prodEnv = System.getenv("PRODUCTION");
		if ("true".equalsIgnoreCase(prodEnv) || "1".equals(prodEnv)) return false;
		return true;
	}

	private static final String FIRST_BOOT_SENTINEL = "CHANGE_ME_ON_FIRST_BOOT";

	/**
	 * Generates a cryptographically random 16-character password from a
	 * mixed-character alphabet.
	 *
	 * <p>
	 * Uses {@link SecureRandom} with an alphabet of upper-case letters, lower-case
	 * letters, digits, and common symbols.
	 *
	 * @return a 16-character random password string; never {@code null}
	 * @since v2026.1.0
	 */
	private String generateSecurePassword() {
		String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
		SecureRandom rng = new SecureRandom();
		StringBuilder sb = new StringBuilder(16);
		for (int i = 0; i < 16; i++)
			sb.append(chars.charAt(rng.nextInt(chars.length())));
		return sb.toString();
	}

	/**
	 * Seeds the {@code users} table with default admin and sample employee
	 * accounts.
	 *
	 * <p>
	 * If the configured admin password equals {@link #FIRST_BOOT_SENTINEL} a random
	 * password is generated and logged once at WARN level — administrators should
	 * copy it from the log on first boot and then update it via the UI. All
	 * passwords are BCrypt-hashed with cost factor 12 before storage. Group
	 * memberships are inserted with {@code INSERT OR IGNORE} to prevent
	 * duplicate-key errors. Skipped when the table already has rows.
	 *
	 * @since v2026.1.0
	 */
	private void seedUsers() {
		if (tableCount("users") > 0)
			return;
		String adminPassword = appConfig.getDefaultAdminPassword();
		if (FIRST_BOOT_SENTINEL.equals(adminPassword)) {
			adminPassword = generateSecurePassword();
			log.warn("=== OLLA·NEST FIRST BOOT: Admin password: {} ===", adminPassword);
		}
		String adminHash = BCrypt.hashpw(adminPassword, BCrypt.gensalt(12));
		String userHash = BCrypt.hashpw(appConfig.getDefaultUserPassword(), BCrypt.gensalt(12));
		String adminEmail = appConfig.getDefaultAdminEmail();

		Object[][] users = {
				{ "u-admin", "Admin", adminEmail, adminHash, "admin",
						"[\"admin:manage\",\"chat:use\",\"models:manage\",\"users:manage\"]", "dept-product" },
				{ "u-user", "Employee", "employee@ollanest.local", userHash, "user", "[\"chat:use\"]", "dept-general" },
				{ "u-builder", "Builder Employee", "builder@ollanest.local", userHash, "user",
						"[\"chat:use\",\"workspace:build\"]", "dept-product" },
				{ "u-support", "Support Employee", "support@ollanest.local", userHash, "user",
						"[\"chat:use\",\"workspace:review\"]", "dept-support" }, };
		for (Object[] u : users) {
			db.update(
					"INSERT INTO users (id, name, email, password_hash, role, rights, department_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
					u[0], u[1], u[2], u[3], u[4], u[5], u[6]);
		}

		// Group memberships
		String[][] memberships = { { "u-admin", "group-admins" }, { "u-admin", "group-all" }, { "u-user", "group-all" },
				{ "u-builder", "group-all" }, { "u-builder", "group-builders" }, { "u-support", "group-all" }, };
		for (String[] m : memberships) {
			db.update("INSERT OR IGNORE INTO user_groups (user_id, group_id) VALUES (?, ?)", m[0], m[1]);
		}
	}
}
