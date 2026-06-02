package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * LLM-in-the-loop automatic memory extraction from conversations.
 *
 * After every {@value #EXTRACTION_INTERVAL} user messages in a session, this
 * service sends the recent conversation to the configured LLM and asks it to
 * extract memorable facts, preferences, and decisions. Extracted items are
 * stored via {@link MemoryService} with {@code source = "extractor"}.
 *
 * Extraction is fire-and-forget and never blocks the chat response.
 */
@Service
public class MemoryExtractorService {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractorService.class);
    private static final int EXTRACTION_INTERVAL = 10; // extract every N user messages

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final String EXTRACTION_PROMPT = """
            You are a memory extractor. Analyze the conversation and extract memorable facts, \
            preferences, decisions, and important context that should be remembered for future \
            conversations. Return a JSON array of short strings (under 100 chars each). \
            Only extract genuinely useful facts — not every utterance. Return [] if nothing is worth storing.

            Format: ["fact 1", "fact 2", ...]

            Conversation:
            %s""";

    private final MemoryService memoryService;
    private final DatabaseService databaseService;
    private final ObjectMapper mapper;

    public MemoryExtractorService(MemoryService memoryService, DatabaseService databaseService,
            ObjectMapper mapper) {
        this.memoryService = memoryService;
        this.databaseService = databaseService;
        this.mapper = mapper;
    }

    /**
     * Check if extraction should run based on message count, and if so, run it
     * asynchronously. Called after each user message is saved.
     *
     * @param owner     the user whose memory to update
     * @param sessionId the session to extract from
     * @param messages  recent messages in the session
     */
    public void maybeExtract(String owner, String sessionId, List<Map<String, Object>> messages) {
        long userMsgCount = messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .count();

        if (userMsgCount % EXTRACTION_INTERVAL != 0 || userMsgCount == 0) return;

        // Run extraction on a virtual thread — never block chat
        Thread.ofVirtual().name("memory-extractor-" + sessionId).start(() -> {
            try {
                extract(owner, sessionId, messages);
            } catch (Exception e) {
                log.warn("[memory-extractor] Extraction failed for session {}: {}", sessionId, e.getMessage());
            }
        });
    }

    private void extract(String owner, String sessionId, List<Map<String, Object>> messages) {
        String ollamaUrl = databaseService.getSetting("ollamaUrl", "http://localhost:11434");
        String model = databaseService.getSetting("memoryExtractorModel",
                databaseService.getSetting("defaultModel", "llama3.2"));

        // Build conversation transcript
        StringBuilder transcript = new StringBuilder();
        for (Map<String, Object> msg : messages) {
            String role = msg.getOrDefault("role", "user").toString();
            String content = msg.getOrDefault("content", "").toString();
            if (content.length() > 500) content = content.substring(0, 500) + "...";
            transcript.append(role.toUpperCase()).append(": ").append(content).append("\n");
        }

        String prompt = EXTRACTION_PROMPT.formatted(transcript);

        try {
            Map<String, Object> request = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "stream", false,
                    "options", Map.of("num_predict", 512, "temperature", 0.1)
            );

            String url = ollamaUrl.replaceAll("/+$", "") + "/api/chat";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String content = mapper.readTree(resp.body()).path("message").path("content").asText("[]");

            // Parse JSON array from response (handle markdown code blocks)
            content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start >= 0 && end > start) {
                content = content.substring(start, end + 1);
                List<String> facts = mapper.readValue(content, new TypeReference<>() {});
                int stored = 0;
                for (String fact : facts) {
                    if (fact != null && !fact.isBlank() && fact.length() < 500) {
                        memoryService.remember(owner, fact.trim(), sessionId, "extractor", List.of("auto"));
                        stored++;
                    }
                }
                if (stored > 0) log.info("[memory-extractor] Stored {} facts for {}", stored, owner);
            }
        } catch (Exception e) {
            log.debug("[memory-extractor] Extraction error: {}", e.getMessage());
        }
    }
}
