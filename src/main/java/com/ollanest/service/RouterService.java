package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.ModelRecord;
import com.ollanest.model.User;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Auto-router that selects the optimal LLM model for each user request.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest can have multiple LLM models available simultaneously — small fast
 * local models, larger reasoning models, specialist coding or vision models,
 * and external API models. Manually choosing the right model for every message
 * would be tedious and error-prone. This service automates that decision: it
 * classifies the user's intent into capability tags, scores every model the
 * user is permitted to access, and returns the highest-scoring model along with
 * a ranked list of all candidates and a human-readable explanation. It mirrors
 * the {@code src/services/router.js} logic from the prior Node.js
 * implementation.
 *
 * <h3>Design notes</h3>
 * <p>
 * The scoring formula is:
 * 
 * <pre>
 * score = capabilityMatch + specialistBonus + speed * speedWeight + quality * qualityWeight + privacyContribution
 * </pre>
 * 
 * Weights are read from the {@code routerWeights} setting (JSON map with keys
 * {@code speed}, {@code quality}, {@code privacy}); defaults are 0.3 / 0.5 /
 * 0.2.
 * <p>
 * Privacy enforcement is hard: when the message contains sensitive content
 * patterns (SSN, credit-card numbers, API keys, medical/PHI terms) or the mode
 * is in the {@code localOnlyModes} list, all non-local models are removed from
 * the candidate set before scoring. External models are not merely downscored —
 * they are eliminated.
 * <p>
 * Admin-configurable regex patterns are loaded from the
 * {@code sensitivePatterns} setting and applied in addition to the built-in
 * patterns.
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.0</b> — initial Java Spring Boot migration</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.0
 */
@Service
public class RouterService {

	/** Service for reading the list of models the user is allowed to access. */
	private final ModelService modelService;

	/**
	 * Service for reading persisted settings such as router weights and local-only
	 * modes.
	 */
	private final DatabaseService databaseService;

	/** Jackson mapper for deserialising JSON setting values. */
	private final ObjectMapper mapper;

	/**
	 * Constructs the router with its required collaborators.
	 *
	 * @param modelService    model access and metadata service
	 * @param databaseService key-value settings service
	 * @param mapper          shared Jackson {@link ObjectMapper}
	 * @since v2026.1.0
	 */
	public RouterService(ModelService modelService, DatabaseService databaseService, ObjectMapper mapper) {
		this.modelService = modelService;
		this.databaseService = databaseService;
		this.mapper = mapper;
	}

	// ── Inner types ─────────────────────────────────────────────────────────

	/**
	 * Carries the result of a {@link #detectSensitiveContent(String)} call.
	 */
	public static class SensitivityResult {

		/** {@code true} when one or more sensitive patterns were matched. */
		public boolean isSensitive;

		/** Human-readable labels for each matched sensitive-data category. */
		public List<String> reasons;

		/**
		 * Constructs a result with the given sensitivity flag and reason list.
		 *
		 * @param isSensitive {@code true} when sensitive content was detected
		 * @param reasons     list of matched category labels (may be empty)
		 * @since v2026.1.0
		 */
		public SensitivityResult(boolean isSensitive, List<String> reasons) {
			this.isSensitive = isSensitive;
			this.reasons = reasons;
		}
	}

	/**
	 * Carries the full result of a {@link #routeModel(User, String, String)} call.
	 *
	 * @since v2026.1.0
	 */
	public static class RouteResult {

		/**
		 * The model selected for this request; {@code null} if no suitable model was
		 * found.
		 */
		public ModelRecord selected;

		/**
		 * Capability tags inferred from the message and mode by
		 * {@link #classifyRequest}.
		 */
		public List<String> tags;

		/**
		 * All scored candidate models in descending score order; each map contains
		 * {@code id}, {@code name}, {@code score}, {@code matches}, and
		 * {@code breakdown}.
		 */
		public List<Map<String, Object>> candidates;

		/** Human-readable explanation of the routing decision. */
		public String reason;

		/**
		 * {@code true} when sensitive content or a local-only mode forced the candidate
		 * list to be restricted to local models only.
		 */
		public boolean privacyBlocked;

		/** Sensitive-data categories detected in the message (empty when clean). */
		public List<String> sensitiveReasons;
	}

	// ── Built-in sensitive-content patterns ─────────────────────────────────

	/** Matches US Social Security Number format {@code NNN-NN-NNNN}. */
	private static final Pattern SSN_PAT = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

	/**
	 * Matches 16-digit credit/debit card numbers with optional spaces or hyphens.
	 */
	private static final Pattern CC_PAT = Pattern.compile("\\b\\d{4}[- ]\\d{4}[- ]\\d{4}[- ]\\d{4}\\b");

	/** Matches OpenAI-style API keys beginning with {@code sk-}. */
	private static final Pattern APIKEY_PAT = Pattern.compile("sk-[a-zA-Z0-9]{20,}");

	/**
	 * Matches common medical/PHI terms: diagnosis, prescription, patient record,
	 * PHI, HIPAA.
	 */
	private static final Pattern MEDICAL_PAT = Pattern
			.compile("(?i)diagnosis|prescription|patient\\s+record|PHI|HIPAA");

	// ── Public API ───────────────────────────────────────────────────────────

	/**
	 * Scans a message for sensitive-data patterns and returns a structured result.
	 *
	 * <p>
	 * Applies four built-in regex patterns (SSN, credit card, API key, medical/PHI)
	 * plus any admin-configured patterns stored in the {@code sensitivePatterns}
	 * setting as a JSON array of regex strings. Invalid admin patterns are silently
	 * ignored.
	 *
	 * @param text the message text to scan; must not be {@code null}
	 * @return a {@link SensitivityResult} indicating whether sensitive content was
	 *         found and listing which categories were matched
	 * @since v2026.1.0
	 */
	public SensitivityResult detectSensitiveContent(String text) {
		List<String> reasons = new ArrayList<>();
		if (SSN_PAT.matcher(text).find())
			reasons.add("SSN");
		if (CC_PAT.matcher(text).find())
			reasons.add("credit card");
		if (APIKEY_PAT.matcher(text).find())
			reasons.add("API key");
		if (MEDICAL_PAT.matcher(text).find())
			reasons.add("medical/PHI");

		// Admin custom patterns from settings
		try {
			String patternsJson = databaseService.getSetting("sensitivePatterns", null);
			if (patternsJson != null && !patternsJson.isBlank()) {
				List<String> adminPatterns = mapper.readValue(patternsJson, new TypeReference<List<String>>() {
				});
				for (String pat : adminPatterns) {
					try {
						if (Pattern.compile(pat).matcher(text).find())
							reasons.add("admin pattern: " + pat);
					} catch (Exception ignored) {
					}
				}
			}
		} catch (Exception ignored) {
		}

		return new SensitivityResult(!reasons.isEmpty(), reasons);
	}

	/**
	 * Infers a set of capability tags from a message and an optional UI mode
	 * string.
	 *
	 * <p>
	 * Applies keyword pattern matching to the lower-cased message text and maps
	 * recognised phrases to capability tags (e.g. {@code "bug"} or {@code "error"}
	 * → {@code ["fix", "debugging", "coding"]}). The {@code mode} parameter can
	 * override or supplement the text-based classification — for example, mode
	 * {@code "build"} always adds {@code ["coding", "build", "project"]} regardless
	 * of message content. Returns {@code ["general", "ask"]} when no patterns
	 * match.
	 *
	 * @param message the user's message text; must not be {@code null}
	 * @param mode    optional UI mode string (e.g. {@code "build"}, {@code "fix"},
	 *                {@code "ask"}); {@code null} is treated as {@code "ask"}
	 * @return ordered, deduplicated list of capability tag strings
	 * @since v2026.1.0
	 */
	public List<String> classifyRequest(String message, String mode) {
		if (mode == null)
			mode = "ask";
		String text = message.toLowerCase();
		Set<String> tags = new LinkedHashSet<>();

		if (text.matches(".*?(medical|health|doctor|patient|clinical|medicine|diagnosis|symptom).*"))
			tags.addAll(Arrays.asList("medical", "analysis"));
		if (text.matches(".*?(ocr|image|scan|receipt|invoice|extract text|read this image|document image).*"))
			tags.addAll(Arrays.asList("ocr", "vision", "document"));
		if (text.matches(".*?(bug|error|fix|stack|trace|failing|broken|crash|exception).*") || "fix".equals(mode))
			tags.addAll(Arrays.asList("fix", "debugging", "coding"));
		if (text.matches(".*?(debug|root cause|why is|not working|wrong output|unexpected).*") || "debug".equals(mode))
			tags.addAll(Arrays.asList("debugging", "coding", "fix"));
		if (text.matches(
				".*?(code|repo|component|api|server|frontend|backend|function|build|generate|create|write a).*")
				|| "build".equals(mode))
			tags.addAll(Arrays.asList("coding", "build", "project"));
		if (text.matches(".*?(test|unit test|spec|jest|pytest|coverage|mock|assertion).*") || "test".equals(mode))
			tags.addAll(Arrays.asList("coding", "build", "review"));
		if (text.matches(".*?(readme|documentation|doc|comment|jsdoc|api doc|usage|changelog).*")
				|| "docs".equals(mode))
			tags.addAll(Arrays.asList("writing", "summary", "review"));
		if (text.matches(".*?(plan|architect|design|schema|structure|roadmap|stack|phase|milestone|breakdown).*")
				|| "plan".equals(mode))
			tags.addAll(Arrays.asList("review", "analysis", "project"));
		if (text.matches(".*?(review|risk|issue|security|quality|audit).*") || "review".equals(mode))
			tags.addAll(Arrays.asList("review", "analysis"));
		if (text.matches(".*?(write|email|pitch|copy|summarize|summary).*"))
			tags.addAll(Arrays.asList("writing", "summary"));
		if (text.matches(".*?(explain|teach|learn|understand|what is|how does).*") || "learn".equals(mode))
			tags.addAll(Arrays.asList("learn", "general"));

		if (tags.isEmpty())
			tags.addAll(Arrays.asList("general", "ask"));
		return new ArrayList<>(tags);
	}

	/**
	 * Selects the best model for a user request and returns a fully scored
	 * candidate list.
	 *
	 * <p>
	 * The routing algorithm:
	 * <ol>
	 * <li>Load all models the user is permitted to access via
	 * {@link ModelService#allowedModels}.</li>
	 * <li>Classify the request into capability tags using
	 * {@link #classifyRequest}.</li>
	 * <li>Detect sensitive content; if found (or if the mode is in
	 * {@code localOnlyModes}), restrict candidates to models with
	 * {@code privacy = "local"}.</li>
	 * <li>Score each remaining candidate using capability match, specialist bonus,
	 * weighted speed, weighted quality, and a privacy contribution.</li>
	 * <li>Return the highest-scoring model in {@link RouteResult#selected}.</li>
	 * </ol>
	 *
	 * <p>
	 * When the auto-router is disabled (setting {@code routerEnabled = false}) the
	 * first model in the allowed list is selected with a score of 0 and the reason
	 * explains that the router is off.
	 *
	 * @param user    the authenticated user; used to filter the model access list
	 * @param message the user's message text, used for classification and
	 *                sensitivity detection
	 * @param mode    optional UI mode string; may be {@code null}
	 * @return a {@link RouteResult} with the selected model, all scored candidates,
	 *         a reason string, and privacy-enforcement metadata
	 * @since v2026.1.0
	 */
	public RouteResult routeModel(User user, String message, String mode) {
		List<ModelRecord> candidates = modelService.allowedModels(user);
		List<String> tags = classifyRequest(message, mode);

		// Router weights
		Map<String, Object> weights = safeJsonMap(databaseService.getSetting("routerWeights", null));
		double speedW = weights.containsKey("speed") ? ((Number) weights.get("speed")).doubleValue() : 0.3;
		double qualityW = weights.containsKey("quality") ? ((Number) weights.get("quality")).doubleValue() : 0.5;
		double privacyW = weights.containsKey("privacy") ? ((Number) weights.get("privacy")).doubleValue() : 0.2;

		// Local-only modes
		List<String> localOnlyModes = safeJsonList(databaseService.getSetting("localOnlyModes", null));
		if (localOnlyModes == null || localOnlyModes.isEmpty())
			localOnlyModes = Arrays.asList("build", "fix");

		SensitivityResult sensitivityResult = detectSensitiveContent(message);
		boolean privacyBlocked = sensitivityResult.isSensitive || localOnlyModes.contains(mode);

		if (privacyBlocked) {
			List<ModelRecord> filtered = new ArrayList<>();
			for (ModelRecord m : candidates)
				if ("local".equals(m.privacy))
					filtered.add(m);
			candidates = filtered;
		}

		RouteResult result = new RouteResult();
		result.tags = tags;
		result.privacyBlocked = privacyBlocked;
		result.sensitiveReasons = sensitivityResult.reasons;

		boolean routerEnabled = databaseService.getSettingBool("routerEnabled", true);
		if (!routerEnabled) {
			result.selected = candidates.isEmpty() ? null : candidates.get(0);
			result.candidates = new ArrayList<>();
			for (ModelRecord m : candidates) {
				Map<String, Object> c = new LinkedHashMap<>();
				c.put("id", m.id);
				c.put("name", m.name);
				c.put("score", 0);
				result.candidates.add(c);
			}
			result.reason = "Auto Router is disabled by admin, so the first approved model was selected.";
			return result;
		}

		// Score candidates
		List<ScoredModel> scored = new ArrayList<>();
		for (ModelRecord model : candidates) {
			List<String> matches = new ArrayList<>();
			for (String cap : model.capabilities)
				if (tags.contains(cap))
					matches.add(cap);
			int capabilityScore = matches.size() * 35;
			int specialistScore = 0;
			List<String> specialistTags = Arrays.asList("medical", "ocr", "vision", "coding");
			for (String st : specialistTags) {
				if (tags.contains(st) && model.capabilities.contains(st)) {
					specialistScore = 45;
					break;
				}
			}
			boolean allowApi = databaseService.getSettingBool("allowApiModels", false);
			double privacyScore = "local".equals(model.privacy) ? 100 * privacyW : (allowApi ? 0 : -100);
			double score = capabilityScore + specialistScore + model.speedScore * speedW + model.qualityScore * qualityW
					+ privacyScore;
			scored.add(new ScoredModel(model, (int) Math.round(score), matches, capabilityScore,
					model.speedScore * speedW, model.qualityScore * qualityW, privacyScore, score));
		}
		scored.sort((a, b) -> b.score - a.score);

		result.selected = scored.isEmpty() ? null : scored.get(0).model;
		result.candidates = new ArrayList<>();
		for (ScoredModel sm : scored) {
			Map<String, Object> c = new LinkedHashMap<>();
			c.put("id", sm.model.id);
			c.put("name", sm.model.name);
			c.put("score", sm.score);
			c.put("matches", sm.matches);
			Map<String, Object> bd = new LinkedHashMap<>();
			bd.put("capabilityMatch", sm.capabilityScore);
			bd.put("speedScore", sm.speedScore);
			bd.put("qualityScore", sm.qualityScore);
			bd.put("privacyScore", sm.privacyScore);
			bd.put("weightedTotal", sm.total);
			c.put("breakdown", bd);
			result.candidates.add(c);
		}
		result.reason = result.selected != null
				? "Selected " + result.selected.name
						+ " by matching request capabilities, speed, quality, privacy, and the user's approved access."
				: "No approved active model is available for this user.";
		return result;
	}

	// ── Private helpers ─────────────────────────────────────────────────────

	/**
	 * Internal holder combining a {@link ModelRecord} with its computed routing
	 * scores.
	 */
	private static class ScoredModel {

		/** The model record this score belongs to. */
		ModelRecord model;

		/** Final rounded composite score used for sorting. */
		int score;

		/** Capability tag names that matched between the request and the model. */
		List<String> matches;

		/** Raw capability-match contribution to the score. */
		int capabilityScore;

		/** Weighted speed contribution. */
		double speedScore;

		/** Weighted quality contribution. */
		double qualityScore;

		/** Privacy contribution (positive for local, zero or negative for external). */
		double privacyScore;

		/** Unrounded total composite score. */
		double total;

		/**
		 * Constructs a fully populated scored-model holder.
		 *
		 * @param model   the model record
		 * @param score   rounded composite score
		 * @param matches matched capability tags
		 * @param cap     capability-match sub-score
		 * @param sp      weighted speed sub-score
		 * @param qu      weighted quality sub-score
		 * @param pr      privacy contribution
		 * @param total   unrounded total
		 */
		ScoredModel(ModelRecord model, int score, List<String> matches, int cap, double sp, double qu, double pr,
				double total) {
			this.model = model;
			this.score = score;
			this.matches = matches;
			this.capabilityScore = cap;
			this.speedScore = sp;
			this.qualityScore = qu;
			this.privacyScore = pr;
			this.total = total;
		}
	}

	/**
	 * Safely deserialises a JSON string into a {@link Map}, returning an empty map
	 * on failure.
	 *
	 * @param json JSON object string; {@code null} or blank returns an empty map
	 * @return the deserialised map, or an empty {@link LinkedHashMap} on any error
	 * @since v2026.1.0
	 */
	private Map<String, Object> safeJsonMap(String json) {
		try {
			if (json == null || json.isBlank())
				return new LinkedHashMap<>();
			return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
			});
		} catch (Exception e) {
			return new LinkedHashMap<>();
		}
	}

	/**
	 * Safely deserialises a JSON string into a {@link List} of strings, returning
	 * an empty list on failure.
	 *
	 * @param json JSON array string; {@code null} or blank returns an empty list
	 * @return the deserialised list, or an empty {@link ArrayList} on any error
	 * @since v2026.1.0
	 */
	private List<String> safeJsonList(String json) {
		try {
			if (json == null || json.isBlank())
				return new ArrayList<>();
			return mapper.readValue(json, new TypeReference<List<String>>() {
			});
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}
}
