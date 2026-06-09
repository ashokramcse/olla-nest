package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.ModelRecord;
import com.ollanest.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RouterService}.
 *
 * <p>Covers: sensitive-content detection, request classification, model
 * routing (privacy blocking, scoring, router-disabled path, empty candidate
 * list), and the null-safety fix in {@code detectSensitiveContent}.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RouterService — unit tests")
class RouterServiceTest {

	@Mock ModelService     modelService;
	@Mock DatabaseService  databaseService;

	/** Real ObjectMapper — no stubs needed for JSON parsing. */
	private final ObjectMapper mapper = new ObjectMapper();

	private RouterService router;

	@BeforeEach
	void setUp() {
		router = new RouterService(modelService, databaseService, mapper);
	}

	// ── Helper ───────────────────────────────────────────────────────────────

	private ModelRecord model(String id, String name, String privacy, String provider,
			int speed, int quality, String... caps) {
		ModelRecord m = new ModelRecord();
		m.id = id;
		m.name = name;
		m.privacy = privacy;
		m.provider = provider;
		m.speedScore = speed;
		m.qualityScore = quality;
		m.capabilities = Arrays.asList(caps);
		return m;
	}

	private User adminUser() {
		User u = new User();
		u.id = "u-admin";
		u.role = "admin";
		return u;
	}

	// ── detectSensitiveContent ───────────────────────────────────────────────

	@Nested
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("detectSensitiveContent")
	class DetectSensitiveContent {

		@BeforeEach
		void noCustomPatterns() {
			// Stub: no admin-configured custom patterns for these tests
			when(databaseService.getSetting(eq("sensitivePatterns"), any())).thenReturn(null);
		}

		@Test
		@DisplayName("null input returns non-sensitive result without NPE")
		void nullInputIsNotSensitive() {
			// Null content must not crash — privacy detection must be null-safe
			RouterService.SensitivityResult r = router.detectSensitiveContent(null);
			assertThat(r.isSensitive).isFalse();
			assertThat(r.reasons).isEmpty();
		}

		@Test
		@DisplayName("clean text is not sensitive")
		void cleanTextNotSensitive() {
			// Ordinary technical content must never trigger false-positive privacy blocking
			RouterService.SensitivityResult r = router.detectSensitiveContent("How does Spring Boot work?");
			assertThat(r.isSensitive).isFalse();
			assertThat(r.reasons).isEmpty();
		}

		@Test
		@DisplayName("SSN pattern detected")
		void detectsSSN() {
			// SECURITY: SSN pattern must be caught to prevent local-only data from going to API models
			RouterService.SensitivityResult r = router.detectSensitiveContent("My SSN is 123-45-6789 — keep it safe.");
			assertThat(r.isSensitive).isTrue();
			assertThat(r.reasons).contains("SSN");
		}

		@Test
		@DisplayName("credit card pattern detected")
		void detectsCreditCard() {
			// SECURITY: PCI-DSS — credit card numbers must trigger local-only routing
			RouterService.SensitivityResult r = router.detectSensitiveContent("Card: 4111-1111-1111-1111");
			assertThat(r.isSensitive).isTrue();
			assertThat(r.reasons).contains("credit card");
		}

		@Test
		@DisplayName("OpenAI-style API key pattern detected")
		void detectsApiKey() {
			// SECURITY: API key in prompt must be caught before it leaks to an external provider
			RouterService.SensitivityResult r = router.detectSensitiveContent("Use key sk-abcdefghijklmnopqrstu123");
			assertThat(r.isSensitive).isTrue();
			assertThat(r.reasons).contains("API key");
		}

		@Test
		@DisplayName("medical/PHI terms detected (case-insensitive)")
		void detectsMedical() {
			// SECURITY: HIPAA — medical/PHI keywords must trigger local-only routing
			RouterService.SensitivityResult r = router.detectSensitiveContent("Patient record shows HIPAA-covered diagnosis");
			assertThat(r.isSensitive).isTrue();
			assertThat(r.reasons).containsAnyOf("medical/PHI");
		}

		@Test
		@DisplayName("admin-configured custom pattern detected")
		void detectsAdminPattern() {
			// Stub: admin has added a custom regex for a proprietary project codename
			when(databaseService.getSetting(eq("sensitivePatterns"), any()))
					.thenReturn("[\"secret-project-[A-Z]+\"]");
			RouterService.SensitivityResult r = router.detectSensitiveContent("Working on secret-project-ALPHA");
			assertThat(r.isSensitive).isTrue();
			// Custom admin pattern must be surfaced in reasons for audit purposes
			assertThat(r.reasons).anyMatch(s -> s.contains("admin pattern"));
		}

		@Test
		@DisplayName("invalid admin regex pattern is silently ignored")
		void invalidAdminPatternIgnored() {
			// Stub: admin has misconfigured a broken regex — must not crash the router
			when(databaseService.getSetting(eq("sensitivePatterns"), any()))
					.thenReturn("[\"[invalid-regex\"]");
			RouterService.SensitivityResult r = router.detectSensitiveContent("Hello world");
			// Invalid regex must be silently skipped — not propagate as an exception
			assertThat(r.isSensitive).isFalse();
		}

		@Test
		@DisplayName("multiple patterns in same text — all reasons reported")
		void multiplePatternsSameText() {
			// Both SSN and credit card appear in the same message — both reasons must be reported
			RouterService.SensitivityResult r = router.detectSensitiveContent(
					"SSN 123-45-6789 and card 4111-1111-1111-1111");
			assertThat(r.reasons).containsExactlyInAnyOrder("SSN", "credit card");
		}
	}

	// ── classifyRequest ──────────────────────────────────────────────────────

	@Nested
	@DisplayName("classifyRequest")
	class ClassifyRequest {

		@Test
		@DisplayName("null mode defaults to 'ask' without NPE")
		void nullModeDefaultsToAsk() {
			// Null mode must not cause NPE — treated as default "ask" mode
			List<String> tags = router.classifyRequest("Tell me about forests", null);
			assertThat(tags).isNotEmpty();
		}

		@Test
		@DisplayName("unmatched message returns [general, ask]")
		void unmatchedMessageReturnsGeneralAsk() {
			// Message with no keyword matches falls back to the most generic tag set
			List<String> tags = router.classifyRequest("zzzyyyxxx — completely unrelated nonsense", "ask");
			assertThat(tags).containsExactlyInAnyOrder("general", "ask");
		}

		@Test
		@DisplayName("'bug' keyword maps to fix/debugging/coding")
		void bugKeywordMapsToCoding() {
			// "bug" is a keyword that signals debugging intent — router must tag for code-capable models
			List<String> tags = router.classifyRequest("There is a bug in my code", "ask");
			assertThat(tags).contains("fix", "debugging", "coding");
		}

		@Test
		@DisplayName("'build' mode always adds coding/build/project")
		void buildModeAddsCodingTags() {
			// "build" mode unconditionally adds coding tags regardless of message content
			List<String> tags = router.classifyRequest("Random message", "build");
			assertThat(tags).contains("coding", "build", "project");
		}

		@Test
		@DisplayName("medical keyword maps to medical/analysis")
		void medicalKeyword() {
			// Medical keywords must route to models with medical/analysis capabilities when available
			List<String> tags = router.classifyRequest("What is the diagnosis for this patient?", "ask");
			assertThat(tags).contains("medical", "analysis");
		}

		@Test
		@DisplayName("'fix' mode always adds fix/debugging/coding")
		void fixModeAddsTags() {
			// "fix" mode unconditionally adds fix/debugging/coding tags — code model required
			List<String> tags = router.classifyRequest("Anything", "fix");
			assertThat(tags).contains("fix", "debugging", "coding");
		}

		@Test
		@DisplayName("tags are deduplicated when keyword and mode both match")
		void tagsAreDeduplicated() {
			// When keyword and mode both add the same tag, the list must not contain duplicates
			List<String> tags = router.classifyRequest("debug my code", "debug");
			long unique = tags.stream().distinct().count();
			assertThat(unique).isEqualTo(tags.size());
		}
	}

	// ── routeModel ────────────────────────────────────────────────────────────

	@Nested
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("routeModel")
	class RouteModel {

		@BeforeEach
		void commonStubs() {
			// Stub common settings used by most routing tests
			when(databaseService.getSetting(eq("routerWeights"), any())).thenReturn(null);
			when(databaseService.getSetting(eq("localOnlyModes"), any())).thenReturn(null);
			when(databaseService.getSetting(eq("sensitivePatterns"), any())).thenReturn(null);
			when(databaseService.getSettingBool(eq("routerEnabled"), anyBoolean())).thenReturn(true);
			when(databaseService.getSettingBool(eq("allowApiModels"), anyBoolean())).thenReturn(false);
		}

		@Test
		@DisplayName("selects highest-scoring local model for clean message")
		void selectsHighestScoringModel() {
			// Stub: two local models with different scores
			ModelRecord fast = model("m-fast", "Fast Local", "local", "ollama", 90, 60, "coding");
			ModelRecord slow = model("m-slow", "Slow Local", "local", "ollama", 20, 95, "general");
			when(modelService.allowedModels(any())).thenReturn(Arrays.asList(fast, slow));

			// mode "ask" does not trigger local-only enforcement
			RouterService.RouteResult r = router.routeModel(adminUser(), "Write a function for me", "ask");
			// A model must be selected and neither privacy block applies here
			assertThat(r.selected).isNotNull();
			assertThat(r.candidates).hasSize(2);
			assertThat(r.privacyBlocked).isFalse();
		}

		@Test
		@DisplayName("privacy blocking eliminates non-local models when SSN present")
		void privacyBlockingEliminatesApiModels() {
			// Stub: one local model and one API model
			ModelRecord localModel = model("m-local", "Local", "local", "ollama", 80, 80, "general");
			ModelRecord apiModel  = model("m-api", "GPT-4", "external", "api", 90, 95, "general");
			when(modelService.allowedModels(any())).thenReturn(Arrays.asList(localModel, apiModel));

			// SSN in message must trigger privacy blocking
			RouterService.RouteResult r = router.routeModel(adminUser(), "My SSN is 123-45-6789", "ask");
			assertThat(r.privacyBlocked).isTrue();
			// SECURITY: only the local model must be selected — API model must be excluded
			assertThat(r.selected).isNotNull();
			assertThat(r.selected.id).isEqualTo("m-local");
			// API model must not appear in candidates list — security invariant
			assertThat(r.candidates).noneMatch(c -> "m-api".equals(c.get("id")));
		}

		@Test
		@DisplayName("'fix' mode triggers local-only enforcement by default")
		void fixModeTriggerLocalOnly() {
			// Stub: one local, one API model
			ModelRecord localModel = model("m-local", "Local", "local", "ollama", 80, 80, "coding");
			ModelRecord apiModel  = model("m-api", "GPT-4", "external", "api", 95, 99, "coding");
			when(modelService.allowedModels(any())).thenReturn(Arrays.asList(localModel, apiModel));

			// "fix" mode is in the localOnlyModes list by default — code stays local
			RouterService.RouteResult r = router.routeModel(adminUser(), "Fix this bug", "fix");
			assertThat(r.privacyBlocked).isTrue();
			// Local model must win — even though API model has higher scores
			assertThat(r.selected.id).isEqualTo("m-local");
		}

		@Test
		@DisplayName("empty allowed models list returns selected=null")
		void emptyAllowedModelsReturnsNull() {
			// Stub: user has no allowed models — must not throw
			when(modelService.allowedModels(any())).thenReturn(Collections.emptyList());

			RouterService.RouteResult r = router.routeModel(adminUser(), "Hello", "ask");
			// selected=null is the contract for "no model available" — callers check this
			assertThat(r.selected).isNull();
			assertThat(r.candidates).isEmpty();
		}

		@Test
		@DisplayName("router disabled — returns first model, score=0")
		void routerDisabledReturnsFirstModel() {
			// Stub: admin has disabled the intelligent router
			when(databaseService.getSettingBool(eq("routerEnabled"), anyBoolean())).thenReturn(false);
			ModelRecord m1 = model("m-1", "First", "local", "ollama", 50, 50, "general");
			ModelRecord m2 = model("m-2", "Second", "local", "ollama", 80, 80, "general");
			when(modelService.allowedModels(any())).thenReturn(Arrays.asList(m1, m2));

			RouterService.RouteResult r = router.routeModel(adminUser(), "Any message", "ask");
			// When router is disabled, first model in the list wins regardless of score
			assertThat(r.selected.id).isEqualTo("m-1");
			// All candidates must have score=0 when router is disabled
			assertThat(r.candidates).allMatch(c -> Integer.valueOf(0).equals(c.get("score")));
		}

		@Test
		@DisplayName("null message does not throw NPE — returns result gracefully")
		void nullMessageDoesNotThrow() {
			// Stub: one model available
			ModelRecord m = model("m-1", "Model", "local", "ollama", 50, 50, "general");
			when(modelService.allowedModels(any())).thenReturn(List.of(m));

			// Null message must not crash the router — treat as empty/general request
			RouterService.RouteResult r = router.routeModel(adminUser(), null, "ask");
			assertThat(r).isNotNull();
		}

		@Test
		@DisplayName("candidate breakdown map contains all expected keys")
		void candidateBreakdownContainsExpectedKeys() {
			// Stub: one model to inspect the breakdown shape
			ModelRecord m = model("m-1", "Model", "local", "ollama", 60, 70, "general");
			when(modelService.allowedModels(any())).thenReturn(List.of(m));

			RouterService.RouteResult r = router.routeModel(adminUser(), "Explain SOLID principles", "ask");
			assertThat(r.candidates).hasSize(1);
			// Breakdown map must have all scoring dimensions so the admin can see why a model was chosen
			@SuppressWarnings("unchecked")
			Map<String, Object> breakdown =
					(Map<String, Object>) r.candidates.get(0).get("breakdown");
			assertThat(breakdown).containsKey("capabilityMatch");
			assertThat(breakdown).containsKey("speedScore");
			assertThat(breakdown).containsKey("qualityScore");
			assertThat(breakdown).containsKey("privacyScore");
			assertThat(breakdown).containsKey("weightedTotal");
		}
	}
}
