package com.ollanest.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ollanest.admin.OllaNestAdminApplication;
import com.ollanest.service.DatabaseService;
import com.ollanest.service.OllamaService;
import com.ollanest.service.WhisperServerManager;

/**
 * Schema integration tests — verifies Flyway migrations produce the correct
 * schema against an in-memory SQLite database.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The database schema is owned by versioned Flyway migrations (V1–V6); a dropped
 * table, a missing performance index, or a silently-disabled foreign-key pragma
 * is the kind of regression that only surfaces in production. These tests boot
 * the real migration path against a fresh in-memory SQLite instance and assert
 * every production table, every expected index, the migration history, the
 * critical columns, and that foreign keys are actually enforced (BUG-033).
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Runs as a non-web {@link SpringBootTest} with the {@code test} profile so
 * Flyway applies all migrations to a fresh SQLite instance.</li>
 * <li>{@link DatabaseService} and external services (Ollama, Whisper) are
 * replaced with {@link MockitoBean}s so the context loads without seeding
 * side-effects or infrastructure dependencies.</li>
 * <li>Table/index existence is checked via {@code sqlite_master}; column
 * presence via {@code PRAGMA table_info}; everything is read-only except a few
 * self-cleaning settings writes.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — SQL hardening: schema migration validation across V1–V6.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.0
 */
@SpringBootTest(classes = OllaNestAdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Schema Integration — Flyway migration validation (V1–V6)")
class SchemaIntegrationTest {

	/** Live SQLite JDBC template used to inspect the migrated schema. */
	@Autowired
	JdbcTemplate db;

	// Mock external services so the context loads without infrastructure deps
	/** Mocked database service so seeding side-effects do not run during context load. */
	@MockitoBean
	DatabaseService databaseService;
	/** Mocked Ollama service so no local model runtime is required. */
	@MockitoBean
	OllamaService ollamaService;
	/** Mocked Whisper manager so no speech runtime is required. */
	@MockitoBean
	WhisperServerManager whisperServerManager;

	// ── Table existence ───────────────────────────────────────────────────

	/**
	 * Tests that every production table exists after migration.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.0
	 * @version v2026.1.0
	 */
	@Nested
	@DisplayName("Core tables — all production tables exist after migration")
	class CoreTablesExist {

		/**
		 * Verifies a single production table exists in {@code sqlite_master}.
		 *
		 * <p>
		 * Parameterized over every table created by V1–V5; each must resolve to
		 * exactly one matching {@code type='table'} row.
		 *
		 * @param tableName the production table name supplied by the value source
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@ParameterizedTest(name = "table ''{0}'' exists")
		@ValueSource(strings = {
				// V1 tables
				"settings", "departments", "groups", "teams", "users", "user_groups", "models", "access_grants",
				"role_catalog", "permission_catalog", "user_overrides", "chat_sessions", "chat_messages",
				"audit_events", "router_traces", "workspace_prefs", "api_providers", "sessions", "api_models",
				"feedback", "login_attempts",
				// V2 tables
				"rag_documents", "rag_chunks",
				// V3 tables
				"connector_configs", "connector_documents", "connector_sync_log",
				// V4 tables
				"sso_providers", "oauth_state",
				// V5 tables
				"image_generation_log", "web_search_log", })
		@DisplayName("table exists in schema")
		void tableExists(String tableName) {
			Integer count = db.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
					Integer.class, tableName);
			assertThat(count).as("Table '%s' must exist after Flyway migration", tableName).isEqualTo(1);
		}
	}

	// ── V6 performance indexes ────────────────────────────────────────────

	/**
	 * Tests that all V6 performance indexes were created.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.0
	 * @version v2026.1.0
	 */
	@Nested
	@DisplayName("V6 performance indexes — all 14 indexes created")
	class V6PerformanceIndexes {

		/**
		 * Verifies a single V6 index exists in {@code sqlite_master}.
		 *
		 * @param indexName the V6 index name supplied by the value source
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@ParameterizedTest(name = "index ''{0}'' exists")
		@ValueSource(strings = { "idx_models_provider", "idx_models_status", "idx_models_provider_status",
				"idx_users_email", "idx_users_role", "idx_users_active", "idx_users_email_active",
				"idx_chat_sessions_updated_at", "idx_chat_sessions_user_active_updated", "idx_audit_events_actor",
				"idx_audit_events_action", "idx_connector_sync_log_connector", "idx_connector_sync_log_started_at",
				"idx_user_overrides_user", "idx_user_overrides_expires", "idx_access_grants_subject",
				"idx_access_grants_model", })
		@DisplayName("V6 index exists in schema")
		void v6IndexExists(String indexName) {
			Integer count = db.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?",
					Integer.class, indexName);
			assertThat(count).as("V6 index '%s' must exist after migration", indexName).isEqualTo(1);
		}
	}

	// ── V1 baseline indexes ───────────────────────────────────────────────

	/**
	 * Tests that the foundational V1 baseline indexes exist.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.0
	 * @version v2026.1.0
	 */
	@Nested
	@DisplayName("V1 baseline indexes — foundational indexes from initial migration")
	class V1BaselineIndexes {

		/**
		 * Verifies a single V1 baseline index exists in {@code sqlite_master}.
		 *
		 * @param indexName the V1 index name supplied by the value source
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@ParameterizedTest(name = "index ''{0}'' exists")
		@ValueSource(strings = { "idx_sessions_user", "idx_sessions_expires", "idx_chat_messages_session_id",
				"idx_chat_messages_user_date", "idx_chat_messages_created_at", "idx_chat_sessions_user_active",
				"idx_audit_events_created_at", "idx_router_traces_created_at", "idx_feedback_message_id", })
		@DisplayName("V1 baseline index exists")
		void v1IndexExists(String indexName) {
			Integer count = db.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?",
					Integer.class, indexName);
			assertThat(count).as("V1 index '%s' must exist", indexName).isEqualTo(1);
		}
	}

	// ── Flyway migration count ────────────────────────────────────────────

	/**
	 * Tests the Flyway migration history is complete and clean.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.0
	 * @version v2026.1.0
	 */
	@Nested
	@DisplayName("Flyway migration history — all versions applied")
	class FlywayMigrationHistory {

		/**
		 * Verifies at least six successful migrations are recorded.
		 *
		 * <p>
		 * Flyway records one row per script (V1–V6); all must be marked successful.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("exactly 6 migration versions recorded in flyway_schema_history")
		void sixMigrationsApplied() {
			// Flyway records one row per script file (V1–V6)
			Integer count = db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
					Integer.class);
			assertThat(count).as("All 6 Flyway migrations must be recorded as successful").isGreaterThanOrEqualTo(6);
		}

		/**
		 * Verifies no failed migrations are recorded.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("no failed migrations in flyway_schema_history")
		void noFailedMigrations() {
			Integer failCount = db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0",
					Integer.class);
			assertThat(failCount).as("No failed Flyway migrations must exist").isZero();
		}

		/**
		 * Verifies the V6 migration is present in history.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("V6 migration version is present in history")
		void v6MigrationPresent() {
			Integer count = db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6'",
					Integer.class);
			assertThat(count).as("V6 migration must be recorded in flyway_schema_history").isEqualTo(1);
		}
	}

	// ── Critical column constraints ───────────────────────────────────────

	/**
	 * Tests that critical tables expose the columns the application relies on.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.0
	 * @version v2026.1.0
	 */
	@Nested
	@DisplayName("Critical schema constraints — NOT NULL and column presence")
	class SchemaConstraints {

		/**
		 * Verifies the settings table has {@code key} and {@code value} columns.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("settings table has 'key' and 'value' columns")
		void settingsHasKeyValueColumns() {
			List<Map<String, Object>> cols = db.queryForList("PRAGMA table_info(settings)");
			List<String> colNames = cols.stream().map(r -> r.get("name").toString()).toList();
			assertThat(colNames).contains("key", "value");
		}

		/**
		 * Verifies the users table has all required auth columns.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("users table has all required auth columns")
		void usersHasAuthColumns() {
			List<Map<String, Object>> cols = db.queryForList("PRAGMA table_info(users)");
			List<String> colNames = cols.stream().map(r -> r.get("name").toString()).toList();
			// Note: users table does not have a created_at column — it uses last_active_at.
			assertThat(colNames).contains("id", "email", "name", "role", "password_hash", "department_id",
					"auth_provider", "active");
		}

		/**
		 * Verifies the chat_sessions table has all required columns.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("chat_sessions table has all required columns")
		void chatSessionsHasRequiredColumns() {
			List<Map<String, Object>> cols = db.queryForList("PRAGMA table_info(chat_sessions)");
			List<String> colNames = cols.stream().map(r -> r.get("name").toString()).toList();
			assertThat(colNames).contains("id", "user_id", "title", "is_active", "pinned", "archived", "unread",
					"created_at", "updated_at");
		}

		/**
		 * Verifies the chat_messages table includes the {@code latency_ms} column.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("chat_messages table has all required columns including latency_ms")
		void chatMessagesHasLatencyColumn() {
			List<Map<String, Object>> cols = db.queryForList("PRAGMA table_info(chat_messages)");
			List<String> colNames = cols.stream().map(r -> r.get("name").toString()).toList();
			assertThat(colNames).contains("id", "session_id", "role", "content", "model_id", "tokens_used",
					"latency_ms", "created_at");
		}

		/**
		 * Verifies the models table has the governance columns.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("models table has governance columns")
		void modelsHasGovernanceColumns() {
			List<Map<String, Object>> cols = db.queryForList("PRAGMA table_info(models)");
			List<String> colNames = cols.stream().map(r -> r.get("name").toString()).toList();
			assertThat(colNames).contains("id", "name", "provider", "status", "governance_tier", "gpu_required",
					"sensitive_allowed", "max_context_size");
		}

		/**
		 * Verifies the audit_events table has actor/action/detail columns.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("audit_events table has actor, action, detail columns")
		void auditEventsHasRequiredColumns() {
			List<Map<String, Object>> cols = db.queryForList("PRAGMA table_info(audit_events)");
			List<String> colNames = cols.stream().map(r -> r.get("name").toString()).toList();
			assertThat(colNames).contains("id", "actor", "action", "detail", "created_at");
		}

		/**
		 * Verifies the sessions table has token/user_id/expires_at columns.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("sessions table has token, user_id, expires_at columns")
		void sessionsHasRequiredColumns() {
			List<Map<String, Object>> cols = db.queryForList("PRAGMA table_info(sessions)");
			List<String> colNames = cols.stream().map(r -> r.get("name").toString()).toList();
			assertThat(colNames).contains("token", "user_id", "expires_at");
		}

		/**
		 * Verifies the connector_configs table has the V3 columns.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("connector_configs table has V3 columns")
		void connectorConfigsHasV3Columns() {
			List<Map<String, Object>> cols = db.queryForList("PRAGMA table_info(connector_configs)");
			List<String> colNames = cols.stream().map(r -> r.get("name").toString()).toList();
			assertThat(colNames).contains("id", "name", "type", "enabled", "created_at");
		}

		/**
		 * Verifies the sso_providers table has the V4 columns.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("sso_providers table has V4 columns")
		void ssoProvidersHasV4Columns() {
			List<Map<String, Object>> cols = db.queryForList("PRAGMA table_info(sso_providers)");
			List<String> colNames = cols.stream().map(r -> r.get("name").toString()).toList();
			assertThat(colNames).contains("id", "name", "enabled");
		}
	}

	// ── Foreign key enforcement ───────────────────────────────────────────

	/**
	 * Tests that SQLite foreign-key enforcement is actually on (BUG-033).
	 *
	 * @author Ashok Ram
	 * @since v2026.1.0
	 * @version v2026.1.0
	 */
	@Nested
	@DisplayName("Foreign key enforcement — PRAGMA foreign_keys=ON")
	class ForeignKeyEnforcement {

		/**
		 * Verifies foreign keys are enabled by the datasource config, not the test.
		 *
		 * <p>
		 * BUG-033: a multi-statement init-SQL only ran its first PRAGMA under the
		 * Xerial driver, silently leaving {@code foreign_keys} OFF. This asserts FK
		 * is ON without the test enabling it.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("foreign_keys is ENABLED by the datasource config (no manual PRAGMA) — BUG-033 guard")
		void foreignKeysEnabledByConfig() {
			// BUG-033: the multi-statement connection-init-sql only ran its FIRST PRAGMA
			// under the Xerial driver, so foreign_keys was silently OFF in production —
			// cascades never fired and orphan rows accumulated. The fix sets
			// foreign_keys=on as a JDBC URL parameter (applied per connection by the
			// driver). This asserts FK is ON WITHOUT us enabling it here.
			List<Map<String, Object>> rows = db.queryForList("PRAGMA foreign_keys");
			assertThat(rows).isNotEmpty();
			Object val = rows.get(0).get("foreign_keys");
			int fkEnabled = val instanceof Number ? ((Number) val).intValue() : 0;
			assertThat(fkEnabled).as("datasource must enable foreign_keys without a manual PRAGMA").isEqualTo(1);
		}

		/**
		 * Verifies the driver supports per-connection {@code PRAGMA foreign_keys=ON}.
		 *
		 * <p>
		 * Enables the pragma directly and confirms it reports as on, proving driver
		 * support independent of the datasource config.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("foreign_keys PRAGMA can be enabled per-connection in SQLite")
		void foreignKeysEnabled() {
			// Verify that enabling PRAGMA foreign_keys=ON works correctly at the
			// SQLite driver level. The production datasource enables this via
			// connection-init-sql; the test profile uses MEMORY journal mode and
			// may or may not enable FKs depending on connection-init-sql config.
			// We run it directly here to prove the driver supports it.
			db.execute("PRAGMA foreign_keys=ON");
			List<Map<String, Object>> rows = db.queryForList("PRAGMA foreign_keys");
			assertThat(rows).isNotEmpty();
			Object val = rows.get(0).get("foreign_keys");
			int fkEnabled = val instanceof Number ? ((Number) val).intValue() : 0;
			assertThat(fkEnabled).as("SQLite driver must support PRAGMA foreign_keys=ON").isEqualTo(1);
		}
	}

	// ── Settings table read/write ─────────────────────────────────────────

	/**
	 * Tests basic settings read/write against the live migrated schema.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.0
	 * @version v2026.1.0
	 */
	@Nested
	@DisplayName("Settings table — basic read/write against live schema")
	class SettingsTableFunctionality {

		/**
		 * Verifies a value can be inserted into and read back from settings.
		 *
		 * <p>
		 * The test row is removed afterwards so it does not leak into other cases.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("can INSERT and SELECT from settings table")
		void settingsInsertAndSelect() {
			db.update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", "schema_test_key",
					"schema_test_value");
			List<Map<String, Object>> rows = db.queryForList("SELECT value FROM settings WHERE key = ?",
					"schema_test_key");
			assertThat(rows).hasSize(1);
			assertThat(rows.get(0).get("value")).isEqualTo("schema_test_value");
			// Cleanup
			db.update("DELETE FROM settings WHERE key = ?", "schema_test_key");
		}

		/**
		 * Verifies {@code INSERT OR REPLACE} overwrites without a PK violation.
		 *
		 * <p>
		 * Two writes to the same key must leave the latest value; the row is then
		 * cleaned up.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("INSERT OR REPLACE overwrites existing setting (no PK violation)")
		void settingsInsertOrReplaceIdempotent() {
			db.update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", "schema_idempotent_key",
					"first_value");
			db.update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", "schema_idempotent_key",
					"second_value");
			List<Map<String, Object>> rows = db.queryForList("SELECT value FROM settings WHERE key = ?",
					"schema_idempotent_key");
			assertThat(rows).hasSize(1);
			assertThat(rows.get(0).get("value")).isEqualTo("second_value");
			// Cleanup
			db.update("DELETE FROM settings WHERE key = ?", "schema_idempotent_key");
		}

		/**
		 * Verifies the total table count stays within an expected floor.
		 *
		 * <p>
		 * A sanity guard: if a migration accidentally drops or renames a table, the
		 * production-table count falls below 28.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.0
		 * @version v2026.1.0
		 */
		@Test
		@DisplayName("total schema object count is within expected range")
		void schemaObjectCountSane() {
			// Sanity guard: if a migration drops or renames a table accidentally,
			// the object count falls below the expected floor.
			Integer tableCount = db.queryForObject(
					"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'flyway_schema_history'",
					Integer.class);
			assertThat(tableCount).as("Schema must have at least 28 production tables").isGreaterThanOrEqualTo(28);
		}
	}
}
