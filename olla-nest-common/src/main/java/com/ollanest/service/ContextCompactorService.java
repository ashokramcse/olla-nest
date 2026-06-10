package com.ollanest.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Auto-compacts conversation history when approaching the model's context
 * window limit.
 *
 * <p>
 * When the estimated token count of a session's messages exceeds 85% of the
 * context window, the oldest messages are summarised via a structured LLM call.
 * The summary is inserted as a {@code system} role message and the summarised
 * messages are dropped from the active context. The original messages are
 * retained in the database but are no longer forwarded to the LLM.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Local LLMs typically have context windows between 4 k and 128 k tokens. Long
 * chat sessions would silently fail or produce degraded output once the limit
 * was hit without a compaction strategy. This service provides a Cursor-style
 * rolling summary that preserves important facts while keeping the token count
 * manageable.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Token estimation uses a 4 chars/token heuristic — cheap and good enough
 * for the 85% threshold decision.</li>
 * <li>The most recent {@value #KEEP_RECENT} messages are always kept verbatim
 * so the LLM retains full context for the current exchange.</li>
 * <li>The summary prompt is opinionated (Cursor-style structured output) to
 * maximise information density in the replacement message.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced to prevent context overflow in long chat
 * sessions</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class ContextCompactorService {

	private static final Logger log = LoggerFactory.getLogger(ContextCompactorService.class);

	/**
	 * Trigger compaction when context token usage reaches this fraction of the
	 * window.
	 */
	private static final double COMPACT_THRESHOLD = 0.85;

	/**
	 * Number of most-recent messages to keep verbatim; older messages are
	 * summarised.
	 */
	private static final int KEEP_RECENT = 6;

	/**
	 * Fallback context window size (tokens) when the actual model context is
	 * unknown.
	 */
	private static final int DEFAULT_CONTEXT = 8192;

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

	private final ObjectMapper mapper;

	/**
	 * Constructs a {@code ContextCompactorService} with the required dependencies.
	 *
	 * @param mapper shared Jackson mapper for JSON operations
	 * @since v2026.2.1
	 */
	public ContextCompactorService(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	/**
	 * Estimates the token count of a text string using the 4 chars/token heuristic.
	 *
	 * @param text the text to estimate; {@code null} or empty returns {@code 0}
	 * @return estimated token count; always at least {@code 1} for non-empty input
	 * @since v2026.2.1
	 */
	public int estimateTokens(String text) {
		if (text == null || text.isEmpty())
			return 0;
		return Math.max(1, text.length() / 4);
	}

	/**
	 * Checks whether the given message list needs compaction given a context window
	 * size.
	 *
	 * @param messages    list of {@code {role, content}} maps representing the
	 *                    conversation
	 * @param contextSize model context window token count; {@code 0} or negative
	 *                    uses the default
	 * @return {@code true} if the estimated total token count exceeds the
	 *         compaction threshold
	 * @since v2026.2.1
	 */
	public boolean needsCompaction(List<Map<String, Object>> messages, int contextSize) {
		if (contextSize <= 0)
			contextSize = DEFAULT_CONTEXT;
		int total = 0;
		for (Map<String, Object> msg : messages) {
			Object content = msg.get("content");
			total += estimateTokens(content != null ? content.toString() : "");
		}
		return total > contextSize * COMPACT_THRESHOLD;
	}

	/**
	 * Compacts the given message list by replacing older messages with a structured
	 * summary.
	 *
	 * <p>
	 * If the list is short enough to fit in {@code KEEP_RECENT + 2} messages it is
	 * returned unchanged. Otherwise the oldest
	 * {@code messages.size() - KEEP_RECENT} messages are summarised and replaced
	 * with a single system-role summary message.
	 *
	 * @param messages    full conversation message history
	 * @param contextSize model context window token count; {@code 0} or negative
	 *                    uses the default
	 * @param ollamaUrl   LLM endpoint base URL used for the summarisation call
	 * @param model       model name to use for the summarisation LLM call
	 * @return a new message list with older content replaced by a summary, or the
	 *         original list unchanged if compaction was not needed
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> compact(List<Map<String, Object>> messages, int contextSize, String ollamaUrl,
			String model) {

		if (messages.size() <= KEEP_RECENT + 2)
			return messages;

		int toSummarize = messages.size() - KEEP_RECENT;
		List<Map<String, Object>> toCompact = new ArrayList<>(messages.subList(0, toSummarize));
		List<Map<String, Object>> toKeep = new ArrayList<>(messages.subList(toSummarize, messages.size()));

		String summary = summarize(toCompact, ollamaUrl, model);

		List<Map<String, Object>> result = new ArrayList<>();
		// Insert hidden summary as first "system" message
		result.add(Map.of("role", "system", "content", summary, "metadata",
				Map.of("hidden", false, "compaction_summary", true)));
		result.addAll(toKeep);
		return result;
	}

	/**
	 * Summarises a block of older conversation messages via the LLM so the running
	 * context can be compacted to fit the model window while preserving meaning.
	 *
	 * @param messages  the messages to condense
	 * @param ollamaUrl the Ollama base URL
	 * @param model     the model id used to produce the summary
	 * @return the summary text (or a best-effort fallback on failure)
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
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

			Map<String, Object> request = Map.of("model", model, "messages",
					List.of(Map.of("role", "system", "content", systemPrompt),
							Map.of("role", "user", "content", "Summarize this conversation:\n\n" + transcript)),
					"stream", false, "options", Map.of("num_predict", 1024));

			String url = ollamaUrl.replaceAll("/+$", "") + "/api/chat";
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/json")
					.timeout(Duration.ofSeconds(60))
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build();

			HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
			var node = mapper.readTree(resp.body());
			return node.path("message").path("content").asText("[Summary unavailable]");

		} catch (Exception e) {
			log.warn("[compactor] Summarization failed: {}", e.getMessage());
			return "[Context compacted — previous conversation summarized due to length]";
		}
	}
}
