package com.ollanest.service;

import com.ollanest.testinfra.UserFactory;
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
 * OCD-level unit tests for {@link VaultService}.
 *
 * <p>Covers: {@code getConfig()} — returns {@code enabled=false} when no row found;
 * {@code getConfig()} — never exposes {@code session_enc}; {@code saveConfig()} — calls
 * UPDATE first; INSERT called when no row updated (upsert pattern). Methods that spawn
 * real processes ({@code unlock()}, {@code lock()}, {@code getItem()}) are not tested here.
 *
 * <p>All DB and {@link CryptoService} interactions are Mockito-stubbed.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VaultService — unit tests")
class VaultServiceTest {

    @Mock JdbcTemplate db;
    @Mock CryptoService cryptoService;

    @InjectMocks VaultService svc;

    // ── getConfig() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getConfig()")
    class GetConfig {

        @Test
        @DisplayName("returns enabled=false when no config row exists")
        void returnsEnabledFalseWhenNoRow() {
            when(db.queryForList(contains("FROM vault_config"))).thenReturn(List.of());
            var result = svc.getConfig();
            assertThat(result.get("enabled")).isEqualTo(false);
        }

        @Test
        @DisplayName("never exposes session_enc in returned map")
        void sessionEncNeverExposed() {
            var row = Map.<String, Object>of("id", "singleton", "bw_path", "bw",
                    "server_url", "https://vaultwarden.example.com",
                    "enabled", 1,
                    "session_enc", "super-secret-encrypted-session-key");
            when(db.queryForList(contains("FROM vault_config"))).thenReturn(List.of(row));
            var result = svc.getConfig();
            assertThat(result).doesNotContainKey("session_enc");
        }

        @Test
        @DisplayName("returns bw_path and server_url when row exists")
        void returnsConfigFields() {
            var row = Map.<String, Object>of("id", "singleton", "bw_path", "/usr/bin/bw",
                    "server_url", "https://vault.example.com", "enabled", 1);
            when(db.queryForList(contains("FROM vault_config"))).thenReturn(List.of(row));
            var result = svc.getConfig();
            assertThat(result.get("bw_path")).isEqualTo("/usr/bin/bw");
            assertThat(result.get("server_url")).isEqualTo("https://vault.example.com");
        }
    }

    // ── saveConfig() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("saveConfig()")
    class SaveConfig {

        @Test
        @DisplayName("calls UPDATE first (upsert pattern)")
        void callsUpdateFirst() {
            when(db.update(contains("UPDATE vault_config"), any(), any(), any())).thenReturn(1);
            svc.saveConfig("bw", "https://vault.example.com");
            verify(db).update(contains("UPDATE vault_config"), any(), any(), any());
        }

        @Test
        @DisplayName("calls INSERT when UPDATE affects 0 rows")
        void insertsWhenNoRowUpdated() {
            when(db.update(contains("UPDATE vault_config"), any(), any(), any())).thenReturn(0);
            svc.saveConfig("bw", "https://vault.example.com");
            verify(db).update(contains("INSERT INTO vault_config"), any(), any(), any(), any());
        }

        @Test
        @DisplayName("does not INSERT when UPDATE succeeds")
        void noInsertWhenUpdateSucceeds() {
            when(db.update(contains("UPDATE vault_config"), any(), any(), any())).thenReturn(1);
            svc.saveConfig("bw", null);
            verify(db, never()).update(contains("INSERT INTO vault_config"), (Object[]) any());
        }

        @Test
        @DisplayName("null bwPath defaults to 'bw'")
        void nullBwPathDefaultsToBw() {
            when(db.update(contains("UPDATE vault_config"), any(), any(), any())).thenReturn(1);
            svc.saveConfig(null, "https://vault.example.com");
            verify(db).update(contains("UPDATE vault_config"), eq("bw"), any(), any());
        }
    }
}
