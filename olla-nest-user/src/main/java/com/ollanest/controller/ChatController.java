package com.ollanest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.ModelRecord;
import com.ollanest.model.User;
import com.ollanest.service.ChatService;
import com.ollanest.service.DatabaseService;
import com.ollanest.service.DeepResearchService;
import com.ollanest.service.FunctionCallService;
import com.ollanest.service.ModelService;
import com.ollanest.service.ProviderService;
import com.ollanest.service.RagService;
import com.ollanest.service.RouterService;
import com.ollanest.service.WebSearchService;
import com.ollanest.service.WorkspaceService;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Primary chat API for Olla Nest.
 *
 * <p>
 * Handles two chat execution paths:
 * <ul>
 * <li>{@code POST /api/chat} — synchronous (blocking) response</li>
 * <li>{@code POST /api/chat/stream} — SSE streaming response</li>
 * </ul>
 *
 * <p>
 * Both paths follow the same orchestration pipeline:
 * <ol>
 * <li>Auth check + rate limit + daily token quota</li>
 * <li>Privacy gate: detect sensitive content and block external providers</li>
 * <li>Router: select best model ({@link RouterService}) or honour manual
 * selection</li>
 * <li>RAG context: retrieve relevant document chunks ({@link RagService})</li>
 * <li>System prompt assembly
 * ({@link ChatService#buildSystemPromptWithRag})</li>
 * <li>Provider call: function calling (Ollama) or direct streaming call</li>
 * <li>DB persist: user and assistant messages</li>
 * <li>Auto-title: set session title from first message</li>
 * <li>Workspace artifacts: optionally write code fences to disk</li>
 * </ol>
 *
 * <p>
 * Additional endpoints:
 * <ul>
 * <li>{@code POST /api/chat/clear} — archive the active session</li>
 * <li>{@code DELETE /api/chat} — permanently delete the active session and
 * messages</li>
 * <li>{@code POST /api/feedback} — submit thumbs-up/down on a message</li>
 * </ul>
 *
 * <p>
 * <b>Design decisions:</b>
 * <ul>
 * <li>Streaming runs on a Java virtual thread (Project Loom) to avoid blocking
 * the Tomcat thread pool for potentially multi-minute AI responses.</li>
 * <li>Function calling on Ollama is done as a synchronous pre-call; if tool
 * calls are returned the results are injected and the follow-up is
 * streamed.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0 — initial Java Spring Boot migration
 * @version v2026.1.10 — L-4: ok:false added to all 4xx error responses;
 *          MED-1: 403 chat:use response includes ok:false
 */
@RestController
@RequestMapping("/api")
public class ChatController extends BaseController {

	/** Logger for DB errors and streaming issues. */
	private static final Logger log = LoggerFactory.getLogger(ChatController.class);

	/**
	 * JDBC template for message persistence, quota checks, and session management.
	 */
	private final JdbcTemplate db;

	/** Chat session helpers: context building, system prompt, audit. */
	private final ChatService chatService;

	/** Auto-router: selects the best model for each message. */
	private final RouterService routerService;

	/** Provider call layer: synchronous and streaming AI calls. */
	private final ProviderService providerService;

	/** Model registry: loads and filters allowed models. */
	private final ModelService modelService;

	/** Workspace config and artifact writing. */
	private final WorkspaceService workspaceService;

	/** Settings: project knowledge, write flags. */
	private final DatabaseService databaseService;

	/** Shared JSON mapper for SSE event serialisation. */
	private final ObjectMapper mapper;

	/** RAG pipeline for document retrieval. */
	private final RagService ragService;

	/** AI tool/function calling registry and executor. */
	private final FunctionCallService functionCallService;

	/** Web search service for Serper/Brave/SearXNG. */
	private final WebSearchService webSearchService;

	/** Deep research multi-step pipeline. */
	private final DeepResearchService deepResearchService;

	/**
	 * Constructor-injects all required dependencies.
	 *
	 * @param db                  the JDBC template
	 * @param chatService         the chat session helper
	 * @param routerService       the auto-router
	 * @param providerService     the provider call layer
	 * @param modelService        the model registry
	 * @param workspaceService    the workspace service
	 * @param databaseService     the settings service
	 * @param mapper              the shared JSON object mapper
	 * @param ragService          the RAG pipeline service
	 * @param functionCallService the function call service
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 * @version v2026.1.2 — Spring AI integration: added ragService and
	 *          functionCallService
	 */
	public ChatController(JdbcTemplate db, ChatService chatService, RouterService routerService,
			ProviderService providerService, ModelService modelService, WorkspaceService workspaceService,
			DatabaseService databaseService, ObjectMapper mapper, RagService ragService,
			FunctionCallService functionCallService, WebSearchService webSearchService,
			DeepResearchService deepResearchService) {
		this.db = db;
		this.chatService = chatService;
		this.routerService = routerService;
		this.providerService = providerService;
		this.modelService = modelService;
		this.workspaceService = workspaceService;
		this.databaseService = databaseService;
		this.mapper = mapper;
		this.ragService = ragService;
		this.functionCallService = functionCallService;
		this.webSearchService = webSearchService;
		this.deepResearchService = deepResearchService;
	}

	/**
	 * Executes a synchronous (blocking) chat request and returns the full response.
	 *
	 * <p>
	 * Runs the full orchestration pipeline (routing → RAG → function calling →
	 * provider call) and persists both the user and assistant messages before
	 * returning. On provider failure, returns a degraded response with
	 * {@code live: false}.
	 *
	 * @param body request body with: {@code message} (required), {@code mode}
	 *             (optional, default {@code "ask"}), {@code manualModelId}
	 *             (optional), {@code images} (optional list of base64 strings),
	 *             {@code writeToWorkspace} (optional boolean)
	 * @param req  the current HTTP request (for auth and CSRF check)
	 * @return 200 OK with {@code {content, route, model, live, artifacts,
	 *               extractedFiles, chat}}, or 400/401/403/429 on validation errors
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 * @version v2026.1.2 — Spring AI integration: RAG context + function calling
	 *          added
	 */
	@PostMapping("/chat")
	public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);
		if (!userHasRight(user, "chat:use")) {
			return ResponseEntity.status(403).body(Map.of("ok", false, "error", "Chat access is not enabled for this account"));
		}
		if (!chatService.checkChatRateLimit(user.id, user.apiRateLimitPerMinute)) {
			return ResponseEntity.status(429).body(Map.of("ok", false, "error", "Rate limit reached."));
		}

		String message = (String) body.get("message");
		String mode = (String) body.getOrDefault("mode", "ask");
		String manualModelId = (String) body.get("manualModelId");

		if (message == null || message.trim().isEmpty()) {
			return ResponseEntity.status(400).body(Map.of("ok", false, "error", "Message is required"));
		}
		if (message.length() > 16000) {
			return ResponseEntity.status(400)
					.body(Map.of("ok", false, "error", "Message exceeds maximum length of 16,000 characters"));
		}

		// Daily token quota check
		Integer todayUsage = db.queryForObject(
				"SELECT COALESCE(SUM(tokens_used),0) FROM chat_messages "
						+ "WHERE user_id = ? AND role = 'assistant' AND date(created_at) = date('now')",
				Integer.class, user.id);
		if (todayUsage != null && user.dailyTokenLimit > 0 && todayUsage >= user.dailyTokenLimit) {
			return ResponseEntity.status(429).body(Map.of("ok", false, "error", "Daily token limit reached."));
		}

		ModelRecord manualModel = null;
		if (manualModelId != null) {
			List<ModelRecord> allowed = modelService.allowedModels(user);
			manualModel = allowed.stream().filter(m -> m.id.equals(manualModelId)).findFirst().orElse(null);
			if (manualModel != null) {
				RouterService.SensitivityResult sr = routerService.detectSensitiveContent(message);
				if (sr.isSensitive && !"local".equals(manualModel.privacy)) {
					return ResponseEntity.status(403).body(Map.of("ok", false, "error",
							"Message contains sensitive content and cannot be sent to an external model."));
				}
			}
		}

		RouterService.RouteResult route;
		if (manualModel != null) {
			route = new RouterService.RouteResult();
			route.selected = manualModel;
			route.tags = List.of("manual");
			route.candidates = List.of();
			route.reason = "User manually selected an approved model.";
			route.privacyBlocked = false;
			route.sensitiveReasons = List.of();
		} else {
			route = routerService.routeModel(user, message, mode);
		}
		if (route.selected == null)
			return ResponseEntity.status(403).body(Map.of("ok", false, "error", route.reason));

		Map<String, Object> workspace = workspaceService.workspaceForUser(user.id);
		Map<String, Object> chat = chatService.getActiveChat(user.id);
		String content;
		boolean live = true;
		try {
			Map<String, Object> provider = providerService.resolveProvider(route);
			String ragContext = ragService.buildRagContext(message, user.id);
			String systemPrompt = chatService.buildSystemPromptWithRag(mode, route, workspace,
					databaseService.getSetting("projectKnowledge", ""), ragContext);
			@SuppressWarnings("unchecked")
			List<String> images = body.get("images") instanceof List ? (List<String>) body.get("images") : List.of();
			List<Map<String, Object>> messages = chatService.buildContextMessages((String) chat.get("id"), systemPrompt,
					message, route.selected.model, images);
			// Function calling: include tools for Ollama providers
			List<Map<String, Object>> tools = "ollama".equals(provider.get("type"))
					? functionCallService.getToolDefinitions()
					: null;
			ProviderService.ProviderResult result = providerService.callProvider(provider, route.selected.model,
					messages, 300000, tools);
			// Handle tool calls (max 1 round-trip)
			if (result.toolCalls != null && !result.toolCalls.isEmpty()) {
				List<Map<String, Object>> toolMessages = new ArrayList<>(messages);
				Map<String, Object> assistantMsg = new LinkedHashMap<>();
				assistantMsg.put("role", "assistant");
				assistantMsg.put("content", "");
				assistantMsg.put("tool_calls", result.toolCalls);
				toolMessages.add(assistantMsg);
				for (Map<String, Object> tc : result.toolCalls) {
					@SuppressWarnings("unchecked")
					Map<String, Object> args = (Map<String, Object>) tc.getOrDefault("args", Map.of());
					String toolResult = functionCallService.executeTool((String) tc.get("name"), args, user.id);
					Map<String, Object> toolMsg = new LinkedHashMap<>();
					toolMsg.put("role", "tool");
					toolMsg.put("content", toolResult);
					toolMessages.add(toolMsg);
				}
				result = providerService.callProvider(provider, route.selected.model, toolMessages, 300000, null);
			}
			content = result.content;
		} catch (Exception e) {
			live = false;
			content = "Auto Router selected " + route.selected.name
					+ ", but the model call did not complete.\n\nReason: " + e.getMessage();
		}

		boolean writeApproved = Boolean.TRUE.equals(body.get("writeToWorkspace"))
				|| "full".equals(workspace.get("permissionMode"));
		boolean shouldWriteLocal = live && Boolean.TRUE.equals(workspace.get("localWritesEnabled")) && writeApproved;
		List<Map<String, Object>> artifacts = shouldWriteLocal
				? workspaceService.writeLocalArtifacts(workspace, message, mode, content)
				: List.of();
		List<Map<String, Object>> extractedFiles = live ? extractFiles(content, message) : List.of();
		String chatContent = content;
		if (!artifacts.isEmpty() || !extractedFiles.isEmpty()) {
			chatContent = content.replaceAll("(?s)```[\\s\\S]*?```", "").replaceAll("\n{3,}", "\n\n").trim();
		}

		String now = Instant.now().toString();
		String chatId = (String) chat.get("id");
		String userMsgId = chatService.uid("msg");
		String asstMsgId = chatService.uid("msg");
		try {
			db.update(
					"INSERT INTO chat_messages (id, session_id, user_id, role, content, mode, "
							+ "created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
					userMsgId, chatId, user.id, "user", message, mode, now);
			db.update(
					"INSERT INTO chat_messages (id, session_id, user_id, role, content, model_id, "
							+ "model_name, route_reason, live, artifacts_json, extracted_files_json, "
							+ "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
					asstMsgId, chatId, user.id, "assistant", chatContent, route.selected.id, route.selected.name,
					route.reason, live ? 1 : 0, toJson(artifacts), toJson(extractedFiles), now);
		} catch (Exception e) {
			log.error("[chat] DB error: {}", e.getMessage());
		}

		// Auto-title from first user message
		List<Map<String, Object>> cs = db.queryForList("SELECT title FROM chat_sessions WHERE id = ?", chatId);
		String title = cs.isEmpty() ? "New Chat" : (String) cs.get(0).get("title");
		if (title == null || "New Chat".equals(title)) {
			title = message.substring(0, Math.min(45, message.length())).trim() + (message.length() > 45 ? "…" : "");
		}
		db.update("UPDATE chat_sessions SET title = ?, updated_at = ? WHERE id = ?", title, now, chatId);

		// Guard: session may be deleted concurrently; avoid IndexOutOfBoundsException
		// in this hot streaming path by checking the list before indexing into it.
		List<Map<String, Object>> sessionRows =
				db.queryForList("SELECT * FROM chat_sessions WHERE id = ?", chatId);
		if (sessionRows.isEmpty()) {
			log.warn("[chat] Session {} vanished after update — returning partial result", chatId);
			sessionRows = java.util.Collections.singletonList(java.util.Map.of("id", chatId));
		}
		Map<String, Object> updatedSession = sessionRows.get(0);
		Map<String, Object> updatedChat = chatService.buildChatObject(updatedSession);
		chatService.appendTrace(user.id, chatId, message, mode, route.selected.id, route.tags, route.candidates, live);
		chatService.appendAudit(user.name, "chat.request", mode.toUpperCase() + " routed to " + route.selected.name,
				Map.of("live", live));

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("content", content);
		result.put("route", routeToMap(route));
		result.put("model", route.selected);
		result.put("live", live);
		result.put("artifacts", artifacts);
		result.put("extractedFiles", extractedFiles);
		result.put("chat", updatedChat);
		return ResponseEntity.ok(result);
	}

	/**
	 * Executes a streaming chat request, emitting SSE events as tokens arrive.
	 *
	 * <p>
	 * Runs the orchestration pipeline on a Java virtual thread to avoid blocking
	 * the Tomcat thread pool. SSE event types emitted:
	 * <ul>
	 * <li>{@code routing} — model and reason selected by the router</li>
	 * <li>{@code token} — individual response token</li>
	 * <li>{@code error} — provider or validation error</li>
	 * <li>{@code done} — final event with token count, latency, and artifacts</li>
	 * </ul>
	 *
	 * <p>
	 * For Ollama providers, a synchronous function-call pre-pass is made before
	 * streaming; if tool calls are returned the results are injected and the
	 * follow-up is streamed.
	 *
	 * @param body same parameters as {@link #chat}
	 * @param req  the current HTTP request (for auth and CSRF check)
	 * @return an {@link SseEmitter} that streams events until done or error
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 * @version v2026.1.2 — Spring AI integration: RAG context + function calling
	 *          added
	 */
	@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter chatStream(@RequestBody Map<String, Object> body, HttpServletRequest req) {
		SseEmitter emitter = new SseEmitter(-1L);

		User user = getUser(req);
		if (user == null) {
			try {
				emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Login required\"}"));
				emitter.complete();
			} catch (Exception ignored) {
			}
			return emitter;
		}
		if (!isCsrfOk(req)) {
			try {
				emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Forbidden\"}"));
				emitter.complete();
			} catch (Exception ignored) {
			}
			return emitter;
		}
		if (!userHasRight(user, "chat:use")) {
			try {
				emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Chat access not enabled\"}"));
				emitter.complete();
			} catch (Exception ignored) {
			}
			return emitter;
		}
		if (!chatService.checkChatRateLimit(user.id, user.apiRateLimitPerMinute)) {
			try {
				emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Rate limit reached.\"}"));
				emitter.complete();
			} catch (Exception ignored) {
			}
			return emitter;
		}

		String message = (String) body.get("message");
		String mode = (String) body.getOrDefault("mode", "ask");
		String manualModelId = (String) body.get("manualModelId");

		if (message == null || message.trim().isEmpty()) {
			try {
				emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Message is required\"}"));
				emitter.complete();
			} catch (Exception ignored) {
			}
			return emitter;
		}
		if (message.length() > 16000) {
			try {
				emitter.send(SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Message too long\"}"));
				emitter.complete();
			} catch (Exception ignored) {
			}
			return emitter;
		}

		// Daily token quota check
		Integer todayUsage = db.queryForObject(
				"SELECT COALESCE(SUM(tokens_used),0) FROM chat_messages "
						+ "WHERE user_id = ? AND role = 'assistant' AND date(created_at) = date('now')",
				Integer.class, user.id);
		if (todayUsage != null && user.dailyTokenLimit > 0 && todayUsage >= user.dailyTokenLimit) {
			try {
				emitter.send(
						SseEmitter.event().data("{\"type\":\"error\",\"message\":\"Daily token limit reached.\"}"));
				emitter.complete();
			} catch (Exception ignored) {
			}
			return emitter;
		}

		// Deep research path — delegates entirely to DeepResearchService
		if (Boolean.TRUE.equals(body.get("deepResearch"))) {
			final User researchUser = user;
			final String researchMessage = message;
			Thread.ofVirtual().start(() -> deepResearchService.executeResearch(researchMessage, researchUser, emitter));
			return emitter;
		}

		// Run streaming on a virtual thread to avoid blocking Tomcat
		final String finalMessage = message;
		final String finalMode = mode;
		final boolean enableWebSearch = Boolean.TRUE.equals(body.get("enableWebSearch"));
		Thread.ofVirtual().start(() -> {
			try {
				ModelRecord manualModel = null;
				if (manualModelId != null) {
					List<ModelRecord> allowed = modelService.allowedModels(user);
					manualModel = allowed.stream().filter(m -> m.id.equals(manualModelId)).findFirst().orElse(null);
					if (manualModel != null) {
						RouterService.SensitivityResult sr = routerService.detectSensitiveContent(finalMessage);
						if (sr.isSensitive && !"local".equals(manualModel.privacy)) {
							emitter.send(SseEmitter.event()
									.data(toJson(Map.of("type", "error", "message",
											"Message contains sensitive content and cannot be "
													+ "sent to an external model."))));
							emitter.complete();
							return;
						}
					}
				}

				RouterService.RouteResult route;
				if (manualModel != null) {
					route = new RouterService.RouteResult();
					route.selected = manualModel;
					route.tags = List.of("manual");
					route.candidates = List.of();
					route.reason = "User manually selected an approved model.";
					route.privacyBlocked = false;
					route.sensitiveReasons = List.of();
				} else {
					route = routerService.routeModel(user, finalMessage, finalMode);
				}
				if (route.selected == null) {
					emitter.send(SseEmitter.event().data(toJson(Map.of("type", "error", "message", route.reason))));
					emitter.complete();
					return;
				}

				emitter.send(SseEmitter.event().data(toJson(Map.of("type", "routing", "model", route.selected.name,
						"provider", route.selected.provider, "reason", route.reason))));

				Map<String, Object> workspace = workspaceService.workspaceForUser(user.id);
				@SuppressWarnings("unchecked")
				List<String> images = body.get("images") instanceof List ? (List<String>) body.get("images")
						: List.of();
				Map<String, Object> chatSession = chatService.getActiveChat(user.id);
				String chatId = (String) chatSession.get("id");
				String ragContext = ragService.buildRagContext(finalMessage, user.id);

				// Web search — inject results into system prompt if enabled
				String webSearchContext = "";
				if (enableWebSearch) {
					try {
						List<WebSearchService.SearchResult> searchResults = webSearchService.search(finalMessage, 5);
						if (!searchResults.isEmpty()) {
							emitter.send(SseEmitter.event().data(toJson(Map.of("type", "search_status", "query",
									finalMessage, "resultsCount", searchResults.size()))));
							webSearchContext = webSearchService.formatResultsForPrompt(searchResults);
						}
					} catch (Exception e) {
						log.warn("[chat/stream] web search failed: {}", e.getMessage());
					}
				}

				String systemPrompt = chatService.buildSystemPromptWithRag(finalMode, route, workspace,
						databaseService.getSetting("projectKnowledge", ""),
						ragContext + (webSearchContext.isEmpty() ? "" : "\n\n" + webSearchContext));
				List<Map<String, Object>> messages = chatService.buildContextMessages(chatId, systemPrompt,
						finalMessage, route.selected.model, images);

				StringBuilder fullContent = new StringBuilder();
				int[] tokensUsed = { 0 };
				boolean[] live = { true };
				long startMs = System.currentTimeMillis();

				try {
					Map<String, Object> provider = providerService.resolveProvider(route);
					// For Ollama: try non-streaming function-call first, then stream
					if ("ollama".equals(provider.get("type"))) {
						List<Map<String, Object>> tools = functionCallService.getToolDefinitions();
						ProviderService.ProviderResult fcResult = providerService.callProvider(provider,
								route.selected.model, messages, 300000, tools);
						if (fcResult.toolCalls != null && !fcResult.toolCalls.isEmpty()) {
							List<Map<String, Object>> toolMessages = new ArrayList<>(messages);
							Map<String, Object> assistantMsg = new LinkedHashMap<>();
							assistantMsg.put("role", "assistant");
							assistantMsg.put("content", "");
							assistantMsg.put("tool_calls", fcResult.toolCalls);
							toolMessages.add(assistantMsg);
							for (Map<String, Object> tc : fcResult.toolCalls) {
								@SuppressWarnings("unchecked")
								Map<String, Object> args = (Map<String, Object>) tc.getOrDefault("args", Map.of());
								String toolResult = functionCallService.executeTool((String) tc.get("name"), args,
										user.id);
								Map<String, Object> toolMsg = new LinkedHashMap<>();
								toolMsg.put("role", "tool");
								toolMsg.put("content", toolResult);
								toolMessages.add(toolMsg);
							}
							// Stream the follow-up response after tool execution
							providerService.callProviderStream(provider, route.selected.model, toolMessages, token -> {
								fullContent.append(token);
								try {
									emitter.send(
											SseEmitter.event().data(toJson(Map.of("type", "token", "content", token))));
								} catch (Exception ignored) {
								}
							}, total -> tokensUsed[0] = total);
						} else {
							// No tool calls — stream normally
							providerService.callProviderStream(provider, route.selected.model, messages, token -> {
								fullContent.append(token);
								try {
									emitter.send(
											SseEmitter.event().data(toJson(Map.of("type", "token", "content", token))));
								} catch (Exception ignored) {
								}
							}, total -> tokensUsed[0] = total);
						}
					} else {
						// Non-Ollama providers: stream directly
						providerService.callProviderStream(provider, route.selected.model, messages, token -> {
							fullContent.append(token);
							try {
								emitter.send(
										SseEmitter.event().data(toJson(Map.of("type", "token", "content", token))));
							} catch (Exception ignored) {
							}
						}, total -> tokensUsed[0] = total);
					}
				} catch (Exception e) {
					live[0] = false;
					String errMsg = "Auto Router selected " + route.selected.name
							+ ", but the model call did not complete.\n\nReason: " + e.getMessage();
					fullContent.append(errMsg);
					emitter.send(SseEmitter.event().data(toJson(Map.of("type", "error", "message", e.getMessage()))));
				}

				String cleanedContent = WorkspaceService.cleanModelOutput(fullContent.toString());
				boolean writeApproved = Boolean.TRUE.equals(body.get("writeToWorkspace"))
						|| "full".equals(workspace.get("permissionMode"));
				boolean shouldWriteLocal = live[0] && Boolean.TRUE.equals(workspace.get("localWritesEnabled"))
						&& writeApproved;
				List<Map<String, Object>> artifacts = shouldWriteLocal
						? workspaceService.writeLocalArtifacts(workspace, finalMessage, finalMode, cleanedContent)
						: List.of();
				List<Map<String, Object>> extractedFiles = live[0] ? extractFiles(cleanedContent, finalMessage)
						: List.of();
				String chatContent = cleanedContent;
				if (!artifacts.isEmpty() || !extractedFiles.isEmpty()) {
					chatContent = cleanedContent.replaceAll("(?s)```[\\s\\S]*?```", "").replaceAll("\n{3,}", "\n\n")
							.trim();
				}

				String now = Instant.now().toString();
				long latencyMs = System.currentTimeMillis() - startMs;
				String userMsgId = chatService.uid("msg");
				String asstMsgId = chatService.uid("msg");
				try {
					db.update(
							"INSERT INTO chat_messages (id, session_id, user_id, role, content, "
									+ "mode, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
							userMsgId, chatId, user.id, "user", finalMessage, finalMode, now);
					db.update(
							"INSERT INTO chat_messages (id, session_id, user_id, role, content, "
									+ "model_id, model_name, route_reason, live, artifacts_json, "
									+ "extracted_files_json, tokens_used, latency_ms, created_at) "
									+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
							asstMsgId, chatId, user.id, "assistant", chatContent, route.selected.id,
							route.selected.name, route.reason, live[0] ? 1 : 0, toJson(artifacts),
							toJson(extractedFiles), tokensUsed[0], latencyMs, now);
				} catch (Exception e) {
					log.error("[chat/stream] DB persist error: {}", e.getMessage());
				}

				// Auto-title from first user message
				List<Map<String, Object>> cs = db.queryForList("SELECT title FROM chat_sessions WHERE id = ?", chatId);
				String title = cs.isEmpty() ? "New Chat" : (String) cs.get(0).get("title");
				if (title == null || "New Chat".equals(title)) {
					title = finalMessage.substring(0, Math.min(45, finalMessage.length())).trim()
							+ (finalMessage.length() > 45 ? "…" : "");
				}
				db.update("UPDATE chat_sessions SET title = ?, updated_at = ? WHERE id = ?", title, now, chatId);

				chatService.appendTrace(user.id, chatId, finalMessage, finalMode, route.selected.id, route.tags,
						route.candidates, live[0]);
				chatService.appendAudit(user.name, "chat.stream",
						finalMode.toUpperCase() + " streamed to " + route.selected.name, Map.of("live", live[0]));

				emitter.send(SseEmitter.event()
						.data(toJson(Map.of("type", "done", "tokensUsed", tokensUsed[0], "latencyMs", latencyMs,
								"messageId", asstMsgId, "live", live[0], "artifacts", artifacts, "extractedFiles",
								extractedFiles))));
				emitter.complete();
			} catch (Exception e) {
				try {
					emitter.send(SseEmitter.event().data(toJson(Map.of("type", "error", "message", e.getMessage()))));
					emitter.complete();
				} catch (Exception ignored) {
				}
			}
		});

		return emitter;
	}

	/**
	 * Archives the current active chat session and creates a fresh one.
	 *
	 * <p>
	 * Does not delete any messages; the archived session remains in history.
	 *
	 * @param req the current HTTP request (for auth check)
	 * @return 200 OK {@code {ok: true}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@PostMapping("/chat/clear")
	public ResponseEntity<Map<String, Object>> clearChat(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);
		List<Map<String, Object>> existing = db
				.queryForList("SELECT id FROM chat_sessions WHERE user_id = ? AND is_active = 1 "
						+ "ORDER BY updated_at DESC LIMIT 1", user.id);
		if (!existing.isEmpty()) {
			db.update("UPDATE chat_sessions SET is_active = 0, updated_at = ? WHERE id = ?", Instant.now().toString(),
					existing.get(0).get("id"));
		}
		chatService.getActiveChat(user.id);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Permanently deletes the active chat session and all of its messages.
	 *
	 * <p>
	 * A fresh empty session is created after deletion via
	 * {@link ChatService#getActiveChat}.
	 *
	 * @param req the current HTTP request (for auth check)
	 * @return 200 OK {@code {ok: true}}
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	@DeleteMapping("/chat")
	public ResponseEntity<Map<String, Object>> deleteChat(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);
		Map<String, Object> session = chatService.getActiveChatSession(user.id);
		if (session != null) {
			String sessionId = (String) session.get("id");
			db.update("DELETE FROM chat_messages WHERE session_id = ?", sessionId);
			db.update("DELETE FROM chat_sessions WHERE id = ?", sessionId);
		}
		chatService.getActiveChat(user.id);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Records a thumbs-up (+1) or thumbs-down (-1) rating for an assistant message.
	 *
	 * <p>
	 * IDOR protection: the message must belong to a session owned by the current
	 * user. Returns 404 if the message is not found or belongs to another user.
	 *
	 * @param body request body with {@code messageId}, {@code sessionId},
	 *             {@code rating} (1 or -1), and optional {@code comment}
	 * @param req  the current HTTP request (for auth check)
	 * @return 200 OK {@code {ok: true}}, or 400/404 on validation errors
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 * @version v2026.1.0 — security hardening: IDOR protection added
	 */
	@PostMapping("/feedback")
	public ResponseEntity<Map<String, Object>> feedback(@RequestBody Map<String, Object> body, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> authError = requireAuthWithCsrf(req);
		if (authError != null)
			return authError;
		User user = getUser(req);
		String messageId = (String) body.get("messageId");
		String sessionId = (String) body.get("sessionId");
		Object ratingObj = body.get("rating");
		if (messageId == null || sessionId == null || ratingObj == null) {
			return ResponseEntity.status(400).body(Map.of("ok", false, "error", "messageId, sessionId, and rating are required"));
		}
		int rating = ((Number) ratingObj).intValue();
		if (rating != 1 && rating != -1) {
			return ResponseEntity.status(400).body(Map.of("ok", false, "error", "rating must be 1 or -1"));
		}
		// IDOR guard: verify the message belongs to the user's own session
		Integer msgCount = db.queryForObject(
				"SELECT COUNT(*) FROM chat_messages WHERE id = ? "
						+ "AND session_id IN (SELECT id FROM chat_sessions WHERE user_id = ?)",
				Integer.class, messageId, user.id);
		if (msgCount == null || msgCount == 0) {
			return ResponseEntity.status(404).body(Map.of("ok", false, "error", "Message not found"));
		}
		db.update(
				"INSERT INTO feedback (id, message_id, session_id, user_id, rating, comment, "
						+ "created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
				chatService.uid("fb"), messageId, sessionId, user.id, rating, body.get("comment"),
				Instant.now().toString());
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Returns {@code true} if the user has the given right, or is an admin.
	 *
	 * @param user  the authenticated user
	 * @param right the right string to check (e.g. {@code "chat:use"})
	 * @return {@code true} if the right is present or the user is an admin
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	private boolean userHasRight(User user, String right) {
		return "admin".equals(user.role) || (user.rights != null && user.rights.contains(right));
	}

	/**
	 * Extracts code-fence artifacts from an AI response without writing to disk.
	 *
	 * @param content the raw AI response text
	 * @param message the user's original message (used for filename inference)
	 * @return a list of {@code {name, content}} maps for each artifact found
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	private List<Map<String, Object>> extractFiles(String content, String message) {
		List<WorkspaceService.Artifact> extracted = workspaceService.extractArtifacts(content, message);
		List<Map<String, Object>> result = new ArrayList<>();
		for (WorkspaceService.Artifact a : extracted) {
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("name", a.name);
			f.put("content", a.content);
			result.add(f);
		}
		return result;
	}

	/**
	 * Converts a {@link RouterService.RouteResult} to a serialisable map for the
	 * response.
	 *
	 * @param route the route result to convert
	 * @return a map with keys: selected, tags, candidates, reason, privacyBlocked,
	 *         sensitiveReasons
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	private Map<String, Object> routeToMap(RouterService.RouteResult route) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("selected", route.selected);
		m.put("tags", route.tags);
		m.put("candidates", route.candidates);
		m.put("reason", route.reason);
		m.put("privacyBlocked", route.privacyBlocked);
		m.put("sensitiveReasons", route.sensitiveReasons);
		return m;
	}

	/**
	 * Serialises an object to a JSON string. Returns {@code "{}"} on error.
	 *
	 * @param obj the object to serialise
	 * @return the JSON string representation
	 * @since v2026.1.0 — initial Java Spring Boot migration
	 */
	private String toJson(Object obj) {
		try {
			return mapper.writeValueAsString(obj);
		} catch (Exception e) {
			return "{}";
		}
	}
}
