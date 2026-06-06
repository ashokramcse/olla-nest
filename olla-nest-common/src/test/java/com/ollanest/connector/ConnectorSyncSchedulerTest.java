package com.ollanest.connector;

import com.ollanest.service.CryptoService;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link ConnectorSyncScheduler}.
 *
 * <p>Covers scheduledSync() with empty connectors and with a known connector.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConnectorSyncScheduler — unit tests")
class ConnectorSyncSchedulerTest {

    @Mock JdbcTemplate db;
    @Mock ConnectorRegistry registry;
    @Mock CryptoService cryptoService;

    @InjectMocks ConnectorSyncScheduler scheduler;

    // ── scheduledSync() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("scheduledSync()")
    class ScheduledSync {

        @Test
        @DisplayName("when no enabled connectors, no connector.sync() calls made")
        void noSyncWhenNoEnabled() {
            when(db.queryForList(contains("WHERE enabled = 1"))).thenReturn(List.of());
            scheduler.scheduledSync();
            verify(registry, never()).get(anyString());
        }

        @Test
        @DisplayName("when registry has no connector for type, skip silently")
        void skipsWhenConnectorNotRegistered() {
            Map<String, Object> cfg = Map.of("id", "cfg-1", "type", "unknown_type",
                    "enabled", 1, "credentials_enc", "enc-val");
            when(db.queryForList(contains("WHERE enabled = 1"))).thenReturn(List.of(cfg));
            when(registry.get("unknown_type")).thenReturn(null);
            assertThatCode(() -> scheduler.scheduledSync()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("when connector sync succeeds, sync log INSERT is called")
        void syncLogInsertedOnSuccess() throws Exception {
            Map<String, Object> cfg = Map.of("id", "cfg-1", "type", "github",
                    "enabled", 1, "credentials_enc", "enc-val");
            when(db.queryForList(contains("WHERE enabled = 1"))).thenReturn(List.of(cfg));

            BaseConnector connector = mock(BaseConnector.class);
            when(registry.get("github")).thenReturn(connector);
            when(cryptoService.decryptKey("enc-val")).thenReturn("{\"token\":\"ghp_test\"}");
            when(connector.sync(any(), anyString())).thenReturn(BaseConnector.SyncResult.ok(5, 2));

            scheduler.scheduledSync();

            verify(db).update(contains("INSERT INTO connector_sync_log"), (Object[]) any());
        }

        @Test
        @DisplayName("exception in one connector does not prevent DB log write")
        void exceptionWritesErrorLog() {
            Map<String, Object> cfg = Map.of("id", "cfg-2", "type", "slack",
                    "enabled", 1, "credentials_enc", "enc-val");
            when(db.queryForList(contains("WHERE enabled = 1"))).thenReturn(List.of(cfg));

            BaseConnector connector = mock(BaseConnector.class);
            when(registry.get("slack")).thenReturn(connector);
            when(cryptoService.decryptKey("enc-val")).thenReturn("{}");
            when(connector.sync(any(), anyString())).thenThrow(new RuntimeException("Network error"));

            assertThatCode(() -> scheduler.scheduledSync()).doesNotThrowAnyException();
            // Error log should be updated
            verify(db, atLeastOnce()).update(contains("status='error'"), (Object[]) any());
        }
    }
}
