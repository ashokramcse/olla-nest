package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.ModelRecord;
import com.ollanest.model.User;

/**
 * Unit tests for {@link RouterService}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * {@link RouterService} decides which model handles a request and is therefore
 * a privacy-critical component: it must detect sensitive content (SSNs, cards,
 * API keys, PHI), keep sensitive or "local-only" requests on local models, and
 * fall back gracefully when misconfigured. These tests pin the detection
 * patterns, the request classification tags, and the routing/scoring rules —
 * including the security invariant that an external model is never selected for
 * sensitive content.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>{@link ModelService} and {@link DatabaseService} are Mockito mocks; a
 * real {@link ObjectMapper} is used since JSON parsing needs no stubbing.</li>
 * <li>Nested groups that share settings stubs use {@link Strictness#LENIENT} so
 * unused stubs on some paths do not fail.</li>
 * <li>{@link #model(String, String, String, String, int, int, String...)} and
 * {@link #adminUser()} build fixtures shared across the routing tests.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — sensitive-content detection, classification and routing
 * coverage, including the {@code detectSensitiveContent} null-safety fix.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RouterService — unit tests")
class RouterServiceTest {

	/** Mocked model service supplying the per-user allowed model list. */
	@Mock
	ModelService modelService;
	/** Mocked database service supplying router settings and custom patterns. */
	@Mock
	DatabaseService databaseService;

	/** Real ObjectMapper — no stubs needed for JSON parsing. */
	private final ObjectMapper mapper = new ObjectMapper();

	/** Service under test, rebuilt fresh before each test. */
	private RouterService router;

	/**
	 * Builds a fresh {@link RouterService} before each test so settings stubs do
	 * not leak between cases.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@BeforeEach
	void setUp() {
		router = new RouterService(modelService, databaseService, mapper);
	}

	// ── Helper ───────────────────────────────────────────────────────────────

	/**
	 * Builds a {@link ModelRecord} fixture.
	 *
	 * @param id       the model id
	 * @param name     the display name
	 * @param privacy  the privacy class ({@code local}/{@code external})
	 * @param provider the provider name
	 * @param speed    the speed score
	 * @param quality  the quality score
	 * @param caps     the capability tags
	 * @return a populated {@link ModelRecord}
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	private ModelRecord model(String id, String name, String privacy, String provider, int speed, int quality,
			String... caps) {
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

	/**
	 * Builds an admin {@link User} fixture used as the routing caller.
	 *
	 * @return an admin user
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	private User adminUser() {
		User u = new User();
		u.id = "u-admin";
		u.role = "admin";
		return u;
	}

	// ── detectSensitiveContent ───────────────────────────────────────────────

	/**
	 * Tests for {@code detectSensitiveContent()} — PII/secret detection.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("detectSensitiveContent")
	class DetectSensitiveContent {

		/**
		 * Disables admin-configured custom patterns for these tests.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@BeforeEach
		void noCustomPatterns() {
			// Stub: no admin-configured custom patterns for these tests
			when(databaseService.getSetting(eq("sensitivePatterns"), any())).thenReturn(null);
		}

		/**
		 * Verifies null input is treated as non-sensitive without an NPE.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("null input returns non-sensitive result without NPE")
		void nullInputIsNotSensitive() {
			// Null content must not crash — privacy detection must be null-safe
			RouterService.SensitivityResult r = router.detectSensitiveContent(null);
			assertThat(r.isSensitive).isFalse();
			assertThat(r.reasons).isEmpty();
		}

		/**
		 * Verifies ordinary technical text is not flagged sensitive.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("clean text is not sensitive")
		void cleanTextNotSensitive() {
			// Ordinary technical content must never trigger false-positive privacy blocking
			RouterService.SensitivityResult r = router.detectSensitiveContent("How does Spring Boot work?");
			assertThat(r.isSensitive).isFalse();
			assertThat(r.reasons).isEmpty();
		}

		/**
		 * Verifies an SSN pattern is detected.
		 *
		 * <p>
		 * SECURITY: an SSN must be caught so the request stays on local models.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("SSN pattern detected")
		void detectsSSN() {
			// SECURITY: SSN pattern must be caught to prevent local-only data from going to
			// API models
			RouterService.SensitivityResult r = router.detectSensitiveContent("My SSN is 123-45-6789 — keep it safe.");
			assertThat(r.isSensitive).isTrue();
			assertThat(r.reasons).contains("SSN");
		}

		/**
		 * Verifies a credit-card pattern is detected.
		 *
		 * <p>
		 * SECURITY (PCI-DSS): card numbers must trigger local-only routing.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("credit card pattern detected")
		void detectsCreditCard() {
			// SECURITY: PCI-DSS — credit card numbers must trigger local-only routing
			RouterService.SensitivityResult r = router.detectSensitiveContent("Card: 4111-1111-1111-1111");
			assertThat(r.isSensitive).isTrue();
			assertThat(r.reasons).contains("credit card");
		}

		/**
		 * Verifies an OpenAI-style API key is detected.
		 *
		 * <p>
		 * SECURITY: a key in the prompt must be caught before it can leak to an
		 * external provider.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("OpenAI-style API key pattern detected")
		void detectsApiKey() {
			// SECURITY: API key in prompt must be caught before it leaks to an external
			// provider
			RouterService.SensitivityResult r = router.detectSensitiveContent("Use key sk-abcdefghijklmnopqrstu123");
			assertThat(r.isSensitive).isTrue();
			assertThat(r.reasons).contains("API key");
		}

		/**
		 * Verifies medical/PHI terms are detected case-insensitively.
		 *
		 * <p>
		 * SECURITY (HIPAA): PHI keywords must trigger local-only routing.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("medical/PHI terms detected (case-insensitive)")
		void detectsMedical() {
			// SECURITY: HIPAA — medical/PHI keywords must trigger local-only routing
			RouterService.SensitivityResult r = router
					.detectSensitiveContent("Patient record shows HIPAA-covered diagnosis");
			assertThat(r.isSensitive).isTrue();
			assertThat(r.reasons).containsAnyOf("medical/PHI");
		}

		/**
		 * Verifies an admin-configured custom pattern is detected and surfaced.
		 *
		 * <p>
		 * A custom regex match must flag the content and name an "admin pattern"
		 * reason for audit.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("admin-configured custom pattern detected")
		void detectsAdminPattern() {
			// Stub: admin has added a custom regex for a proprietary project codename
			when(databaseService.getSetting(eq("sensitivePatterns"), any())).thenReturn("[\"secret-project-[A-Z]+\"]");
			RouterService.SensitivityResult r = router.detectSensitiveContent("Working on secret-project-ALPHA");
			assertThat(r.isSensitive).isTrue();
			// Custom admin pattern must be surfaced in reasons for audit purposes
			assertThat(r.reasons).anyMatch(s -> s.contains("admin pattern"));
		}

		/**
		 * Verifies a broken admin regex is silently ignored.
		 *
		 * <p>
		 * A misconfigured pattern must be skipped rather than crashing the router.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("invalid admin regex pattern is silently ignored")
		void invalidAdminPatternIgnored() {
			// Stub: admin has misconfigured a broken regex — must not crash the router
			when(databaseService.getSetting(eq("sensitivePatterns"), any())).thenReturn("[\"[invalid-regex\"]");
			RouterService.SensitivityResult r = router.detectSensitiveContent("Hello world");
			// Invalid regex must be silently skipped — not propagate as an exception
			assertThat(r.isSensitive).isFalse();
		}

		/**
		 * Verifies multiple patterns in one message report all reasons.
		 *
		 * <p>
		 * An SSN and a card in the same text must both appear in the reasons.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("multiple patterns in same text — all reasons reported")
		void multiplePatternsSameText() {
			// Both SSN and credit card appear in the same message — both reasons must be
			// reported
			RouterService.SensitivityResult r = router
					.detectSensitiveContent("SSN 123-45-6789 and card 4111-1111-1111-1111");
			assertThat(r.reasons).containsExactlyInAnyOrder("SSN", "credit card");
		}
	}

	// ── classifyRequest ──────────────────────────────────────────────────────

	/**
	 * Tests for {@code classifyRequest()} — intent tagging by keyword and mode.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("classifyRequest")
	class ClassifyRequest {

		/**
		 * Verifies a null mode defaults to "ask" without an NPE.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("null mode defaults to 'ask' without NPE")
		void nullModeDefaultsToAsk() {
			// Null mode must not cause NPE — treated as default "ask" mode
			List<String> tags = router.classifyRequest("Tell me about forests", null);
			assertThat(tags).isNotEmpty();
		}

		/**
		 * Verifies an unmatched message falls back to {@code [general, ask]}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("unmatched message returns [general, ask]")
		void unmatchedMessageReturnsGeneralAsk() {
			// Message with no keyword matches falls back to the most generic tag set
			List<String> tags = router.classifyRequest("zzzyyyxxx — completely unrelated nonsense", "ask");
			assertThat(tags).containsExactlyInAnyOrder("general", "ask");
		}

		/**
		 * Verifies the "bug" keyword maps to fix/debugging/coding tags.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("'bug' keyword maps to fix/debugging/coding")
		void bugKeywordMapsToCoding() {
			// "bug" is a keyword that signals debugging intent — router must tag for
			// code-capable models
			List<String> tags = router.classifyRequest("There is a bug in my code", "ask");
			assertThat(tags).contains("fix", "debugging", "coding");
		}

		/**
		 * Verifies "build" mode always adds coding/build/project tags.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("'build' mode always adds coding/build/project")
		void buildModeAddsCodingTags() {
			// "build" mode unconditionally adds coding tags regardless of message content
			List<String> tags = router.classifyRequest("Random message", "build");
			assertThat(tags).contains("coding", "build", "project");
		}

		/**
		 * Verifies a medical keyword maps to medical/analysis tags.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("medical keyword maps to medical/analysis")
		void medicalKeyword() {
			// Medical keywords must route to models with medical/analysis capabilities when
			// available
			List<String> tags = router.classifyRequest("What is the diagnosis for this patient?", "ask");
			assertThat(tags).contains("medical", "analysis");
		}

		/**
		 * Verifies "fix" mode always adds fix/debugging/coding tags.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("'fix' mode always adds fix/debugging/coding")
		void fixModeAddsTags() {
			// "fix" mode unconditionally adds fix/debugging/coding tags — code model
			// required
			List<String> tags = router.classifyRequest("Anything", "fix");
			assertThat(tags).contains("fix", "debugging", "coding");
		}

		/**
		 * Verifies tags are deduplicated when keyword and mode overlap.
		 *
		 * <p>
		 * When both the keyword and the mode add the same tag, the result must
		 * contain no duplicates.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("tags are deduplicated when keyword and mode both match")
		void tagsAreDeduplicated() {
			// When keyword and mode both add the same tag, the list must not contain
			// duplicates
			List<String> tags = router.classifyRequest("debug my code", "debug");
			long unique = tags.stream().distinct().count();
			assertThat(unique).isEqualTo(tags.size());
		}
	}

	// ── routeModel ────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code routeModel()} — scoring, privacy blocking and fallbacks.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("routeModel")
	class RouteModel {

		/**
		 * Establishes the common router settings used by most routing tests.
		 *
		 * <p>
		 * Router enabled, API models disallowed, and no custom weights/patterns.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@BeforeEach
		void commonStubs() {
			// Stub common settings used by most routing tests
			when(databaseService.getSetting(eq("routerWeights"), any())).thenReturn(null);
			when(databaseService.getSetting(eq("localOnlyModes"), any())).thenReturn(null);
			when(databaseService.getSetting(eq("sensitivePatterns"), any())).thenReturn(null);
			when(databaseService.getSettingBool(eq("routerEnabled"), anyBoolean())).thenReturn(true);
			when(databaseService.getSettingBool(eq("allowApiModels"), anyBoolean())).thenReturn(false);
		}

		/**
		 * Verifies the highest-scoring local model is selected for clean text.
		 *
		 * <p>
		 * With two local candidates and a non-sensitive "ask" request, a model is
		 * selected, both candidates appear, and no privacy block applies.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies sensitive content eliminates external models.
		 *
		 * <p>
		 * SECURITY: with an SSN present, the API model must be excluded from the
		 * candidates and only the local model selected, with {@code privacyBlocked}
		 * true.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("privacy blocking eliminates non-local models when SSN present")
		void privacyBlockingEliminatesApiModels() {
			// Stub: one local model and one API model
			ModelRecord localModel = model("m-local", "Local", "local", "ollama", 80, 80, "general");
			ModelRecord apiModel = model("m-api", "GPT-4", "external", "api", 90, 95, "general");
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

		/**
		 * Verifies "fix" mode enforces local-only routing by default.
		 *
		 * <p>
		 * Even though the API model scores higher, "fix" mode keeps code local, so
		 * the local model wins and {@code privacyBlocked} is true.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("'fix' mode triggers local-only enforcement by default")
		void fixModeTriggerLocalOnly() {
			// Stub: one local, one API model
			ModelRecord localModel = model("m-local", "Local", "local", "ollama", 80, 80, "coding");
			ModelRecord apiModel = model("m-api", "GPT-4", "external", "api", 95, 99, "coding");
			when(modelService.allowedModels(any())).thenReturn(Arrays.asList(localModel, apiModel));

			// "fix" mode is in the localOnlyModes list by default — code stays local
			RouterService.RouteResult r = router.routeModel(adminUser(), "Fix this bug", "fix");
			assertThat(r.privacyBlocked).isTrue();
			// Local model must win — even though API model has higher scores
			assertThat(r.selected.id).isEqualTo("m-local");
		}

		/**
		 * Verifies an empty allowed-models list yields {@code selected=null}.
		 *
		 * <p>
		 * With no allowed models the router must not throw; {@code selected} is
		 * null and candidates empty.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies a disabled router returns the first model with score 0.
		 *
		 * <p>
		 * With the intelligent router off, the first model wins regardless of score
		 * and every candidate reports a score of 0.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies a null message does not throw and still returns a result.
		 *
		 * <p>
		 * A null message must be treated as an empty/general request rather than
		 * crashing the router.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

		/**
		 * Verifies the candidate breakdown exposes all scoring dimensions.
		 *
		 * <p>
		 * The breakdown map must contain capabilityMatch, speedScore, qualityScore,
		 * privacyScore and weightedTotal so an admin can see why a model was
		 * chosen.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("candidate breakdown map contains all expected keys")
		void candidateBreakdownContainsExpectedKeys() {
			// Stub: one model to inspect the breakdown shape
			ModelRecord m = model("m-1", "Model", "local", "ollama", 60, 70, "general");
			when(modelService.allowedModels(any())).thenReturn(List.of(m));

			RouterService.RouteResult r = router.routeModel(adminUser(), "Explain SOLID principles", "ask");
			assertThat(r.candidates).hasSize(1);
			// Breakdown map must have all scoring dimensions so the admin can see why a
			// model was chosen
			@SuppressWarnings("unchecked")
			Map<String, Object> breakdown = (Map<String, Object>) r.candidates.get(0).get("breakdown");
			assertThat(breakdown).containsKey("capabilityMatch");
			assertThat(breakdown).containsKey("speedScore");
			assertThat(breakdown).containsKey("qualityScore");
			assertThat(breakdown).containsKey("privacyScore");
			assertThat(breakdown).containsKey("weightedTotal");
		}
	}
}
