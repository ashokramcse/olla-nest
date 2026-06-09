package com.ollanest.connector;

import com.ollanest.service.CryptoService;
import com.ollanest.service.RagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OCD-level unit tests for {@link ConnectorRegistry}.
 *
 * <p>Covers get() lookup by type key and types() list.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConnectorRegistry — unit tests")
class ConnectorRegistryTest {

    @Mock JdbcTemplate db;
    @Mock RagService ragService;
    @Mock CryptoService cryptoService;

    private BaseConnector mockConnector(String type) {
        BaseConnector c = mock(BaseConnector.class);
        when(c.getType()).thenReturn(type);
        return c;
    }

    // ── get() ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("get()")
    class Get {

        @Test
        @DisplayName("returns the GitHub connector when registered")
        void returnsGithubConnector() {
            BaseConnector github = mockConnector("github");
            ConnectorRegistry registry = new ConnectorRegistry(List.of(github), db, ragService, cryptoService);
            // Lookup by type key must return the exact same instance
            assertThat(registry.get("github")).isSameAs(github);
        }

        @Test
        @DisplayName("returns null for nonexistent type")
        void nullForNonexistent() {
            BaseConnector github = mockConnector("github");
            ConnectorRegistry registry = new ConnectorRegistry(List.of(github), db, ragService, cryptoService);
            // Unknown type → null, not an exception
            assertThat(registry.get("nonexistent")).isNull();
        }

        @Test
        @DisplayName("sets dependencies on each connector during construction")
        void setsDependencies() {
            BaseConnector c = mockConnector("test");
            new ConnectorRegistry(List.of(c), db, ragService, cryptoService);
            // Registry must inject shared dependencies into each connector at construction time
            verify(c).setDependencies(db, ragService, cryptoService);
        }
    }

    // ── types() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("types()")
    class Types {

        @Test
        @DisplayName("returns list containing all registered type keys")
        void containsAllTypes() {
            ConnectorRegistry registry = new ConnectorRegistry(
                    List.of(mockConnector("github"), mockConnector("slack"), mockConnector("notion")),
                    db, ragService, cryptoService);
            // All three type keys must be present regardless of registration order
            assertThat(registry.types()).containsExactlyInAnyOrder("github", "slack", "notion");
        }

        @Test
        @DisplayName("returns sorted list")
        void returnsSorted() {
            ConnectorRegistry registry = new ConnectorRegistry(
                    List.of(mockConnector("slack"), mockConnector("github"), mockConnector("notion")),
                    db, ragService, cryptoService);
            // Alphabetical sort ensures stable UI ordering
            assertThat(registry.types()).isSortedAccordingTo(String::compareTo);
        }

        @Test
        @DisplayName("returns empty list when no connectors registered")
        void emptyWhenNoConnectors() {
            ConnectorRegistry registry = new ConnectorRegistry(List.of(), db, ragService, cryptoService);
            assertThat(registry.types()).isEmpty();
        }
    }
}
