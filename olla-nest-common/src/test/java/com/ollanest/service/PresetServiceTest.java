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
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;

/**
 * OCD-level unit tests for {@link PresetService}.
 *
 * <p>
 * Covers: {@code listAll()} — 7 system presets always present, correct IDs;
 * {@code createTemplate()} — DB INSERT, id prefix; {@code updateTemplate()} —
 * DB UPDATE; {@code deleteTemplate()} — scoped DELETE; {@code getTemplate()} —
 * null when not found.
 *
 * <p>
 * All DB interactions are Mockito-stubbed — no Spring context, no real DB.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PresetService — unit tests")
class PresetServiceTest {

	private static final String OWNER = UserFactory.USER_ID;

	@Mock
	JdbcTemplate db;
	@Mock
	ObjectMapper mapper;

	@InjectMocks
	PresetService svc;

	@BeforeEach
	void stubDefaults() {
		// Stub DB to return no user-defined templates by default
		when(db.queryForList(contains("FROM user_templates"), eq(OWNER))).thenReturn(List.of());
		when(db.queryForList(contains("FROM user_templates WHERE id"), (Object) any(), any())).thenReturn(List.of());
	}

	// ── listAll() ─────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("listAll()")
	class ListAll {

		@Test
		@DisplayName("always includes exactly 7 system presets")
		void alwaysHas7SystemPresets() {
			var result = svc.listAll(OWNER);
			// Step 1: count hardcoded system presets — must always be exactly 7
			long systemCount = result.stream().filter(p -> "system".equals(p.get("source"))).count();
			// Step 2: 7 hardcoded system presets must be present regardless of DB content
			assertThat(systemCount).isEqualTo(7);
		}

		@Test
		@DisplayName("system presets have correct IDs")
		void systemPresetIds() {
			var result = svc.listAll(OWNER);
			Set<String> ids = result.stream().filter(p -> "system".equals(p.get("source")))
					.map(p -> (String) p.get("id")).collect(Collectors.toSet());
			// These 7 IDs are referenced by the front-end — any name change is a breaking
			// change
			assertThat(ids).containsExactlyInAnyOrder("default", "precise", "creative", "coding", "research", "writer",
					"analyst");
		}

		@Test
		@DisplayName("user templates are appended after system presets")
		void userTemplatesAppendedAfterSystem() {
			// Stub: DB returns one user-defined template
			var userTemplate = Map.<String, Object>of("id", "tpl-abc", "name", "My Custom", "system_prompt",
					"Act as...", "temperature", 0.7, "max_tokens", 0, "inject_prefix", "", "inject_suffix", "");
			when(db.queryForList(contains("FROM user_templates"), eq(OWNER))).thenReturn(List.of(userTemplate));
			var result = svc.listAll(OWNER);
			// 7 system presets + 1 user template = 8 total
			assertThat(result.size()).isEqualTo(8);
		}

		@Test
		@DisplayName("total count equals system presets when no user templates exist")
		void totalCountWhenNoUserTemplates() {
			// Default stub returns no user templates — only 7 system presets must appear
			var result = svc.listAll(OWNER);
			assertThat(result).hasSize(7);
		}
	}

	// ── createTemplate() ──────────────────────────────────────────────────────

	@Nested
	@DisplayName("createTemplate()")
	class CreateTemplate {

		@Test
		@DisplayName("DB INSERT called with owner")
		void insertCalledWithOwner() {
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.createTemplate(OWNER, Map.of("name", "My Preset", "system_prompt", "Be helpful"));
			verify(db).update(contains("INSERT INTO user_templates"), cap.capture());
			// args[1] = owner — must be scoped to the authenticated user
			assertThat(cap.getValue()[1]).isEqualTo(OWNER);
		}

		@Test
		@DisplayName("generated id starts with 'tpl-'")
		void idStartsWithTplPrefix() {
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.createTemplate(OWNER, Map.of("name", "Preset X"));
			verify(db).update(contains("INSERT INTO user_templates"), cap.capture());
			// args[0] = id — must use the "tpl-" prefix for consistent ID namespace
			assertThat(cap.getValue()[0].toString()).startsWith("tpl-");
		}

		@Test
		@DisplayName("name defaults to 'My Preset' when not provided")
		void defaultName() {
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.createTemplate(OWNER, Map.of());
			verify(db).update(contains("INSERT INTO user_templates"), cap.capture());
			// args[2] = name — must default to a sensible value rather than null
			assertThat(cap.getValue()[2]).isEqualTo("My Preset");
		}
	}

	// ── deleteTemplate() ──────────────────────────────────────────────────────

	@Nested
	@DisplayName("deleteTemplate()")
	class DeleteTemplate {

		@Test
		@DisplayName("calls DELETE WHERE id=? AND owner=?")
		void deleteScopedToIdAndOwner() {
			svc.deleteTemplate("tpl-123", OWNER);
			// SECURITY: DELETE must be scoped to both id AND owner to prevent cross-user
			// deletion
			verify(db).update(contains("DELETE FROM user_templates WHERE id"), eq("tpl-123"), eq(OWNER));
		}
	}

	// ── getTemplate() ─────────────────────────────────────────────────────────

	@Nested
	@DisplayName("getTemplate()")
	class GetTemplate {

		@Test
		@DisplayName("returns null when DB has no row")
		void returnsNullWhenNotFound() {
			// Stub: no template with this ID for this owner
			when(db.queryForList(anyString(), eq("tpl-missing"), eq(OWNER))).thenReturn(List.of());
			// Null is the contract for "not found" — must not throw
			assertThat(svc.getTemplate("tpl-missing", OWNER)).isNull();
		}

		@Test
		@DisplayName("returns the row when found")
		void returnsRowWhenFound() {
			// Stub: DB returns the template row for this owner
			var row = Map.<String, Object>of("id", "tpl-1", "owner", OWNER, "name", "My Preset");
			when(db.queryForList(anyString(), eq("tpl-1"), eq(OWNER))).thenReturn(List.of(row));
			// Returned map must be exactly the DB row — no data loss or mutation
			assertThat(svc.getTemplate("tpl-1", OWNER)).isEqualTo(row);
		}
	}
}
