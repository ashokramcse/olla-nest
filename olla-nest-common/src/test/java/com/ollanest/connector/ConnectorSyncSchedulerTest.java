package com.ollanest.connector;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ollanest.service.CryptoService;

/**
 * OCD-level unit tests for {@link ConnectorSyncScheduler}.
 *
 * <p>
 * Covers scheduledSync() with empty connectors and with a known connector.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConnectorSyncScheduler — unit tests")
class ConnectorSyncSchedulerTest {

	@Mock
	JdbcTemplate db;
	@Mock
	ConnectorRegistry registry;
	@Mock
	CryptoService cryptoService;

	@InjectMocks
	ConnectorSyncScheduler scheduler;

	// ── scheduledSync() ───────────────────────────────────────────────────────

	@Nested
	@DisplayName("scheduledSync()")
	class ScheduledSync {

		@Test
		@DisplayName("when no enabled connectors, no connector.sync() calls made")
		void noSyncWhenNoEnabled() {
			// Stub: no enabled connector configs in DB
			when(db.queryForList(contains("WHERE enabled = 1"))).thenReturn(List.of());
			scheduler.scheduledSync();
			// No connector should be looked up if there are no enabled configs
			verify(registry, never()).get(anyString());
		}

		@Test
		@DisplayName("when registry has no connector for type, skip silently")
		void skipsWhenConnectorNotRegistered() {
			Map<String, Object> cfg = Map.of("id", "cfg-1", "type", "unknown_type", "enabled", 1, "credentials_enc",
					"enc-val");
			// Stub: one enabled config with an unrecognised type
			when(db.queryForList(contains("WHERE enabled = 1"))).thenReturn(List.of(cfg));
			when(registry.get("unknown_type")).thenReturn(null);
			// No exception thrown = scheduler gracefully skips unknown types
			assertThatCode(() -> scheduler.scheduledSync()).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("when connector sync succeeds, sync log INSERT is called")
		void syncLogInsertedOnSuccess() throws Exception {
			Map<String, Object> cfg = Map.of("id", "cfg-1", "type", "github", "enabled", 1, "credentials_enc",
					"enc-val");
			// Stub: one enabled github config
			when(db.queryForList(contains("WHERE enabled = 1"))).thenReturn(List.of(cfg));

			BaseConnector connector = mock(BaseConnector.class);
			when(registry.get("github")).thenReturn(connector);
			// Stub: decrypt credentials and return a successful sync result
			when(cryptoService.decryptKey("enc-val")).thenReturn("{\"token\":\"ghp_test\"}");
			when(connector.sync(any(), anyString())).thenReturn(BaseConnector.SyncResult.ok(5, 2));

			scheduler.scheduledSync();

			// Successful sync must be persisted to the sync log for audit/monitoring
			verify(db).update(contains("INSERT INTO connector_sync_log"), any(Object[].class));
		}

		@Test
		@DisplayName("exception in one connector does not prevent DB log write")
		void exceptionWritesErrorLog() {
			Map<String, Object> cfg = Map.of("id", "cfg-2", "type", "slack", "enabled", 1, "credentials_enc",
					"enc-val");
			// Stub: one enabled slack config
			when(db.queryForList(contains("WHERE enabled = 1"))).thenReturn(List.of(cfg));

			BaseConnector connector = mock(BaseConnector.class);
			when(registry.get("slack")).thenReturn(connector);
			when(cryptoService.decryptKey("enc-val")).thenReturn("{}");
			// Stub: connector throws a network error during sync
			when(connector.sync(any(), anyString())).thenThrow(new RuntimeException("Network error"));

			// No exception thrown = scheduler swallows the error (other connectors still
			// run)
			assertThatCode(() -> scheduler.scheduledSync()).doesNotThrowAnyException();
			// Error log should be updated
			verify(db, atLeastOnce()).update(contains("status='error'"), any(Object[].class));
		}
	}
}
