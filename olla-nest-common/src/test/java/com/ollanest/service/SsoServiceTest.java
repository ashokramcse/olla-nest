package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * OCD-level unit tests for {@link SsoService}.
 *
 * <p>Covers state management, provider listing, and Google auth URL construction.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SsoService — unit tests")
class SsoServiceTest {

    @Mock JdbcTemplate db;
    @Mock CryptoService cryptoService;
    @Mock ObjectMapper mapper;

    @InjectMocks SsoService ssoService;

    // ── createState() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createState()")
    class CreateState {

        @Test
        @DisplayName("INSERT is called")
        void insertsRow() {
            String state = ssoService.createState("provider-1", "/dashboard");
            verify(db).update(contains("INSERT INTO oauth_state"), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("returns non-null non-blank state token")
        void returnsNonBlankToken() {
            String state = ssoService.createState("provider-1", "/dashboard");
            assertThat(state).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("state is 64 hex characters")
        void stateIs64Hex() {
            String state = ssoService.createState("provider-1", null);
            assertThat(state).hasSize(64);
            assertThat(state).matches("[0-9a-f]{64}");
        }
    }

    // ── validateState() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateState()")
    class ValidateState {

        @Test
        @DisplayName("returns null when state not found in DB")
        void nullWhenNotFound() {
            when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
            assertThat(ssoService.validateState("invalid-state-xyz")).isNull();
        }

        @Test
        @DisplayName("returns map when found and deletes the state")
        void returnsMapAndDeletes() {
            Map<String, Object> row = Map.of("state", "abc123", "provider_id", "p-1", "redirect_uri", "/");
            when(db.queryForList(anyString(), eq("abc123"))).thenReturn(List.of(row));
            Map<String, Object> result = ssoService.validateState("abc123");
            assertThat(result).isNotNull();
            assertThat(result.get("provider_id")).isEqualTo("p-1");
            verify(db).update(contains("DELETE FROM oauth_state"), eq("abc123"));
        }
    }

    // ── cleanExpiredOAuthState() ──────────────────────────────────────────────

    @Nested
    @DisplayName("cleanExpiredOAuthState()")
    class CleanExpiredOAuthState {

        @Test
        @DisplayName("DELETE called for expired states")
        void deletesExpiredStates() {
            ssoService.cleanExpiredOAuthState();
            verify(db).update(contains("DELETE FROM oauth_state WHERE created_at <"));
        }
    }

    // ── listEnabledProviders() ────────────────────────────────────────────────

    @Nested
    @DisplayName("listEnabledProviders()")
    class ListEnabledProviders {

        @Test
        @DisplayName("queries DB for enabled=1 providers")
        void queriesForEnabled() {
            when(db.queryForList(contains("WHERE enabled = 1"))).thenReturn(List.of(
                    Map.of("id", "sso-google", "type", "google", "name", "Google")));
            List<Map<String, Object>> results = ssoService.listEnabledProviders();
            assertThat(results).hasSize(1);
        }
    }

    // ── buildGoogleAuthUrl() ──────────────────────────────────────────────────

    @Nested
    @DisplayName("buildGoogleAuthUrl()")
    class BuildGoogleAuthUrl {

        @Test
        @DisplayName("URL contains accounts.google.com")
        void containsGoogleDomain() {
            String url = ssoService.buildGoogleAuthUrl("client-123", "state-abc", null);
            assertThat(url).contains("accounts.google.com");
        }

        @Test
        @DisplayName("URL contains client_id parameter")
        void containsClientId() {
            String url = ssoService.buildGoogleAuthUrl("client-123", "state-abc", null);
            assertThat(url).contains("client_id=");
        }

        @Test
        @DisplayName("URL contains state parameter")
        void containsState() {
            String url = ssoService.buildGoogleAuthUrl("client-123", "my-state-value", null);
            assertThat(url).contains("state=");
        }

        @Test
        @DisplayName("URL contains hd= when hostedDomain is set")
        void containsHdParam() {
            String url = ssoService.buildGoogleAuthUrl("client-123", "state-abc", "myco.com");
            assertThat(url).contains("hd=");
        }

        @Test
        @DisplayName("URL does not contain hd= when hostedDomain is null")
        void noHdParamWhenNull() {
            String url = ssoService.buildGoogleAuthUrl("client-123", "state-abc", null);
            assertThat(url).doesNotContain("hd=");
        }
    }
}
