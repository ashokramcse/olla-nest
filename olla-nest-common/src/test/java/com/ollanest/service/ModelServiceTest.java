package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.ModelRecord;
import com.ollanest.model.User;

/**
 * Unit tests for {@link ModelService}.
 *
 * <p>
 * Covers: {@code parseModel} field hydration and defaults,
 * {@code allowedModels} access filtering and API-flag enforcement, and JSON
 * capability parsing.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelService — unit tests")
class ModelServiceTest {

	@Mock
	JdbcTemplate db;
	@Mock
	UserService userService;
	@Mock
	DatabaseService databaseService;

	private final ObjectMapper mapper = new ObjectMapper();
	private ModelService service;

	@BeforeEach
	void setUp() {
		service = new ModelService(db, mapper, userService, databaseService);
	}

	// ── parseModel ────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("parseModel")
	class ParseModel {

		@Test
		@DisplayName("maps all non-null fields correctly")
		void mapsAllFields() {
			// Build a fully-populated DB row to verify every field mapping
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", "m-001");
			row.put("name", "Llama 3");
			row.put("provider", "ollama");
			row.put("model_ref", "llama3:8b");
			row.put("status", "available");
			row.put("capabilities", "[\"coding\",\"general\"]");
			row.put("speed_score", 85);
			row.put("quality_score", 70);
			row.put("privacy", "local");
			row.put("governance_tier", "approved-local");
			row.put("resource_tier", "standard");
			row.put("gpu_required", 0);
			row.put("max_concurrency", 4);
			row.put("context_size", 8192);
			row.put("external_cost_tier", "local-free");
			row.put("sensitive_allowed", 1);

			ModelRecord m = service.parseModel(row);

			// Verify all scalar fields are correctly hydrated
			assertThat(m.id).isEqualTo("m-001");
			assertThat(m.name).isEqualTo("Llama 3");
			assertThat(m.provider).isEqualTo("ollama");
			assertThat(m.model).isEqualTo("llama3:8b");
			assertThat(m.status).isEqualTo("available");
			// Capabilities JSON is parsed into a list
			assertThat(m.capabilities).containsExactlyInAnyOrder("coding", "general");
			assertThat(m.speedScore).isEqualTo(85);
			assertThat(m.qualityScore).isEqualTo(70);
			assertThat(m.privacy).isEqualTo("local");
			// gpu_required=0 must map to false (not true)
			assertThat(m.gpuRequired).isFalse();
			assertThat(m.maxConcurrency).isEqualTo(4);
			assertThat(m.contextSize).isEqualTo(8192L);
			// sensitive_allowed=1 must map to true
			assertThat(m.sensitiveAllowed).isTrue();
		}

		@Test
		@DisplayName("null row values produce safe defaults")
		void nullValuesProduceSafeDefaults() {
			// All fields absent — verify the service returns safe defaults rather than NPE
			Map<String, Object> row = new HashMap<>();

			ModelRecord m = service.parseModel(row);

			assertThat(m.id).isNull();
			// Missing capabilities JSON should yield an empty list, not null
			assertThat(m.capabilities).isEmpty();
			assertThat(m.speedScore).isEqualTo(0);
			assertThat(m.qualityScore).isEqualTo(0);
			// Default concurrency guard prevents unlimited parallel requests
			assertThat(m.maxConcurrency).isEqualTo(2);
			// zero context_size is treated as "not set" → null
			assertThat(m.contextSize).isNull();
			// Governance/resource/cost tiers have safe hardcoded defaults
			assertThat(m.governanceTier).isEqualTo("approved-local");
			assertThat(m.resourceTier).isEqualTo("standard");
			assertThat(m.externalCostTier).isEqualTo("local-free");
		}

		@Test
		@DisplayName("context_size=0 is stored as null, not 0")
		void zeroContextSizeIsStoredAsNull() {
			// A row with context_size=0 means "unknown" — must be stored as null to
			// avoid router treating it as a 0-token window
			Map<String, Object> row = new HashMap<>();
			row.put("context_size", 0);

			ModelRecord m = service.parseModel(row);
			assertThat(m.contextSize).isNull();
		}

		@Test
		@DisplayName("max_context_size fallback used when context_size is absent")
		void maxContextSizeFallback() {
			// Some DB rows use max_context_size instead of context_size — verify fallback
			Map<String, Object> row = new HashMap<>();
			row.put("max_context_size", 16384);

			ModelRecord m = service.parseModel(row);
			assertThat(m.contextSize).isEqualTo(16384L);
		}

		@Test
		@DisplayName("sensitive_allowed=0 maps to false")
		void sensitiveAllowedZeroIsFalse() {
			// SECURITY: sensitive_allowed=0 must deny sensitive data routing to this model
			Map<String, Object> row = new HashMap<>();
			row.put("sensitive_allowed", 0);

			ModelRecord m = service.parseModel(row);
			assertThat(m.sensitiveAllowed).isFalse();
		}

		@Test
		@DisplayName("sensitive_allowed=null maps to true (default allow)")
		void sensitiveAllowedNullIsTrue() {
			// Default is permissive — absence of the flag does not block sensitive routing
			Map<String, Object> row = new HashMap<>();
			// sensitive_allowed absent

			ModelRecord m = service.parseModel(row);
			assertThat(m.sensitiveAllowed).isTrue();
		}

		@Test
		@DisplayName("malformed capabilities JSON returns empty list")
		void malformedCapabilitiesJson() {
			// Corrupt JSON must not crash the parser — graceful degradation to empty list
			Map<String, Object> row = new HashMap<>();
			row.put("capabilities", "{not-an-array}");

			ModelRecord m = service.parseModel(row);
			assertThat(m.capabilities).isEmpty();
		}

		@Test
		@DisplayName("gpu_required=1 maps to true")
		void gpuRequired() {
			// gpu_required=1 must map to true so the router skips GPU models on CPU-only
			// deployments
			Map<String, Object> row = new HashMap<>();
			row.put("gpu_required", 1);

			ModelRecord m = service.parseModel(row);
			assertThat(m.gpuRequired).isTrue();
		}
	}

	// ── allowedModels ─────────────────────────────────────────────────────────

	@Nested
	@DisplayName("allowedModels")
	class AllowedModels {

		private User user;

		@BeforeEach
		void setUpUser() {
			user = new User();
			user.id = "u-001";
			user.role = "user";
		}

		private Map<String, Object> modelRow(String id, String provider, String status) {
			Map<String, Object> row = new HashMap<>();
			row.put("id", id);
			row.put("name", "Model " + id);
			row.put("provider", provider);
			row.put("status", status);
			row.put("capabilities", "[]");
			return row;
		}

		@Test
		@DisplayName("returns only models matching user's allowed IDs")
		void filtersToAllowedIds() {
			// Stub: user is granted access to m-1 and m-2 only
			when(userService.allowedModelIds(user)).thenReturn(List.of("m-1", "m-2"));
			when(databaseService.getSettingBool(eq("allowApiModels"), anyBoolean())).thenReturn(true);
			// DB returns 3 models — m-3 is in the DB but not in the user's grant list
			when(db.queryForList(anyString())).thenReturn(List.of(modelRow("m-1", "ollama", "available"),
					modelRow("m-2", "ollama", "available"), modelRow("m-3", "ollama", "available") // not in allowed set
			));

			List<ModelRecord> result = service.allowedModels(user);
			// Only the two granted models must appear — m-3 is silently dropped
			assertThat(result).extracting(m -> m.id).containsExactlyInAnyOrder("m-1", "m-2");
		}

		@Test
		@DisplayName("excludes API models when allowApiModels=false")
		void excludesApiModelsWhenFlagFalse() {
			// Stub: user is granted both, but the global flag prohibits API models
			when(userService.allowedModelIds(user)).thenReturn(List.of("m-local", "m-api"));
			when(databaseService.getSettingBool(eq("allowApiModels"), anyBoolean())).thenReturn(false);
			when(db.queryForList(anyString())).thenReturn(
					List.of(modelRow("m-local", "ollama", "available"), modelRow("m-api", "api", "configured")));

			List<ModelRecord> result = service.allowedModels(user);
			// API model must be filtered out when the admin flag is off
			assertThat(result).extracting(m -> m.id).containsExactly("m-local");
		}

		@Test
		@DisplayName("includes API models when allowApiModels=true")
		void includesApiModelsWhenFlagTrue() {
			// Stub: global flag allows API models
			when(userService.allowedModelIds(user)).thenReturn(List.of("m-local", "m-api"));
			when(databaseService.getSettingBool(eq("allowApiModels"), anyBoolean())).thenReturn(true);
			when(db.queryForList(anyString())).thenReturn(
					List.of(modelRow("m-local", "ollama", "available"), modelRow("m-api", "api", "configured")));

			List<ModelRecord> result = service.allowedModels(user);
			// Both models must be present when the admin permits API access
			assertThat(result).extracting(m -> m.id).containsExactlyInAnyOrder("m-local", "m-api");
		}

		@Test
		@DisplayName("returns empty list when user has no allowed IDs")
		void emptyAllowedIds() {
			// Stub: user has zero model grants — should not see any models at all
			when(userService.allowedModelIds(user)).thenReturn(Collections.emptyList());
			when(databaseService.getSettingBool(eq("allowApiModels"), anyBoolean())).thenReturn(false);
			when(db.queryForList(anyString())).thenReturn(List.of(modelRow("m-1", "ollama", "available")));

			List<ModelRecord> result = service.allowedModels(user);
			// Empty grant list must result in empty response — not all models
			assertThat(result).isEmpty();
		}

		@Test
		@DisplayName("returns empty list when DB returns no models")
		void emptyDbResult() {
			// Stub: DB is empty (no models configured) — must not throw
			when(userService.allowedModelIds(user)).thenReturn(List.of("m-1"));
			when(databaseService.getSettingBool(eq("allowApiModels"), anyBoolean())).thenReturn(false);
			when(db.queryForList(anyString())).thenReturn(Collections.emptyList());

			assertThat(service.allowedModels(user)).isEmpty();
		}
	}
}
