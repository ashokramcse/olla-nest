package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deep Research service — multi-step agentic research pipeline.
 *
 * <p>Steps:
 * <ol>
 *   <li>Plan — ask the LLM to decompose the query into 3–5 sub-questions</li>
 *   <li>Search — for each sub-question, run web search + RAG retrieval</li>
 *   <li>Synthesize — ask the LLM to write a comprehensive report with all context</li>
 * </ol>
 *
 * <p>Each step emits SSE events of type {@code research_step} so the frontend
 * can show a live progress card above the streaming synthesis.
 */
@Service
public class DeepResearchService {

    private static final Logger log = LoggerFactory.getLogger(DeepResearchService.class);

    private final ProviderService providerService;
    private final RouterService routerService;
    private final WebSearchService webSearchService;
    private final RagService ragService;
    private final ObjectMapper mapper;

    public DeepResearchService(ProviderService providerService,
                                RouterService routerService,
                                WebSearchService webSearchService,
                                RagService ragService,
                                ObjectMapper mapper) {
        this.providerService = providerService;
        this.routerService   = routerService;
        this.webSearchService = webSearchService;
        this.ragService       = ragService;
        this.mapper           = mapper;
    }

    public void executeResearch(String query, User user, SseEmitter emitter) {
        try {
            RouterService.RouteResult route = routerService.routeModel(user, query, "ask");
            Map<String, Object> provider = providerService.resolveProvider(route);

            // ── Step 1: Plan ────────────────────────────────────────────────
            emit(emitter, Map.of("type", "research_step", "step", "plan", "status", "running",
                    "msg", "Analysing query and planning sub-questions…"));

            List<String> subQuestions = planResearch(query, route, provider);

            emit(emitter, Map.of("type", "research_step", "step", "plan", "status", "done",
                    "subQuestions", subQuestions));

            // ── Step 2: Search ──────────────────────────────────────────────
            List<String> allContext = new ArrayList<>();
            for (int i = 0; i < subQuestions.size(); i++) {
                String sq = subQuestions.get(i);
                emit(emitter, Map.of("type", "research_step", "step", "search", "index", i,
                        "query", sq, "status", "running"));

                // Web search
                List<WebSearchService.SearchResult> webResults = webSearchService.search(sq, 3);
                for (WebSearchService.SearchResult r : webResults)
                    allContext.add("**" + r.title() + "**\n" + r.snippet() + "\nSource: " + r.url());

                // RAG retrieval
                String ragCtx = ragService.buildRagContext(sq, user.id);
                if (!ragCtx.isBlank()) allContext.add(ragCtx);

                emit(emitter, Map.of("type", "research_step", "step", "search", "index", i,
                        "query", sq, "status", "done",
                        "sources", webResults.size() + (ragCtx.isBlank() ? 0 : 1)));
            }

            // ── Step 3: Synthesise ──────────────────────────────────────────
            emit(emitter, Map.of("type", "research_step", "step", "synthesize",
                    "status", "running", "msg", "Writing comprehensive report…"));

            // Emit an empty assistant message start
            emit(emitter, Map.of("type", "token", "content", ""));

            String systemPrompt = buildResearchSystemPrompt(query, allContext);
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user",   "content", "Write a comprehensive, well-structured research report answering: " + query)
            );

            int[] tokenCount = {0};
            providerService.callProviderStream(provider,
                    route.selected != null ? route.selected.model : "llama3.2:3b",
                    messages,
                    token -> {
                        try { emitter.send(SseEmitter.event().data("{\"type\":\"token\",\"content\":" + mapper.writeValueAsString(token) + "}")); }
                        catch (Exception ignore) {}
                    },
                    tokens -> tokenCount[0] = tokens);

            emit(emitter, Map.of("type", "research_step", "step", "synthesize", "status", "done"));
            emit(emitter, Map.of("type", "done", "tokensUsed", tokenCount[0]));
            emitter.complete();

        } catch (Exception e) {
            log.error("[research] failed: {}", e.getMessage());
            try {
                emit(emitter, Map.of("type", "error", "message", e.getMessage()));
                emitter.complete();
            } catch (Exception ex) { emitter.completeWithError(ex); }
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private List<String> planResearch(String query, RouterService.RouteResult route, Map<String, Object> provider) {
        try {
            String planningPrompt = """
                    You are a research planning assistant.
                    Given the research query below, decompose it into 3 to 5 specific sub-questions
                    that together would fully answer it. Return ONLY a JSON array of strings.
                    Example: ["sub-question 1", "sub-question 2", "sub-question 3"]

                    Query: """ + query;

            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", planningPrompt));

            String modelRef = route.selected != null ? route.selected.model : "llama3.2:3b";
            ProviderService.ProviderResult result = providerService.callProvider(
                    provider, modelRef, messages, 30_000);

            // Parse JSON array from LLM response
            String content = result.content.trim();
            int start = content.indexOf('[');
            int end   = content.lastIndexOf(']');
            if (start >= 0 && end > start) {
                com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(content.substring(start, end + 1));
                List<String> questions = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode n : arr) questions.add(n.asText());
                if (!questions.isEmpty()) return questions;
            }
        } catch (Exception e) {
            log.warn("[research] plan step failed, falling back to 3 sub-questions: {}", e.getMessage());
        }
        // Fallback: return three generic sub-questions
        return List.of(query, "Background and context: " + query, "Key findings and implications: " + query);
    }

    private String buildResearchSystemPrompt(String query, List<String> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert research analyst.\n");
        sb.append("Using ONLY the sources provided below, write a comprehensive, well-structured report answering the research query.\n");
        sb.append("Include clear sections with headings. Cite sources inline as [Source N].\n\n");
        sb.append("RESEARCH QUERY: ").append(query).append("\n\n");
        sb.append("SOURCES:\n");
        for (int i = 0; i < context.size(); i++) {
            sb.append("[Source ").append(i + 1).append("]\n").append(context.get(i)).append("\n\n");
        }
        return sb.toString();
    }

    private void emit(SseEmitter emitter, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.warn("[research] emit failed: {}", e.getMessage());
        }
    }
}
