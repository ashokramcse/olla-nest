package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;

/**
 * OCD-level unit tests for {@link WebhookService}.
 *
 * <p>
 * Covers CRUD operations. Network dispatch is not tested.
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

	@Mock
	JdbcTemplate db;
	@Mock
	ObjectMapper mapper;
	@Mock
	EventBusService eventBus;

	@InjectMocks
	WebhookService webhookService;

	private Map<String, Object> webhookRow(String id) {
		return Map.of("id", id, "owner", OWNER, "name", "My Webhook", "url", "https://hooks.example.com/recv",
				"events_json", "[\"chat.completed\"]", "enabled", 1, "created_at", "2026-01-01T00:00:00Z");
	}

	// ── create() ─────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("create()")
	class Create {

		@Test
		@DisplayName("INSERT is called")
		void insertsRow() throws Exception {
			// Stub: mapper serializes events list to JSON; DB read-back returns the created
			// row
			when(mapper.writeValueAsString(any())).thenReturn("[\"chat.completed\"]");
			when(db.queryForList(contains("FROM webhooks WHERE id"), anyString(), anyString()))
					.thenReturn(List.of(webhookRow("wh-abc")));
			// Public IP literal (not a hostname) so validateUrl's SSRF check resolves
			// without a DNS lookup — deterministic offline; still a non-private address.
			webhookService.create(OWNER, Map.of("url", "https://93.184.216.34/recv", "name", "Hook"));
			// Verify the INSERT into webhooks was executed
			verify(db).update(contains("INSERT INTO webhooks"), any(Object[].class));
		}

		@Test
		@DisplayName("id starts with 'wh-'")
		void idPrefix() throws Exception {
			// Stub mapper + DB read-back returning the created row
			when(mapper.writeValueAsString(any())).thenReturn("[\"chat.completed\"]");
			when(db.queryForList(contains("FROM webhooks WHERE id"), anyString(), anyString()))
					.thenReturn(List.of(webhookRow("wh-xyz")));
			Map<String, Object> result = webhookService.create(OWNER, Map.of("url", "https://example.com/hook"));
			// "wh-" prefix identifies webhook records in URLs and logs
			assertThat(result.get("id").toString()).startsWith("wh-");
		}

		@Test
		@DisplayName("owner is stored in returned record")
		void ownerStored() throws Exception {
			// Stub DB read-back returns row with OWNER
			when(mapper.writeValueAsString(any())).thenReturn("[\"chat.completed\"]");
			when(db.queryForList(contains("FROM webhooks WHERE id"), anyString(), anyString()))
					.thenReturn(List.of(webhookRow("wh-1")));
			Map<String, Object> result = webhookService.create(OWNER, Map.of("url", "https://example.com/hook"));
			// Owner stored so that getById / list can scope queries correctly
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
			// Stub: no row found (wrong id or wrong owner)
			when(db.queryForList(contains("FROM webhooks WHERE id"), anyString(), anyString())).thenReturn(List.of());
			// Null returned (not a 404 exception) — caller decides how to respond
			assertThat(webhookService.getById("wh-999", OWNER)).isNull();
		}

		@Test
		@DisplayName("returns mapped row when found")
		void returnsMappedRow() throws Exception {
			// Stub: mapper deserializes events_json to a list; DB returns the row
			when(mapper.readValue(anyString(), eq(List.class))).thenReturn(List.of("chat.completed"));
			when(db.queryForList(contains("FROM webhooks WHERE id"), eq("wh-1"), eq(OWNER)))
					.thenReturn(List.of(webhookRow("wh-1")));
			Map<String, Object> result = webhookService.getById("wh-1", OWNER);
			assertThat(result).isNotNull();
			// ID preserved in the returned map
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
			// Stub: mapper for events_json field; DB returns one webhook row
			when(mapper.readValue(anyString(), eq(List.class))).thenReturn(List.of("chat.completed"));
			when(db.queryForList(anyString(), eq(OWNER))).thenReturn(List.of(webhookRow("wh-1")));
			List<Map<String, Object>> results = webhookService.list(OWNER);
			assertThat(results).isNotNull();
			// DB query must be scoped to the caller's owner ID
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
			// DELETE scoped to BOTH id and owner — prevents cross-user deletion
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
			// true → integer 1 (SQLite boolean encoding)
			verify(db).update(contains("UPDATE webhooks SET enabled=?"), eq(1), eq("wh-1"), eq(OWNER));
		}

		@Test
		@DisplayName("UPDATE SET enabled=0 when enabled=false")
		void setsEnabledFalse() {
			webhookService.setEnabled("wh-1", OWNER, false);
			// false → integer 0
			verify(db).update(contains("UPDATE webhooks SET enabled=?"), eq(0), eq("wh-1"), eq(OWNER));
		}
	}
}
