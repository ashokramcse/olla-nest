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
            // SECURITY: OAuth state must be persisted to DB so it can be validated on callback
            String state = ssoService.createState("provider-1", "/dashboard");
            verify(db).update(contains("INSERT INTO oauth_state"), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("returns non-null non-blank state token")
        void returnsNonBlankToken() {
            // State token is returned to the browser as a query param — must not be blank
            String state = ssoService.createState("provider-1", "/dashboard");
            assertThat(state).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("state is 64 hex characters")
        void stateIs64Hex() {
            // SECURITY: 64 hex chars = 256 bits of entropy — prevents CSRF via state forgery
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
            // Stub: unknown/expired state — must return null, not throw
            when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
            // Null = state validation failed — caller must reject the OAuth callback
            assertThat(ssoService.validateState("invalid-state-xyz")).isNull();
        }

        @Test
        @DisplayName("returns map when found and deletes the state")
        void returnsMapAndDeletes() {
            // Stub: valid state token found in DB
            Map<String, Object> row = Map.of("state", "abc123", "provider_id", "p-1", "redirect_uri", "/");
            when(db.queryForList(anyString(), eq("abc123"))).thenReturn(List.of(row));
            Map<String, Object> result = ssoService.validateState("abc123");
            // State must be returned so the caller can complete the OAuth flow
            assertThat(result).isNotNull();
            assertThat(result.get("provider_id")).isEqualTo("p-1");
            // SECURITY: state must be deleted after validation — prevents replay attacks
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
            // Expired OAuth states must be swept to prevent DB bloat and stale entry reuse
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
            // Stub: one enabled SSO provider in DB
            when(db.queryForList(contains("WHERE enabled = 1"))).thenReturn(List.of(
                    Map.of("id", "sso-google", "type", "google", "name", "Google")));
            List<Map<String, Object>> results = ssoService.listEnabledProviders();
            // Only enabled providers must be returned — disabled ones must be hidden from login UI
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
            // Google OAuth must always direct to Google's authorization server
            String url = ssoService.buildGoogleAuthUrl("client-123", "state-abc", null);
            assertThat(url).contains("accounts.google.com");
        }

        @Test
        @DisplayName("URL contains client_id parameter")
        void containsClientId() {
            // client_id is required by OAuth2 — missing it would cause the auth to fail
            String url = ssoService.buildGoogleAuthUrl("client-123", "state-abc", null);
            assertThat(url).contains("client_id=");
        }

        @Test
        @DisplayName("URL contains state parameter")
        void containsState() {
            // SECURITY: state parameter prevents CSRF — must be present in the redirect URL
            String url = ssoService.buildGoogleAuthUrl("client-123", "my-state-value", null);
            assertThat(url).contains("state=");
        }

        @Test
        @DisplayName("URL contains hd= when hostedDomain is set")
        void containsHdParam() {
            // hd= restricts login to a specific Google Workspace domain — used for enterprise SSO
            String url = ssoService.buildGoogleAuthUrl("client-123", "state-abc", "myco.com");
            assertThat(url).contains("hd=");
        }

        @Test
        @DisplayName("URL does not contain hd= when hostedDomain is null")
        void noHdParamWhenNull() {
            // Without a hosted domain, any Google account can authenticate — hd= must be absent
            String url = ssoService.buildGoogleAuthUrl("client-123", "state-abc", null);
            assertThat(url).doesNotContain("hd=");
        }
    }
}
