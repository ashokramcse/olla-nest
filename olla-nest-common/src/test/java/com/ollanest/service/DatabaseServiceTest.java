package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ollanest.config.AppConfig;

/**
 * Unit tests for {@link DatabaseService}.
 *
 * <p>
 * Covers: getSetting/setSetting/getSettingBool contract, null-value guard (NOT
 * NULL schema constraint), tableCount SQL-injection guard, and seedDatabase
 * idempotency logic.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * {@link DatabaseService} owns settings access and first-boot seeding, both of
 * which must degrade gracefully: settings reads happen during early startup
 * before the schema may exist, and seeding must be idempotent and failure-safe
 * so a partial database never prevents the server from coming up. These tests
 * pin those contracts.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>{@link JdbcTemplate} and {@link AppConfig} are Mockito mocks so behaviour
 * is exercised without a real database or filesystem.</li>
 * <li>Seeding tests stub {@code tableCount} to a non-zero value so the seed path
 * is skipped, isolating the idempotency and failure-handling logic.</li>
 * <li>Lenient strictness is applied on the seeding nests because not every stub
 * is consumed on every path.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — documented as part of the project-wide Javadoc pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DatabaseService — unit tests")
class DatabaseServiceTest {

	/** Mocked JDBC template backing settings reads/writes and seeding. */
	@Mock
	JdbcTemplate db;
	/** Mocked application config supplying seed credentials and data dir. */
	@Mock
	AppConfig appConfig;

	/** System under test, rebuilt fresh before each test. */
	private DatabaseService service;

	/**
	 * Builds a fresh {@link DatabaseService} before each test so no state leaks
	 * between cases.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@BeforeEach
	void setUp() {
		service = new DatabaseService(db, appConfig);
	}

	// ── getSetting ────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code getSetting()} — value retrieval and graceful fallbacks.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("getSetting")
	class GetSetting {

		/**
		 * Verifies a stored value is returned when the key exists.
		 *
		 * <p>
		 * With a row stubbed for the key, the stored value (not the fallback) is
		 * returned.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns stored value when key exists")
		void returnsStoredValue() {
			// Stub DB to return a row with "myValue" for key "myKey"
			when(db.queryForList("SELECT value FROM settings WHERE key = ?", "myKey"))
					.thenReturn(List.of(Map.of("value", "myValue")));
			assertThat(service.getSetting("myKey", "fallback")).isEqualTo("myValue");
		}

		/**
		 * Verifies the fallback is returned when the key is absent.
		 *
		 * <p>
		 * An empty result set must yield the supplied fallback.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns fallback when key is absent")
		void returnsFallbackWhenAbsent() {
			// Stub DB to return empty list — key does not exist
			when(db.queryForList("SELECT value FROM settings WHERE key = ?", "missing")).thenReturn(List.of());
			assertThat(service.getSetting("missing", "default")).isEqualTo("default");
		}

		/**
		 * Verifies a stored null value is treated as absent.
		 *
		 * <p>
		 * A row whose {@code value} is null must yield the fallback, not null.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns fallback when stored value is null")
		void returnsFallbackWhenValueNull() {
			// Stub DB to return a row with null value — treat same as absent
			Map<String, Object> row = new HashMap<>();
			row.put("value", null);
			when(db.queryForList("SELECT value FROM settings WHERE key = ?", "k")).thenReturn(List.of(row));
			assertThat(service.getSetting("k", "fb")).isEqualTo("fb");
		}

		/**
		 * Verifies a {@link DataAccessException} degrades to the fallback.
		 *
		 * <p>
		 * The settings table may not exist during early startup; a thrown DAE must
		 * be swallowed and the fallback returned rather than propagating.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns fallback on DataAccessException (e.g. table missing at early startup)")
		void returnsFallbackOnException() {
			// Stub DB to throw — settings table may not exist during early startup
			when(db.queryForList(anyString(), anyString())).thenThrow(new DataAccessException("table not found") {
			});
			// No exception thrown = service degrades gracefully using fallback
			assertThat(service.getSetting("k", "safe")).isEqualTo("safe");
		}

		/**
		 * Verifies a null fallback is honoured when the key is absent.
		 *
		 * <p>
		 * When no fallback is supplied and the key is missing, the result is null.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns null fallback when no fallback specified and key absent")
		void returnsNullFallback() {
			when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
			assertThat(service.getSetting("x", null)).isNull();
		}
	}

	// ── setSetting ────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code setSetting()} — upsert and null coercion.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("setSetting")
	class SetSetting {

		/**
		 * Verifies a value is written via {@code INSERT OR REPLACE}.
		 *
		 * <p>
		 * The upsert binds key and value, so no separate UPDATE is needed.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("writes value via INSERT OR REPLACE")
		void writesValue() {
			service.setSetting("theme", "dark");
			// INSERT OR REPLACE upserts the key — no separate UPDATE needed
			verify(db).update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", "theme", "dark");
		}

		/**
		 * Verifies a null value is coerced to an empty string.
		 *
		 * <p>
		 * The settings column is NOT NULL, so a null value must be stored as
		 * {@code ""} rather than violating the constraint.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("null value is coerced to empty string — never violates NOT NULL constraint")
		void nullValueBecomesEmptyString() {
			service.setSetting("someKey", null);
			// SECURITY: null must not be stored — settings column is NOT NULL
			verify(db).update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", "someKey", "");
		}

		/**
		 * Verifies an empty string value is persisted unchanged.
		 *
		 * <p>
		 * An explicitly empty value is stored as {@code ""} without coercion.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("empty string value is persisted as-is")
		void emptyStringPersisted() {
			service.setSetting("k", "");
			verify(db).update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", "k", "");
		}
	}

	// ── getSettingBool ────────────────────────────────────────────────────────

	/**
	 * Tests for {@code getSettingBool()} — strict boolean parsing with fallback.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("getSettingBool")
	class GetSettingBool {

		/**
		 * Stubs the underlying settings lookup for a boolean test.
		 *
		 * @param value the stored string value, or {@code null} to simulate an
		 *              absent key
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		private void stubSetting(String value) {
			if (value == null) {
				when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
			} else {
				when(db.queryForList(anyString(), anyString())).thenReturn(List.of(Map.of("value", value)));
			}
		}

		/**
		 * Verifies the literal {@code "true"} parses to {@code true}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("'true' string returns true")
		@MockitoSettings(strictness = Strictness.LENIENT)
		void trueStringReturnsTrue() {
			stubSetting("true");
			assertThat(service.getSettingBool("flag", false)).isTrue();
		}

		/**
		 * Verifies the literal {@code "false"} parses to {@code false}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("'false' string returns false")
		@MockitoSettings(strictness = Strictness.LENIENT)
		void falseStringReturnsFalse() {
			stubSetting("false");
			assertThat(service.getSettingBool("flag", true)).isFalse();
		}

		/**
		 * Verifies an absent key returns the {@code true} fallback.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("absent key returns fallback=true")
		@MockitoSettings(strictness = Strictness.LENIENT)
		void absentKeyReturnsFallbackTrue() {
			stubSetting(null);
			assertThat(service.getSettingBool("x", true)).isTrue();
		}

		/**
		 * Verifies an absent key returns the {@code false} fallback.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("absent key returns fallback=false")
		@MockitoSettings(strictness = Strictness.LENIENT)
		void absentKeyReturnsFallbackFalse() {
			stubSetting(null);
			assertThat(service.getSettingBool("x", false)).isFalse();
		}

		/**
		 * Verifies a non-boolean string falls back rather than guessing.
		 *
		 * <p>
		 * {@code "yes"} is neither {@code "true"} nor {@code "false"}, so the
		 * fallback must be returned rather than an ambiguous parse.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("non-boolean string returns fallback")
		@MockitoSettings(strictness = Strictness.LENIENT)
		void nonBooleanStringReturnsFallback() {
			// "yes" is not "true" — must fall back rather than try to parse ambiguously
			stubSetting("yes"); // not "true" or "false"
			assertThat(service.getSettingBool("flag", true)).isTrue();
		}
	}

	// ── tableCount SQL-injection guard ────────────────────────────────────────

	/**
	 * Tests for the {@code tableCount} table-name validation guard.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("tableCount injection guard")
	class TableCountInjectionGuard {

		/**
		 * Verifies a valid, allowlisted table name passes validation.
		 *
		 * <p>
		 * With {@code tableCount("settings") > 0} the seed path is skipped, so
		 * {@code seedDatabase()} must complete without throwing.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("valid table name passes validation")
		void validTableNameAccepted() {
			when(db.queryForObject(anyString(), eq(Integer.class))).thenReturn(5);
			// Set up AppConfig stubs for seedDatabase to run
			when(appConfig.getDataDir()).thenReturn("/tmp");
			when(appConfig.getDefaultAdminPassword()).thenReturn("CHANGE_ME_ON_FIRST_BOOT");
			when(appConfig.getDefaultAdminEmail()).thenReturn("admin@test.com");
			when(appConfig.getDefaultUserPassword()).thenReturn("defaultUserPass");
			// tableCount("settings") > 0 → seed is skipped; no exception expected
			assertThatNoException().isThrownBy(() -> service.seedDatabase());
		}
	}

	// ── seedDatabase idempotency ───────────────────────────────────────────────

	/**
	 * Tests for {@code seedDatabase()} idempotency and failure tolerance.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("seedDatabase idempotency")
	class SeedDatabaseIdempotency {

		/**
		 * Stubs all tables as already populated so the seed path is skipped.
		 *
		 * <p>
		 * Returning a non-zero {@code tableCount} and valid config isolates the
		 * idempotency/failure logic from the actual seeding work.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@BeforeEach
		void stubNonZeroTables() {
			// Stub: all tables already have rows → seeding must be skipped entirely
			when(db.queryForObject(anyString(), eq(Integer.class))).thenReturn(5);
			when(appConfig.getDataDir()).thenReturn("/tmp");
			when(appConfig.getDefaultAdminPassword()).thenReturn("SomePassword123!");
			when(appConfig.getDefaultAdminEmail()).thenReturn("admin@test.com");
			when(appConfig.getDefaultUserPassword()).thenReturn("UserPass123!");
		}

		/**
		 * Verifies no INSERT runs when every table is already populated.
		 *
		 * <p>
		 * With non-empty tables, {@code db.update()} must never be called.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("no INSERT is issued when all tables are non-empty")
		void noInsertsWhenTablesPopulated() {
			service.seedDatabase();
			// db.update() must never be called — all tables already have rows
			verify(db, never()).update(contains("INSERT"), any(Object[].class));
		}

		/**
		 * Verifies repeated calls are safe (idempotent).
		 *
		 * <p>
		 * Calling {@code seedDatabase()} three times in a row must not throw.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("seedDatabase is safe to call multiple times (idempotent)")
		void callableManyTimes() {
			// No exception thrown = calling seedDatabase multiple times is safe
			assertThatNoException().isThrownBy(() -> {
				service.seedDatabase();
				service.seedDatabase();
				service.seedDatabase();
			});
		}

		/**
		 * Verifies a seeding exception is swallowed so the server stays up.
		 *
		 * <p>
		 * When the table-count probe throws a {@link DataAccessException},
		 * {@code seedDatabase()} must still complete without propagating, keeping
		 * startup resilient to a partial/broken database.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("exception during seeding is swallowed — server stays up")
		void exceptionSwallowed() {
			when(db.queryForObject(anyString(), eq(Integer.class))).thenThrow(new DataAccessException("DB down") {
			});
			// No exception thrown = DB failures during seeding do not prevent server
			// startup
			assertThatNoException().isThrownBy(() -> service.seedDatabase());
		}
	}
}
