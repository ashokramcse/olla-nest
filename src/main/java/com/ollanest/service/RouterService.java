package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.ModelRecord;
import com.ollanest.model.User;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Auto Router: classifyRequest, routeModel, detectSensitiveContent.
 * Mirrors src/services/router.js logic exactly.
 */
@Service
public class RouterService {

    private final ModelService modelService;
    private final DatabaseService databaseService;
    private final ObjectMapper mapper;

    public RouterService(ModelService modelService, DatabaseService databaseService, ObjectMapper mapper) {
        this.modelService = modelService;
        this.databaseService = databaseService;
        this.mapper = mapper;
    }

    public static class SensitivityResult {
        public boolean isSensitive;
        public List<String> reasons;
        public SensitivityResult(boolean isSensitive, List<String> reasons) {
            this.isSensitive = isSensitive;
            this.reasons = reasons;
        }
    }

    public static class RouteResult {
        public ModelRecord selected;
        public List<String> tags;
        public List<Map<String, Object>> candidates;
        public String reason;
        public boolean privacyBlocked;
        public List<String> sensitiveReasons;
    }

    private static final Pattern SSN_PAT = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CC_PAT = Pattern.compile("\\b\\d{4}[- ]\\d{4}[- ]\\d{4}[- ]\\d{4}\\b");
    private static final Pattern APIKEY_PAT = Pattern.compile("sk-[a-zA-Z0-9]{20,}");
    private static final Pattern MEDICAL_PAT = Pattern.compile("(?i)diagnosis|prescription|patient\\s+record|PHI|HIPAA");

    public SensitivityResult detectSensitiveContent(String text) {
        List<String> reasons = new ArrayList<>();
        if (SSN_PAT.matcher(text).find()) reasons.add("SSN");
        if (CC_PAT.matcher(text).find()) reasons.add("credit card");
        if (APIKEY_PAT.matcher(text).find()) reasons.add("API key");
        if (MEDICAL_PAT.matcher(text).find()) reasons.add("medical/PHI");

        // Admin custom patterns from settings
        try {
            String patternsJson = databaseService.getSetting("sensitivePatterns", null);
            if (patternsJson != null && !patternsJson.isBlank()) {
                List<String> adminPatterns = mapper.readValue(patternsJson, new TypeReference<List<String>>() {});
                for (String pat : adminPatterns) {
                    try {
                        if (Pattern.compile(pat).matcher(text).find()) reasons.add("admin pattern: " + pat);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        return new SensitivityResult(!reasons.isEmpty(), reasons);
    }

    public List<String> classifyRequest(String message, String mode) {
        if (mode == null) mode = "ask";
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
        if (text.matches(".*?(code|repo|component|api|server|frontend|backend|function|build|generate|create|write a).*") || "build".equals(mode))
            tags.addAll(Arrays.asList("coding", "build", "project"));
        if (text.matches(".*?(test|unit test|spec|jest|pytest|coverage|mock|assertion).*") || "test".equals(mode))
            tags.addAll(Arrays.asList("coding", "build", "review"));
        if (text.matches(".*?(readme|documentation|doc|comment|jsdoc|api doc|usage|changelog).*") || "docs".equals(mode))
            tags.addAll(Arrays.asList("writing", "summary", "review"));
        if (text.matches(".*?(plan|architect|design|schema|structure|roadmap|stack|phase|milestone|breakdown).*") || "plan".equals(mode))
            tags.addAll(Arrays.asList("review", "analysis", "project"));
        if (text.matches(".*?(review|risk|issue|security|quality|audit).*") || "review".equals(mode))
            tags.addAll(Arrays.asList("review", "analysis"));
        if (text.matches(".*?(write|email|pitch|copy|summarize|summary).*"))
            tags.addAll(Arrays.asList("writing", "summary"));
        if (text.matches(".*?(explain|teach|learn|understand|what is|how does).*") || "learn".equals(mode))
            tags.addAll(Arrays.asList("learn", "general"));

        if (tags.isEmpty()) tags.addAll(Arrays.asList("general", "ask"));
        return new ArrayList<>(tags);
    }

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
        if (localOnlyModes == null || localOnlyModes.isEmpty()) localOnlyModes = Arrays.asList("build", "fix");

        SensitivityResult sensitivityResult = detectSensitiveContent(message);
        boolean privacyBlocked = sensitivityResult.isSensitive || localOnlyModes.contains(mode);

        if (privacyBlocked) {
            List<ModelRecord> filtered = new ArrayList<>();
            for (ModelRecord m : candidates) if ("local".equals(m.privacy)) filtered.add(m);
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
                c.put("id", m.id); c.put("name", m.name); c.put("score", 0);
                result.candidates.add(c);
            }
            result.reason = "Auto Router is disabled by admin, so the first approved model was selected.";
            return result;
        }

        // Score candidates
        List<ScoredModel> scored = new ArrayList<>();
        for (ModelRecord model : candidates) {
            List<String> matches = new ArrayList<>();
            for (String cap : model.capabilities) if (tags.contains(cap)) matches.add(cap);
            int capabilityScore = matches.size() * 35;
            int specialistScore = 0;
            List<String> specialistTags = Arrays.asList("medical", "ocr", "vision", "coding");
            for (String st : specialistTags) {
                if (tags.contains(st) && model.capabilities.contains(st)) { specialistScore = 45; break; }
            }
            boolean allowApi = databaseService.getSettingBool("allowApiModels", false);
            double privacyScore = "local".equals(model.privacy) ? 100 * privacyW : (allowApi ? 0 : -100);
            double score = capabilityScore + specialistScore + model.speedScore * speedW + model.qualityScore * qualityW + privacyScore;
            scored.add(new ScoredModel(model, (int) Math.round(score), matches, capabilityScore, model.speedScore * speedW, model.qualityScore * qualityW, privacyScore, score));
        }
        scored.sort((a, b) -> b.score - a.score);

        result.selected = scored.isEmpty() ? null : scored.get(0).model;
        result.candidates = new ArrayList<>();
        for (ScoredModel sm : scored) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", sm.model.id); c.put("name", sm.model.name); c.put("score", sm.score);
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
            ? "Selected " + result.selected.name + " by matching request capabilities, speed, quality, privacy, and the user's approved access."
            : "No approved active model is available for this user.";
        return result;
    }

    private static class ScoredModel {
        ModelRecord model; int score; List<String> matches;
        int capabilityScore; double speedScore, qualityScore, privacyScore, total;
        ScoredModel(ModelRecord model, int score, List<String> matches, int cap, double sp, double qu, double pr, double total) {
            this.model = model; this.score = score; this.matches = matches;
            this.capabilityScore = cap; this.speedScore = sp; this.qualityScore = qu;
            this.privacyScore = pr; this.total = total;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeJsonMap(String json) {
        try {
            if (json == null || json.isBlank()) return new LinkedHashMap<>();
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private List<String> safeJsonList(String json) {
        try {
            if (json == null || json.isBlank()) return new ArrayList<>();
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
