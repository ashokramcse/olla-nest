package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.ModelRecord;
import com.ollanest.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ModelService}.
 *
 * <p>Covers: {@code parseModel} field hydration and defaults,
 * {@code allowedModels} access filtering and API-flag enforcement,
 * and JSON capability parsing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelService — unit tests")
class ModelServiceTest {

	@Mock JdbcTemplate    db;
	@Mock UserService     userService;
	@Mock DatabaseService databaseService;

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

			assertThat(m.id).isEqualTo("m-001");
			assertThat(m.name).isEqualTo("Llama 3");
			assertThat(m.provider).isEqualTo("ollama");
			assertThat(m.model).isEqualTo("llama3:8b");
			assertThat(m.status).isEqualTo("available");
			assertThat(m.capabilities).containsExactlyInAnyOrder("coding", "general");
			assertThat(m.speedScore).isEqualTo(85);
			assertThat(m.qualityScore).isEqualTo(70);
			assertThat(m.privacy).isEqualTo("local");
			assertThat(m.gpuRequired).isFalse();
			assertThat(m.maxConcurrency).isEqualTo(4);
			assertThat(m.contextSize).isEqualTo(8192L);
			assertThat(m.sensitiveAllowed).isTrue();
		}

		@Test
		@DisplayName("null row values produce safe defaults")
		void nullValuesProduceSafeDefaults() {
			Map<String, Object> row = new HashMap<>();
			// All fields absent

			ModelRecord m = service.parseModel(row);

			assertThat(m.id).isNull();
			assertThat(m.capabilities).isEmpty();
			assertThat(m.speedScore).isEqualTo(0);
			assertThat(m.qualityScore).isEqualTo(0);
			assertThat(m.maxConcurrency).isEqualTo(2);
			assertThat(m.contextSize).isNull(); // zero context_size → null
			assertThat(m.governanceTier).isEqualTo("approved-local");
			assertThat(m.resourceTier).isEqualTo("standard");
			assertThat(m.externalCostTier).isEqualTo("local-free");
		}

		@Test
		@DisplayName("context_size=0 is stored as null, not 0")
		void zeroContextSizeIsStoredAsNull() {
			Map<String, Object> row = new HashMap<>();
			row.put("context_size", 0);

			ModelRecord m = service.parseModel(row);
			assertThat(m.contextSize).isNull();
		}

		@Test
		@DisplayName("max_context_size fallback used when context_size is absent")
		void maxContextSizeFallback() {
			Map<String, Object> row = new HashMap<>();
			row.put("max_context_size", 16384);

			ModelRecord m = service.parseModel(row);
			assertThat(m.contextSize).isEqualTo(16384L);
		}

		@Test
		@DisplayName("sensitive_allowed=0 maps to false")
		void sensitiveAllowedZeroIsFalse() {
			Map<String, Object> row = new HashMap<>();
			row.put("sensitive_allowed", 0);

			ModelRecord m = service.parseModel(row);
			assertThat(m.sensitiveAllowed).isFalse();
		}

		@Test
		@DisplayName("sensitive_allowed=null maps to true (default allow)")
		void sensitiveAllowedNullIsTrue() {
			Map<String, Object> row = new HashMap<>();
			// sensitive_allowed absent

			ModelRecord m = service.parseModel(row);
			assertThat(m.sensitiveAllowed).isTrue();
		}

		@Test
		@DisplayName("malformed capabilities JSON returns empty list")
		void malformedCapabilitiesJson() {
			Map<String, Object> row = new HashMap<>();
			row.put("capabilities", "{not-an-array}");

			ModelRecord m = service.parseModel(row);
			assertThat(m.capabilities).isEmpty();
		}

		@Test
		@DisplayName("gpu_required=1 maps to true")
		void gpuRequired() {
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
			when(userService.allowedModelIds(user)).thenReturn(List.of("m-1", "m-2"));
			when(databaseService.getSettingBool(eq("allowApiModels"), anyBoolean())).thenReturn(true);
			when(db.queryForList(anyString())).thenReturn(List.of(
					modelRow("m-1", "ollama", "available"),
					modelRow("m-2", "ollama", "available"),
					modelRow("m-3", "ollama", "available")  // not in allowed set
			));

			List<ModelRecord> result = service.allowedModels(user);
			assertThat(result).extracting(m -> m.id).containsExactlyInAnyOrder("m-1", "m-2");
		}

		@Test
		@DisplayName("excludes API models when allowApiModels=false")
		void excludesApiModelsWhenFlagFalse() {
			when(userService.allowedModelIds(user)).thenReturn(List.of("m-local", "m-api"));
			when(databaseService.getSettingBool(eq("allowApiModels"), anyBoolean())).thenReturn(false);
			when(db.queryForList(anyString())).thenReturn(List.of(
					modelRow("m-local", "ollama", "available"),
					modelRow("m-api", "api", "configured")
			));

			List<ModelRecord> result = service.allowedModels(user);
			assertThat(result).extracting(m -> m.id).containsExactly("m-local");
		}

		@Test
		@DisplayName("includes API models when allowApiModels=true")
		void includesApiModelsWhenFlagTrue() {
			when(userService.allowedModelIds(user)).thenReturn(List.of("m-local", "m-api"));
			when(databaseService.getSettingBool(eq("allowApiModels"), anyBoolean())).thenReturn(true);
			when(db.queryForList(anyString())).thenReturn(List.of(
					modelRow("m-local", "ollama", "available"),
					modelRow("m-api", "api", "configured")
			));

			List<ModelRecord> result = service.allowedModels(user);
			assertThat(result).extracting(m -> m.id).containsExactlyInAnyOrder("m-local", "m-api");
		}

		@Test
		@DisplayName("returns empty list when user has no allowed IDs")
		void emptyAllowedIds() {
			when(userService.allowedModelIds(user)).thenReturn(Collections.emptyList());
			when(databaseService.getSettingBool(eq("allowApiModels"), anyBoolean())).thenReturn(false);
			when(db.queryForList(anyString())).thenReturn(List.of(modelRow("m-1", "ollama", "available")));

			List<ModelRecord> result = service.allowedModels(user);
			assertThat(result).isEmpty();
		}

		@Test
		@DisplayName("returns empty list when DB returns no models")
		void emptyDbResult() {
			when(userService.allowedModelIds(user)).thenReturn(List.of("m-1"));
			when(databaseService.getSettingBool(eq("allowApiModels"), anyBoolean())).thenReturn(false);
			when(db.queryForList(anyString())).thenReturn(Collections.emptyList());

			assertThat(service.allowedModels(user)).isEmpty();
		}
	}
}
