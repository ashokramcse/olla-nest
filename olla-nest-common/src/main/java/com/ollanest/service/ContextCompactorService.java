package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Auto-compacts conversation history when approaching the context window limit.
 *
 * When the estimated token count of a session's messages exceeds
 * {@value #COMPACT_THRESHOLD} of the model's context window, the oldest
 * messages are summarized via an LLM call into a structured Cursor-style
 * summary. The summary is inserted as a hidden {@code system} role message
 * and the summarized messages are removed from the active context. Both the
 * original and the summary are kept in the DB for the record, but only the
 * summary is sent to the LLM on subsequent calls.
 */
@Service
public class ContextCompactorService {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactorService.class);

    /** Trigger compaction when context reaches this fraction of the window. */
    private static final double COMPACT_THRESHOLD = 0.85;

    /** Keep this many recent messages untouched — only summarize older ones. */
    private static final int KEEP_RECENT = 6;

    /** Maximum model context size assumed when the actual size is unknown. */
    private static final int DEFAULT_CONTEXT = 8192;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper mapper;
    private final DatabaseService databaseService;

    /**
     * Constructs a {@code ContextCompactorService} with the required dependencies.
     *
     * @param mapper          shared Jackson mapper for JSON operations
     * @param databaseService application settings service used to read provider config
     * @since v2026.2.1
     */
    public ContextCompactorService(ObjectMapper mapper, DatabaseService databaseService) {
        this.mapper = mapper;
        this.databaseService = databaseService;
    }

    /** Estimate token count: ~4 chars per token heuristic. */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 4);
    }

    /**
     * Check whether the given message list needs compaction given a context window size.
     *
     * @param messages    list of {role, content} maps
     * @param contextSize model context window token count; 0 = use default
     */
    public boolean needsCompaction(List<Map<String, Object>> messages, int contextSize) {
        if (contextSize <= 0) contextSize = DEFAULT_CONTEXT;
        int total = 0;
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            total += estimateTokens(content != null ? content.toString() : "");
        }
        return total > contextSize * COMPACT_THRESHOLD;
    }

    /**
     * Compact the given message list. Returns a new list where older messages have
     * been replaced with a single hidden summary message.
     *
     * @param messages    full message history
     * @param contextSize model context window; 0 = use default
     * @param ollamaUrl   LLM endpoint base URL for the summarisation call
     * @param model       model name to use for summarisation
     */
    public List<Map<String, Object>> compact(List<Map<String, Object>> messages,
            int contextSize, String ollamaUrl, String model) {

        if (messages.size() <= KEEP_RECENT + 2) return messages;

        int toSummarize = messages.size() - KEEP_RECENT;
        List<Map<String, Object>> toCompact = new ArrayList<>(messages.subList(0, toSummarize));
        List<Map<String, Object>> toKeep = new ArrayList<>(messages.subList(toSummarize, messages.size()));

        String summary = summarize(toCompact, ollamaUrl, model);

        List<Map<String, Object>> result = new ArrayList<>();
        // Insert hidden summary as first "system" message
        result.add(Map.of(
                "role", "system",
                "content", summary,
                "metadata", Map.of("hidden", false, "compaction_summary", true)
        ));
        result.addAll(toKeep);
        return result;
    }

    private String summarize(List<Map<String, Object>> messages, String ollamaUrl, String model) {
        try {
            StringBuilder transcript = new StringBuilder();
            for (Map<String, Object> msg : messages) {
                String role = msg.getOrDefault("role", "user").toString();
                String content = msg.getOrDefault("content", "").toString();
                transcript.append(role.toUpperCase()).append(": ").append(content).append("\n\n");
            }

            String systemPrompt = """
                    You are summarizing a conversation to preserve context after compaction. Produce a structured summary.

                    ## Conversation Summary
                    **Turns summarized:** %d

                    ### User Goal
                    One sentence describing what the user is trying to accomplish.

                    ### What Was Done
                    - Bullet points of completed actions, decisions made, and key outputs
                    - Include specific file paths, function names, variable names, and config values
                    - Note any errors encountered and how they were resolved

                    ### Current State
                    What is the current state? What was the last thing discussed?

                    ### Pending / Next Steps
                    - What remains to be done
                    - Any open questions or blockers

                    ### Key Context
                    - Important constraints, preferences, or decisions that must not be forgotten
                    - Specific values: model names, ports, paths, versions

                    Keep the summary under 800 tokens. Be dense — every token should carry information."""
                    .formatted(messages.size());

            Map<String, Object> request = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content",
                                    "Summarize this conversation:\n\n" + transcript)
                    ),
                    "stream", false,
                    "options", Map.of("num_predict", 1024)
            );

            String url = ollamaUrl.replaceAll("/+$", "") + "/api/chat";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            var node = mapper.readTree(resp.body());
            return node.path("message").path("content").asText("[Summary unavailable]");

        } catch (Exception e) {
            log.warn("[compactor] Summarization failed: {}", e.getMessage());
            return "[Context compacted — previous conversation summarized due to length]";
        }
    }
}
