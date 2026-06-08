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
            // Stub: no vault_config row exists (fresh install or vault never configured)
            when(db.queryForList(contains("FROM vault_config"))).thenReturn(List.of());
            var result = svc.getConfig();
            // Safe default: vault is disabled — never "accidentally" enabled
            assertThat(result.get("enabled")).isEqualTo(false);
        }

        @Test
        @DisplayName("never exposes session_enc in returned map")
        void sessionEncNeverExposed() {
            // Stub: DB row contains session_enc (the encrypted Bitwarden session token)
            var row = Map.<String, Object>of("id", "singleton", "bw_path", "bw",
                    "server_url", "https://vaultwarden.example.com",
                    "enabled", 1,
                    "session_enc", "super-secret-encrypted-session-key");
            when(db.queryForList(contains("FROM vault_config"))).thenReturn(List.of(row));
            var result = svc.getConfig();
            // SECURITY: session_enc is an encrypted credential — it must NEVER appear in
            // the API response (could leak the vault unlock material to the browser)
            assertThat(result).doesNotContainKey("session_enc");
        }

        @Test
        @DisplayName("returns bw_path and server_url when row exists")
        void returnsConfigFields() {
            // Stub: fully configured vault row
            var row = Map.<String, Object>of("id", "singleton", "bw_path", "/usr/bin/bw",
                    "server_url", "https://vault.example.com", "enabled", 1);
            when(db.queryForList(contains("FROM vault_config"))).thenReturn(List.of(row));
            var result = svc.getConfig();
            // Non-secret fields (bw_path, server_url) must be returned so the UI can display them
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
            // Stub: UPDATE succeeds (singleton row already exists) → INSERT must NOT run
            when(db.update(contains("UPDATE vault_config"), any(), any(), any())).thenReturn(1);
            svc.saveConfig("bw", "https://vault.example.com");
            // Upsert step 1: attempt UPDATE first
            verify(db).update(contains("UPDATE vault_config"), any(), any(), any());
        }

        @Test
        @DisplayName("calls INSERT when UPDATE affects 0 rows")
        void insertsWhenNoRowUpdated() {
            // Stub: UPDATE returns 0 (no existing row) → service must INSERT instead
            when(db.update(contains("UPDATE vault_config"), any(), any(), any())).thenReturn(0);
            svc.saveConfig("bw", "https://vault.example.com");
            // Upsert step 2: INSERT when no existing row (3 bind args; match any varargs)
            verify(db).update(contains("INSERT INTO vault_config"), any(Object[].class));
        }

        @Test
        @DisplayName("does not INSERT when UPDATE succeeds")
        void noInsertWhenUpdateSucceeds() {
            // Stub: UPDATE row count = 1 → no INSERT should fire (avoid duplicate singleton row)
            when(db.update(contains("UPDATE vault_config"), any(), any(), any())).thenReturn(1);
            svc.saveConfig("bw", null);
            // INSERT must be skipped when UPDATE already persisted the row
            verify(db, never()).update(contains("INSERT INTO vault_config"), any(Object[].class));
        }

        @Test
        @DisplayName("null bwPath defaults to 'bw'")
        void nullBwPathDefaultsToBw() {
            // Stub: UPDATE succeeds
            when(db.update(contains("UPDATE vault_config"), any(), any(), any())).thenReturn(1);
            svc.saveConfig(null, "https://vault.example.com");
            // null bwPath → defaulted to "bw" (system PATH lookup) before DB write
            verify(db).update(contains("UPDATE vault_config"), eq("bw"), any(), any());
        }
    }
}
