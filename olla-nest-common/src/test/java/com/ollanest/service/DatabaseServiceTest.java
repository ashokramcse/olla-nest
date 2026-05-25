package com.ollanest.service;

import com.ollanest.config.AppConfig;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DatabaseService}.
 *
 * <p>Covers: getSetting/setSetting/getSettingBool contract, null-value guard
 * (NOT NULL schema constraint), tableCount SQL-injection guard, and
 * seedDatabase idempotency logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DatabaseService — unit tests")
class DatabaseServiceTest {

	@Mock JdbcTemplate db;
	@Mock AppConfig    appConfig;

	private DatabaseService service;

	@BeforeEach
	void setUp() {
		service = new DatabaseService(db, appConfig);
	}

	// ── getSetting ────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("getSetting")
	class GetSetting {

		@Test
		@DisplayName("returns stored value when key exists")
		void returnsStoredValue() {
			when(db.queryForList("SELECT value FROM settings WHERE key = ?", "myKey"))
					.thenReturn(List.of(Map.of("value", "myValue")));
			assertThat(service.getSetting("myKey", "fallback")).isEqualTo("myValue");
		}

		@Test
		@DisplayName("returns fallback when key is absent")
		void returnsFallbackWhenAbsent() {
			when(db.queryForList("SELECT value FROM settings WHERE key = ?", "missing"))
					.thenReturn(List.of());
			assertThat(service.getSetting("missing", "default")).isEqualTo("default");
		}

		@Test
		@DisplayName("returns fallback when stored value is null")
		void returnsFallbackWhenValueNull() {
			Map<String, Object> row = new java.util.HashMap<>();
			row.put("value", null);
			when(db.queryForList("SELECT value FROM settings WHERE key = ?", "k"))
					.thenReturn(List.of(row));
			assertThat(service.getSetting("k", "fb")).isEqualTo("fb");
		}

		@Test
		@DisplayName("returns fallback on DataAccessException (e.g. table missing at early startup)")
		void returnsFallbackOnException() {
			when(db.queryForList(anyString(), anyString()))
					.thenThrow(new DataAccessException("table not found") {});
			assertThat(service.getSetting("k", "safe")).isEqualTo("safe");
		}

		@Test
		@DisplayName("returns null fallback when no fallback specified and key absent")
		void returnsNullFallback() {
			when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
			assertThat(service.getSetting("x", null)).isNull();
		}
	}

	// ── setSetting ────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("setSetting")
	class SetSetting {

		@Test
		@DisplayName("writes value via INSERT OR REPLACE")
		void writesValue() {
			service.setSetting("theme", "dark");
			verify(db).update(
					"INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
					"theme", "dark");
		}

		@Test
		@DisplayName("null value is coerced to empty string — never violates NOT NULL constraint")
		void nullValueBecomesEmptyString() {
			service.setSetting("someKey", null);
			verify(db).update(
					"INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
					"someKey", "");
		}

		@Test
		@DisplayName("empty string value is persisted as-is")
		void emptyStringPersisted() {
			service.setSetting("k", "");
			verify(db).update(
					"INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)",
					"k", "");
		}
	}

	// ── getSettingBool ────────────────────────────────────────────────────────

	@Nested
	@DisplayName("getSettingBool")
	class GetSettingBool {

		private void stubSetting(String value) {
			if (value == null) {
				when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
			} else {
				when(db.queryForList(anyString(), anyString()))
						.thenReturn(List.of(Map.of("value", value)));
			}
		}

		@Test
		@DisplayName("'true' string returns true")
		@MockitoSettings(strictness = Strictness.LENIENT)
		void trueStringReturnsTrue() {
			stubSetting("true");
			assertThat(service.getSettingBool("flag", false)).isTrue();
		}

		@Test
		@DisplayName("'false' string returns false")
		@MockitoSettings(strictness = Strictness.LENIENT)
		void falseStringReturnsFalse() {
			stubSetting("false");
			assertThat(service.getSettingBool("flag", true)).isFalse();
		}

		@Test
		@DisplayName("absent key returns fallback=true")
		@MockitoSettings(strictness = Strictness.LENIENT)
		void absentKeyReturnsFallbackTrue() {
			stubSetting(null);
			assertThat(service.getSettingBool("x", true)).isTrue();
		}

		@Test
		@DisplayName("absent key returns fallback=false")
		@MockitoSettings(strictness = Strictness.LENIENT)
		void absentKeyReturnsFallbackFalse() {
			stubSetting(null);
			assertThat(service.getSettingBool("x", false)).isFalse();
		}

		@Test
		@DisplayName("non-boolean string returns fallback")
		@MockitoSettings(strictness = Strictness.LENIENT)
		void nonBooleanStringReturnsFallback() {
			stubSetting("yes"); // not "true" or "false"
			assertThat(service.getSettingBool("flag", true)).isTrue();
		}
	}

	// ── tableCount SQL-injection guard ────────────────────────────────────────

	@Nested
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("tableCount injection guard")
	class TableCountInjectionGuard {

		@Test
		@DisplayName("valid table name passes validation")
		void validTableNameAccepted() {
			when(db.queryForObject(anyString(), eq(Integer.class))).thenReturn(5);
			// Invoke via seedDatabase → seedSettings guard calls tableCount("settings")
			when(appConfig.getDataDir()).thenReturn("/tmp");
			when(appConfig.getDefaultAdminPassword()).thenReturn("CHANGE_ME_ON_FIRST_BOOT");
			when(appConfig.getDefaultAdminEmail()).thenReturn("admin@test.com");
			when(appConfig.getDefaultUserPassword()).thenReturn("defaultUserPass");
			// tableCount("settings") > 0 → seed is skipped; no exception expected
			assertThatNoException().isThrownBy(() -> service.seedDatabase());
		}
	}

	// ── seedDatabase idempotency ───────────────────────────────────────────────

	@Nested
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("seedDatabase idempotency")
	class SeedDatabaseIdempotency {

		@BeforeEach
		void stubNonZeroTables() {
			// All tables already have rows → seeding must be skipped entirely
			when(db.queryForObject(anyString(), eq(Integer.class))).thenReturn(5);
			when(appConfig.getDataDir()).thenReturn("/tmp");
			when(appConfig.getDefaultAdminPassword()).thenReturn("SomePassword123!");
			when(appConfig.getDefaultAdminEmail()).thenReturn("admin@test.com");
			when(appConfig.getDefaultUserPassword()).thenReturn("UserPass123!");
		}

		@Test
		@DisplayName("no INSERT is issued when all tables are non-empty")
		void noInsertsWhenTablesPopulated() {
			service.seedDatabase();
			// db.update() must never be called — all tables already have rows
			verify(db, never()).update(contains("INSERT"), (Object[]) any());
		}

		@Test
		@DisplayName("seedDatabase is safe to call multiple times (idempotent)")
		void callableManyTimes() {
			assertThatNoException().isThrownBy(() -> {
				service.seedDatabase();
				service.seedDatabase();
				service.seedDatabase();
			});
		}

		@Test
		@DisplayName("exception during seeding is swallowed — server stays up")
		void exceptionSwallowed() {
			when(db.queryForObject(anyString(), eq(Integer.class)))
					.thenThrow(new DataAccessException("DB down") {});
			assertThatNoException().isThrownBy(() -> service.seedDatabase());
		}
	}
}
