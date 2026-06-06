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
 * OCD-level unit tests for {@link WebhookService}.
 *
 * <p>Covers CRUD operations. Network dispatch is not tested.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebhookService — unit tests")
class WebhookServiceTest {

    private static final String OWNER = UserFactory.USER_ID;

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;
    @Mock EventBusService eventBus;

    @InjectMocks WebhookService webhookService;

    private Map<String, Object> webhookRow(String id) {
        return Map.of("id", id, "owner", OWNER, "name", "My Webhook",
                "url", "https://hooks.example.com/recv", "events_json", "[\"chat.completed\"]",
                "enabled", 1, "created_at", "2026-01-01T00:00:00Z");
    }

    // ── create() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("INSERT is called")
        void insertsRow() throws Exception {
            when(mapper.writeValueAsString(any())).thenReturn("[\"chat.completed\"]");
            when(db.queryForList(contains("FROM webhooks WHERE id"), anyString(), anyString()))
                    .thenReturn(List.of(webhookRow("wh-abc")));
            webhookService.create(OWNER, Map.of("url", "https://hooks.example.com/recv", "name", "Hook"));
            verify(db).update(contains("INSERT INTO webhooks"), (Object[]) any());
        }

        @Test
        @DisplayName("id starts with 'wh-'")
        void idPrefix() throws Exception {
            when(mapper.writeValueAsString(any())).thenReturn("[\"chat.completed\"]");
            when(db.queryForList(contains("FROM webhooks WHERE id"), anyString(), anyString()))
                    .thenReturn(List.of(webhookRow("wh-xyz")));
            Map<String, Object> result = webhookService.create(OWNER, Map.of("url", "https://example.com/hook"));
            assertThat(result.get("id").toString()).startsWith("wh-");
        }

        @Test
        @DisplayName("owner is stored in returned record")
        void ownerStored() throws Exception {
            when(mapper.writeValueAsString(any())).thenReturn("[\"chat.completed\"]");
            when(db.queryForList(contains("FROM webhooks WHERE id"), anyString(), anyString()))
                    .thenReturn(List.of(webhookRow("wh-1")));
            Map<String, Object> result = webhookService.create(OWNER, Map.of("url", "https://example.com/hook"));
            assertThat(result.get("owner")).isEqualTo(OWNER);
        }
    }

    // ── getById() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getById()")
    class GetById {

        @Test
        @DisplayName("returns null when not found")
        void nullWhenEmpty() {
            when(db.queryForList(contains("FROM webhooks WHERE id"), anyString(), anyString()))
                    .thenReturn(List.of());
            assertThat(webhookService.getById("wh-999", OWNER)).isNull();
        }

        @Test
        @DisplayName("returns mapped row when found")
        void returnsMappedRow() throws Exception {
            when(mapper.readValue(anyString(), eq(List.class))).thenReturn(List.of("chat.completed"));
            when(db.queryForList(contains("FROM webhooks WHERE id"), eq("wh-1"), eq(OWNER)))
                    .thenReturn(List.of(webhookRow("wh-1")));
            Map<String, Object> result = webhookService.getById("wh-1", OWNER);
            assertThat(result).isNotNull();
            assertThat(result.get("id")).isEqualTo("wh-1");
        }
    }

    // ── list() ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("list()")
    class ListWebhooks {

        @Test
        @DisplayName("queries with owner")
        void queriesWithOwner() throws Exception {
            when(mapper.readValue(anyString(), eq(List.class))).thenReturn(List.of("chat.completed"));
            when(db.queryForList(anyString(), eq(OWNER))).thenReturn(List.of(webhookRow("wh-1")));
            List<Map<String, Object>> results = webhookService.list(OWNER);
            assertThat(results).isNotNull();
            verify(db).queryForList(anyString(), eq(OWNER));
        }
    }

    // ── delete() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("DELETE WHERE id=? AND owner=? is called")
        void deletesWithIdAndOwner() {
            webhookService.delete("wh-1", OWNER);
            verify(db).update(contains("DELETE FROM webhooks WHERE id=? AND owner=?"), eq("wh-1"), eq(OWNER));
        }
    }

    // ── setEnabled() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setEnabled()")
    class SetEnabled {

        @Test
        @DisplayName("UPDATE SET enabled=1 when enabled=true")
        void setsEnabledTrue() {
            webhookService.setEnabled("wh-1", OWNER, true);
            verify(db).update(contains("UPDATE webhooks SET enabled=?"), eq(1), eq("wh-1"), eq(OWNER));
        }

        @Test
        @DisplayName("UPDATE SET enabled=0 when enabled=false")
        void setsEnabledFalse() {
            webhookService.setEnabled("wh-1", OWNER, false);
            verify(db).update(contains("UPDATE webhooks SET enabled=?"), eq(0), eq("wh-1"), eq(OWNER));
        }
    }
}
