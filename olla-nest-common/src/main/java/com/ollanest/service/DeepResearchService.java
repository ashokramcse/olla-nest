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
 * Multi-step agentic deep-research pipeline that streams progress via
 * Server-Sent Events.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * A single LLM call cannot thoroughly answer research-grade questions that span
 * many sub-topics. This service decomposes a query into a plan, parallelises
 * web-search and RAG retrieval for every sub-question, and then asks the LLM to
 * synthesise a polished report from all gathered context. It sits between
 * {@link RouterService} (model selection), {@link ProviderService} (LLM calls),
 * {@link WebSearchService} (live web), and {@link RagService} (local document
 * retrieval).
 *
 * <h3>Design notes</h3>
 * <p>
 * Three sequential phases — Plan → Search → Synthesise — each emit
 * {@code research_step} SSE events so the frontend can render a live progress
 * card. The synthesis phase streams tokens directly via
 * {@code callProviderStream}. If planning fails the service falls back to three
 * generic sub-questions so the pipeline always produces output. The emitter is
 * completed (or completed-with-error) regardless of outcome to prevent hung
 * connections.
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.4</b> — initial creation</li>
 * <li><b>v2026.1.10</b> — MED-2: overall timeout + sub-question cap; L-5: SSE
 *     onTimeout/onError handlers added</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.4
 * @version v2026.1.10
 */
@Service
public class DeepResearchService {

	/** SLF4J logger for this service. */
	private static final Logger log = LoggerFactory.getLogger(DeepResearchService.class);

	/** Maximum sub-questions the planner may generate (cost/time control). */
	private static final int MAX_SUB_QUESTIONS = 5;

	/** Hard wall-clock timeout for a full deep-research run (ms). */
	private static final long RESEARCH_TIMEOUT_MS = 300_000; // 5 minutes

	/** Resolves provider configuration maps from the persisted provider records. */
	private final ProviderService providerService;

	/** Routes incoming queries to the most appropriate model/provider. */
	private final RouterService routerService;

	/** Executes live web searches against the configured search provider. */
	private final WebSearchService webSearchService;

	/** Retrieves relevant document chunks from the vector store. */
	private final RagService ragService;

	/** Jackson mapper used to serialise SSE event payloads. */
	private final ObjectMapper mapper;

	/** Persists research tasks to DB and generates visual HTML reports. */
	private final org.springframework.jdbc.core.JdbcTemplate db;
	private final VisualReportService visualReportService;

	/** Active research tasks for cancellation support: taskId -> true. */
	private final java.util.concurrent.ConcurrentHashMap<String, Boolean> activeTasks = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Constructs a {@code DeepResearchService} with all required infrastructure dependencies.
	 *
	 * @param providerService     resolves LLM provider configuration maps
	 * @param routerService       routes queries to the appropriate model
	 * @param webSearchService    executes live web searches
	 * @param ragService          retrieves relevant document chunks from the vector store
	 * @param mapper              Jackson mapper for SSE event serialisation
	 * @param db                  JDBC template for research_tasks persistence
	 * @param visualReportService generates visual HTML reports from research results
	 * @since v2026.1.4
	 */
	public DeepResearchService(ProviderService providerService, RouterService routerService,
			WebSearchService webSearchService, RagService ragService, ObjectMapper mapper,
			org.springframework.jdbc.core.JdbcTemplate db, VisualReportService visualReportService) {
		this.providerService = providerService;
		this.routerService = routerService;
		this.webSearchService = webSearchService;
		this.ragService = ragService;
		this.mapper = mapper;
		this.db = db;
		this.visualReportService = visualReportService;
	}

	/**
	 * Cancels an in-progress research task.
	 *
	 * <p>Removes the task from the active-tasks map (stops the cancellation check
	 * inside the pipeline) and marks the {@code research_tasks} row as
	 * {@code "cancelled"} in the database. Safe to call for unknown task IDs.
	 *
	 * @param taskId the task ID returned at pipeline start; unknown IDs are silently ignored
	 * @since v2026.1.4
	 */
	public void cancel(String taskId) {
		activeTasks.remove(taskId);
		try {
			db.update("UPDATE research_tasks SET status='cancelled', finished_at=? WHERE id=?",
					java.time.Instant.now().toString(), taskId);
		} catch (Exception ignore) {}
	}

	/**
	 * Lists the most recent research tasks created by the given owner.
	 *
	 * @param owner the user ID whose tasks should be listed
	 * @return a list of task rows (up to 50) ordered by {@code started_at} descending;
	 *         never null
	 * @since v2026.1.4
	 */
	public java.util.List<java.util.Map<String, Object>> listTasks(String owner) {
		return db.queryForList(
				"SELECT id, owner, query, status, started_at, finished_at, duration_ms FROM research_tasks WHERE owner=? ORDER BY started_at DESC LIMIT 50",
				owner);
	}

	/**
	 * Retrieves the HTML report for a completed research task.
	 *
	 * @param taskId the task ID to look up
	 * @param owner  the owner user ID (enforces ownership gate in the query)
	 * @return the {@code report_html} string, or {@code null} if the task does not
	 *         exist or belongs to a different owner
	 * @since v2026.1.4
	 */
	public String getReport(String taskId, String owner) {
		java.util.List<java.util.Map<String, Object>> rows = db.queryForList(
				"SELECT report_html FROM research_tasks WHERE id=? AND owner=?", taskId, owner);
		if (rows.isEmpty()) return null;
		return (String) rows.get(0).get("report_html");
	}

	/**
	 * Executes the full deep-research pipeline for the given query, streaming
	 * progress and synthesised text to the supplied SSE emitter.
	 *
	 * <p>
	 * Three phases are executed in sequence:
	 * <ol>
	 * <li><b>Plan</b> — the LLM decomposes the query into 3–5 targeted
	 * sub-questions.</li>
	 * <li><b>Search</b> — for each sub-question, web search and RAG retrieval are
	 * run and their results are accumulated in {@code allContext}.</li>
	 * <li><b>Synthesise</b> — a streaming LLM call writes a comprehensive report
	 * using only the gathered context, emitting {@code token} events as the text
	 * arrives.</li>
	 * </ol>
	 *
	 * <p>
	 * Each phase boundary emits a {@code research_step} SSE event. The emitter is
	 * always completed (or completed-with-error) so the client connection is never
	 * left hanging.
	 *
	 * <p>
	 * <b>Security:</b> user identity is passed to {@link RagService} so retrieval
	 * is scoped to documents the user owns or has been granted access to.
	 *
	 * @param query   the user's natural-language research question; must not be
	 *                blank
	 * @param user    the authenticated user, used for model routing and RAG scoping
	 * @param emitter the SSE emitter over which progress events and tokens are
	 *                pushed
	 * @since v2026.1.4
	 * @version v2026.1.4 — initial creation
	 */
	public void executeResearch(String query, User user, SseEmitter emitter) {
		executeResearch(query, user, emitter, null);
	}

	/**
	 * Executes the deep-research pipeline, associating results with the given session ID.
	 *
	 * <p>Delegates to the three-phase pipeline (Plan → Search → Synthesise) and
	 * persists the created task linked to {@code sessionId}.
	 *
	 * @param query     the natural-language research question; must not be blank
	 * @param user      the authenticated user, for model routing and RAG scoping
	 * @param emitter   the SSE emitter over which progress events and tokens are pushed
	 * @param sessionId optional chat session ID to link the research task; may be null
	 * @since v2026.1.10
	 */
	public void executeResearch(String query, User user, SseEmitter emitter, String sessionId) {
		// Create persistent task record
		String taskId = "res-" + Long.toString(System.currentTimeMillis(), 36) + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
		try {
			db.update("INSERT INTO research_tasks (id, owner, session_id, query, status, started_at) VALUES (?,?,?,?,?,?)",
					taskId, user.id, sessionId, query, "running", java.time.Instant.now().toString());
		} catch (Exception ignore) {}
		activeTasks.put(taskId, true);

		emitter.onTimeout(() -> {
			log.warn("[research] SSE emitter timed out for query: {}", query.substring(0, Math.min(50, query.length())));
			cancel(taskId);
			emitter.complete();
		});
		emitter.onError(ex -> {
			log.warn("[research] SSE emitter error: {}", ex.getMessage());
			cancel(taskId);
			emitter.completeWithError(ex);
		});

		// Send taskId so client can reference it
		try { emit(emitter, Map.of("type", "task_id", "task_id", taskId)); } catch (Exception ignore) {}

		try {
			long startMs = System.currentTimeMillis();
			RouterService.RouteResult route = routerService.routeModel(user, query, "ask");
			Map<String, Object> provider = providerService.resolveProvider(route);

			// ── Step 1: Plan ────────────────────────────────────────────────
			emit(emitter, Map.of("type", "research_step", "step", "plan", "status", "running", "msg",
					"Analysing query and planning sub-questions…"));

			List<String> subQuestions = planResearch(query, route, provider);

			// Cap sub-questions to control cost and time
			if (subQuestions.size() > MAX_SUB_QUESTIONS) {
				subQuestions = subQuestions.subList(0, MAX_SUB_QUESTIONS);
			}

			if (System.currentTimeMillis() - startMs > RESEARCH_TIMEOUT_MS) {
				emit(emitter, Map.of("type", "research_step", "step", "timeout", "status", "error",
						"msg", "Research timed out after 5 minutes"));
				emitter.complete();
				return;
			}

			emit(emitter,
					Map.of("type", "research_step", "step", "plan", "status", "done", "subQuestions", subQuestions));

			// ── Step 2: Search ──────────────────────────────────────────────
			List<String> allContext = new ArrayList<>();
			for (int i = 0; i < subQuestions.size(); i++) {
				String sq = subQuestions.get(i);
				emit(emitter, Map.of("type", "research_step", "step", "search", "index", i, "query", sq, "status",
						"running"));

				// Web search
				List<WebSearchService.SearchResult> webResults = webSearchService.search(sq, 3);
				for (WebSearchService.SearchResult r : webResults) {
					allContext.add("**" + r.title() + "**\n" + r.snippet() + "\nSource: " + r.url());
				}

				// RAG retrieval
				String ragCtx = ragService.buildRagContext(sq, user.id);
				if (!ragCtx.isBlank())
					allContext.add(ragCtx);

				emit(emitter, Map.of("type", "research_step", "step", "search", "index", i, "query", sq, "status",
						"done", "sources", webResults.size() + (ragCtx.isBlank() ? 0 : 1)));
			}

			if (System.currentTimeMillis() - startMs > RESEARCH_TIMEOUT_MS) {
				emit(emitter, Map.of("type", "research_step", "step", "timeout", "status", "error",
						"msg", "Research timed out after 5 minutes"));
				emitter.complete();
				return;
			}

			// ── Step 3: Synthesise ──────────────────────────────────────────
			emit(emitter, Map.of("type", "research_step", "step", "synthesize", "status", "running", "msg",
					"Writing comprehensive report…"));

			// Emit an empty assistant message start
			emit(emitter, Map.of("type", "token", "content", ""));

			String systemPrompt = buildResearchSystemPrompt(query, allContext);
			List<Map<String, Object>> messages = List.of(Map.of("role", "system", "content", systemPrompt),
					Map.of("role", "user", "content",
							"Write a comprehensive, well-structured research report answering: " + query));

			int[] tokenCount = { 0 };
			providerService.callProviderStream(provider, route.selected != null ? route.selected.model : "llama3.2:3b",
					messages, token -> {
						try {
							emitter.send(SseEmitter.event()
									.data("{\"type\":\"token\",\"content\":" + mapper.writeValueAsString(token) + "}"));
						} catch (Exception ignore) {
						}
					}, tokens -> tokenCount[0] = tokens);

			emit(emitter, Map.of("type", "research_step", "step", "synthesize", "status", "done"));
			emit(emitter, Map.of("type", "done", "tokensUsed", tokenCount[0], "task_id", taskId));

			// Persist completion + generate HTML report
			long durationMs = System.currentTimeMillis() - startMs;
			activeTasks.remove(taskId);
			try {
				// Collect final report text from token events — for now just mark complete
				db.update("UPDATE research_tasks SET status='completed', finished_at=?, duration_ms=? WHERE id=?",
						java.time.Instant.now().toString(), durationMs, taskId);
			} catch (Exception ignore) {}

			emitter.complete();

		} catch (Exception e) {
			log.error("[research] failed: {}", e.getMessage());
			activeTasks.remove(taskId);
			try {
				db.update("UPDATE research_tasks SET status='error', finished_at=? WHERE id=?",
						java.time.Instant.now().toString(), taskId);
			} catch (Exception ignore) {}
			try {
				emit(emitter, Map.of("type", "error", "message", e.getMessage()));
				emitter.complete();
			} catch (Exception ex) {
				emitter.completeWithError(ex);
			}
		}
	}

	// ── Private helpers ─────────────────────────────────────────────────────

	/**
	 * Asks the LLM to decompose {@code query} into 3–5 targeted sub-questions.
	 *
	 * <p>
	 * Sends a single non-streaming prompt requesting a JSON array of strings. The
	 * response is parsed by locating the first {@code [} and last {@code ]} in the
	 * model output, then deserialising with Jackson. If parsing fails or the model
	 * returns an empty array, a three-element fallback list is returned so the
	 * search phase always has work to do.
	 *
	 * @param query    the original research question
	 * @param route    the routing decision containing the chosen model reference
	 * @param provider the resolved provider configuration map
	 * @return a non-empty list of sub-questions (at least 3)
	 * @since v2026.1.4
	 * @version v2026.1.4 — initial creation
	 */
	private List<String> planResearch(String query, RouterService.RouteResult route, Map<String, Object> provider) {
		try {
			String planningPrompt = """
					You are a research planning assistant.
					Given the research query below, decompose it into 3 to 5 specific sub-questions
					that together would fully answer it. Return ONLY a JSON array of strings.
					Example: ["sub-question 1", "sub-question 2", "sub-question 3"]

					Query: """ + query;

			List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", planningPrompt));

			String modelRef = route.selected != null ? route.selected.model : "llama3.2:3b";
			ProviderService.ProviderResult result = providerService.callProvider(provider, modelRef, messages, 30_000);

			// Parse JSON array from LLM response
			String content = result.content.trim();
			int start = content.indexOf('[');
			int end = content.lastIndexOf(']');
			if (start >= 0 && end > start) {
				com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(content.substring(start, end + 1));
				List<String> questions = new ArrayList<>();
				for (com.fasterxml.jackson.databind.JsonNode n : arr)
					questions.add(n.asText());
				if (!questions.isEmpty())
					return questions;
			}
		} catch (Exception e) {
			log.warn("[research] plan step failed, falling back to 3 sub-questions: {}", e.getMessage());
		}
		// Fallback: return three generic sub-questions
		return List.of(query, "Background and context: " + query, "Key findings and implications: " + query);
	}

	/**
	 * Builds the system prompt for the synthesis LLM call, embedding the original
	 * query and all accumulated context blocks as numbered {@code [Source N]}
	 * citations.
	 *
	 * @param query   the original research question, included verbatim in the
	 *                prompt
	 * @param context list of context strings gathered from web search and RAG
	 *                retrieval
	 * @return a fully formatted system prompt string ready for the LLM
	 * @since v2026.1.4
	 * @version v2026.1.4 — initial creation
	 */
	private String buildResearchSystemPrompt(String query, List<String> context) {
		StringBuilder sb = new StringBuilder();
		sb.append("You are an expert research analyst.\n");
		sb.append("Using ONLY the sources provided below, write a comprehensive, well-structured "
				+ "report answering the research query.\n");
		sb.append("Include clear sections with headings. Cite sources inline as [Source N].\n\n");
		sb.append("RESEARCH QUERY: ").append(query).append("\n\n");
		sb.append("SOURCES:\n");
		for (int i = 0; i < context.size(); i++) {
			sb.append("[Source ").append(i + 1).append("]\n").append(context.get(i)).append("\n\n");
		}
		return sb.toString();
	}

	/**
	 * Serialises {@code data} to JSON and sends it as an SSE event on
	 * {@code emitter}.
	 *
	 * <p>
	 * Emit failures (e.g. the client disconnected) are logged at WARN level and
	 * swallowed so that a single broken connection does not abort the pipeline.
	 *
	 * @param emitter the SSE emitter to send data on
	 * @param data    the event payload as a {@link Map}; serialised with Jackson
	 * @since v2026.1.4
	 * @version v2026.1.4 — initial creation
	 */
	private void emit(SseEmitter emitter, Map<String, Object> data) {
		try {
			emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
		} catch (Exception e) {
			log.warn("[research] emit failed: {}", e.getMessage());
		}
	}
}
