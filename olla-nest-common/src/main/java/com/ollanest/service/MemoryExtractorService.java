package com.ollanest.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Automatically extracts memorable facts, preferences, and decisions from
 * conversations by running a lightweight LLM pass every
 * {@value #EXTRACTION_INTERVAL} user messages.
 *
 * <p>
 * Extracted items are stored via {@link MemoryService} with
 * {@code source = "extractor"}. Extraction is always fire-and-forget on a
 * virtual thread and never blocks the chat response pipeline.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Users rarely think to manually save facts to memory during a conversation.
 * This service provides automatic background extraction so the assistant's
 * memory grows passively as the user chats — similar to how a human assistant
 * would take mental notes during a meeting.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Extraction is triggered by a modulo check on the user-message count; this
 * keeps it proportional to conversation length without scheduling
 * overhead.</li>
 * <li>The extraction prompt asks the LLM to return a JSON array of short
 * strings; the response parser strips Markdown code fences before JSON parsing
 * to handle verbose models.</li>
 * <li>Facts longer than 500 characters or blank entries are silently
 * dropped.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced as the automatic memory extraction pipeline</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class MemoryExtractorService {

	private static final Logger log = LoggerFactory.getLogger(MemoryExtractorService.class);
	/** Number of user messages between extraction runs. */
	private static final int EXTRACTION_INTERVAL = 10; // extract every N user messages

	/** Shared HTTP client for LLM extraction calls. */
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	/**
	 * System prompt template used for fact extraction; {@code %s} is replaced with
	 * the transcript.
	 */
	private static final String EXTRACTION_PROMPT = """
			You are a memory extractor. Analyze the conversation and extract memorable facts, \
			preferences, decisions, and important context that should be remembered for future \
			conversations. Return a JSON array of short strings (under 100 chars each). \
			Only extract genuinely useful facts — not every utterance. Return [] if nothing is worth storing.

			Format: ["fact 1", "fact 2", ...]

			Conversation:
			%s""";

	/** Delegate for persisting extracted memory facts. */
	private final MemoryService memoryService;

	/**
	 * Application settings service used to read LLM endpoint and model
	 * configuration.
	 */
	private final DatabaseService databaseService;

	/** Shared Jackson mapper for JSON parsing of the extraction response. */
	private final ObjectMapper mapper;

	/**
	 * Constructor-injects the required service dependencies.
	 *
	 * @param memoryService   service for storing extracted facts
	 * @param databaseService application settings service
	 * @param mapper          shared Jackson mapper
	 * @since v2026.2.1
	 */
	public MemoryExtractorService(MemoryService memoryService, DatabaseService databaseService, ObjectMapper mapper) {
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
	 * @since v2026.2.1
	 */
	public void maybeExtract(String owner, String sessionId, List<Map<String, Object>> messages) {
		long userMsgCount = messages.stream().filter(m -> "user".equals(m.get("role"))).count();

		if (userMsgCount % EXTRACTION_INTERVAL != 0 || userMsgCount == 0)
			return;

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
			if (content.length() > 500)
				content = content.substring(0, 500) + "...";
			transcript.append(role.toUpperCase()).append(": ").append(content).append("\n");
		}

		String prompt = EXTRACTION_PROMPT.formatted(transcript);

		try {
			Map<String, Object> request = Map.of("model", model, "messages",
					List.of(Map.of("role", "user", "content", prompt)), "stream", false, "options",
					Map.of("num_predict", 512, "temperature", 0.1));

			String url = ollamaUrl.replaceAll("/+$", "") + "/api/chat";
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/json")
					.timeout(Duration.ofSeconds(30))
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build();

			HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
			String content = mapper.readTree(resp.body()).path("message").path("content").asText("[]");

			// Parse JSON array from response (handle markdown code blocks)
			content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
			int start = content.indexOf('[');
			int end = content.lastIndexOf(']');
			if (start >= 0 && end > start) {
				content = content.substring(start, end + 1);
				List<String> facts = mapper.readValue(content, new TypeReference<>() {
				});
				int stored = 0;
				for (String fact : facts) {
					if (fact != null && !fact.isBlank() && fact.length() < 500) {
						memoryService.remember(owner, fact.trim(), sessionId, "extractor", List.of("auto"));
						stored++;
					}
				}
				if (stored > 0)
					log.info("[memory-extractor] Stored {} facts for {}", stored, owner);
			}
		} catch (Exception e) {
			log.debug("[memory-extractor] Extraction error: {}", e.getMessage());
		}
	}
}
