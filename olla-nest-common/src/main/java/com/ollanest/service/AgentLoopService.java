package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-round agentic loop that streams tool execution via SSE.
 *
 * The LLM signals tool calls via fenced code blocks:
 * <pre>
 *   ```bash
 *   ls -la
 *   ```
 * </pre>
 *
 * The loop parses tool calls, executes them, feeds results back, and
 * continues until the LLM produces no further tool calls or MAX_ROUNDS
 * is reached. All tool output is streamed via the SSE emitter.
 *
 * Tools are security-checked against the user's privilege flags before execution.
 */
@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);
    private static final int MAX_ROUNDS = 20;
    private static final int SHELL_TIMEOUT_S = 60;

    private static final Pattern TOOL_BLOCK = Pattern.compile(
            "```(bash|python|web_search|web_fetch|read_file|write_file|manage_memory|manage_notes|manage_calendar|manage_tasks)\\s*\\n([\\s\\S]*?)```",
            Pattern.MULTILINE);

    // sessionId -> active run info
    private final Map<String, Map<String, Object>> activeRuns = new ConcurrentHashMap<>();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();

    private final ObjectMapper mapper;
    private final JdbcTemplate db;
    private final WebSearchService webSearchService;
    private final MemoryService memoryService;
    private final NotesService notesService;
    private final CalendarService calendarService;
    private final TaskSchedulerService taskService;

    public AgentLoopService(ObjectMapper mapper, JdbcTemplate db,
            WebSearchService webSearchService, MemoryService memoryService,
            NotesService notesService, CalendarService calendarService,
            TaskSchedulerService taskService) {
        this.mapper = mapper;
        this.db = db;
        this.webSearchService = webSearchService;
        this.memoryService = memoryService;
        this.notesService = notesService;
        this.calendarService = calendarService;
        this.taskService = taskService;
    }

    /**
     * Run the agent loop for a given session. Streams events via {@code emitter}.
     *
     * @param sessionId  chat session ID
     * @param owner      authenticated user ID
     * @param messages   full conversation history
     * @param ollamaUrl  LLM endpoint base URL
     * @param model      model name
     * @param canBash    whether the user has bash tool access
     * @param emitter    SSE emitter to stream output
     */
    public void runLoop(String sessionId, String owner, List<Map<String, Object>> messages,
            String ollamaUrl, String model, boolean canBash, SseEmitter emitter) {

        Thread.ofVirtual().name("agent-" + sessionId).start(() -> {
            activeRuns.put(sessionId, Map.of("owner", owner, "status", "running", "round", 0));
            try {
                List<Map<String, Object>> history = new ArrayList<>(messages);
                int round = 0;

                while (round < MAX_ROUNDS) {
                    round++;
                    if (!activeRuns.containsKey(sessionId)) {
                        sendSseEvent(emitter, "agent_cancelled", Map.of("message", "Agent run cancelled"));
                        break;
                    }

                    sendSseEvent(emitter, "agent_round", Map.of("round", round));

                    // Call LLM
                    String response = callLlm(ollamaUrl, model, history);
                    if (response == null || response.isBlank()) break;

                    // Stream the text response
                    sendSseEvent(emitter, "agent_text", Map.of("content", response, "round", round));

                    // Parse tool calls
                    List<Map<String, Object>> toolCalls = parseToolCalls(response);
                    if (toolCalls.isEmpty()) {
                        // No more tools — done
                        history.add(Map.of("role", "assistant", "content", response));
                        break;
                    }

                    // Execute tools
                    StringBuilder toolResults = new StringBuilder();
                    for (Map<String, Object> toolCall : toolCalls) {
                        String toolType = (String) toolCall.get("type");
                        String toolInput = (String) toolCall.get("input");

                        sendSseEvent(emitter, "tool_call", Map.of("type", toolType, "input", truncate(toolInput, 200)));

                        String result = executeTool(toolType, toolInput, owner, sessionId, canBash);
                        sendSseEvent(emitter, "tool_result", Map.of("type", toolType, "output", truncate(result, 500)));

                        toolResults.append("\n[").append(toolType).append(" result]:\n").append(result).append("\n");
                    }

                    // Feed results back as user message
                    history.add(Map.of("role", "assistant", "content", response));
                    history.add(Map.of("role", "user", "content",
                            "Tool execution results:" + toolResults));
                }

                sendSseEvent(emitter, "agent_done", Map.of("rounds", round));

            } catch (Exception e) {
                log.warn("[agent] Loop error for session {}: {}", sessionId, e.getMessage());
                sendSseEvent(emitter, "agent_error", Map.of("error", e.getMessage()));
            } finally {
                activeRuns.remove(sessionId);
                try { emitter.complete(); } catch (Exception ignore) {}
            }
        });
    }

    public void cancel(String sessionId) {
        activeRuns.remove(sessionId);
    }

    public boolean isRunning(String sessionId) {
        return activeRuns.containsKey(sessionId);
    }

    // ── Tool execution ────────────────────────────────────────────────────────

    private String executeTool(String type, String input, String owner, String sessionId, boolean canBash) {
        return switch (type) {
            case "bash" -> canBash ? runBash(input) : "Error: bash tool access not granted";
            case "python" -> "Python sandbox: " + truncate(input, 50) + " (execution disabled in this build)";
            case "web_search" -> runWebSearch(input, owner, sessionId);
            case "web_fetch" -> runWebFetch(input);
            case "manage_memory" -> runManageMemory(input, owner, sessionId);
            case "manage_notes" -> runManageNotes(input, owner);
            case "manage_calendar" -> runManageCalendar(input, owner);
            case "manage_tasks" -> runManageTasks(input, owner);
            default -> "Unknown tool: " + type;
        };
    }

    private String runBash(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(SHELL_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return "Error: Command timed out after " + SHELL_TIMEOUT_S + "s";
            }
            String output = new String(proc.getInputStream().readAllBytes());
            return truncate(output.isBlank() ? "(no output)" : output, 5000);
        } catch (Exception e) {
            return "Error running bash: " + e.getMessage();
        }
    }

    private String runWebSearch(String query, String owner, String sessionId) {
        try {
            List<WebSearchService.SearchResult> results = webSearchService.search(query.trim(), 5);
            if (results.isEmpty()) return "No results found for: " + query;
            StringBuilder sb = new StringBuilder();
            for (WebSearchService.SearchResult r : results) {
                sb.append("• ").append(r.title()).append("\n")
                  .append("  ").append(r.url()).append("\n")
                  .append("  ").append(truncate(r.snippet(), 200)).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Search error: " + e.getMessage();
        }
    }

    private String runWebFetch(String url) {
        try {
            url = url.trim();
            if (!url.startsWith("http")) return "Error: URL must start with http";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Olla-Nest-Agent/1.0")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            // Strip HTML tags for LLM consumption
            String body = resp.body().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            return truncate(body, 8000);
        } catch (Exception e) {
            return "Fetch error: " + e.getMessage();
        }
    }

    private String runManageMemory(String input, String owner, String sessionId) {
        try {
            Map<String, Object> cmd = mapper.readValue(input, new TypeReference<>() {});
            String action = (String) cmd.getOrDefault("action", "list");
            return switch (action) {
                case "remember" -> {
                    memoryService.remember(owner, (String) cmd.get("text"), sessionId, "agent", null);
                    yield "Memory stored.";
                }
                case "recall" -> {
                    var results = memoryService.recall(owner, (String) cmd.get("query"), 5);
                    yield mapper.writeValueAsString(results);
                }
                case "list" -> mapper.writeValueAsString(memoryService.list(owner, 20));
                default -> "Unknown memory action: " + action;
            };
        } catch (Exception e) {
            return "Memory error: " + e.getMessage();
        }
    }

    private String runManageNotes(String input, String owner) {
        try {
            Map<String, Object> cmd = mapper.readValue(input, new TypeReference<>() {});
            String action = (String) cmd.getOrDefault("action", "list");
            return switch (action) {
                case "create" -> mapper.writeValueAsString(notesService.create(owner, cmd));
                case "list" -> mapper.writeValueAsString(notesService.list(owner, false, null));
                default -> "Unknown notes action: " + action;
            };
        } catch (Exception e) {
            return "Notes error: " + e.getMessage();
        }
    }

    private String runManageCalendar(String input, String owner) {
        try {
            Map<String, Object> cmd = mapper.readValue(input, new TypeReference<>() {});
            String action = (String) cmd.getOrDefault("action", "list");
            String from = (String) cmd.getOrDefault("from", "2000-01-01T00:00:00Z");
            String to = (String) cmd.getOrDefault("to", "2099-12-31T23:59:59Z");
            return switch (action) {
                case "list" -> mapper.writeValueAsString(calendarService.listEventsInRange(owner, from, to));
                default -> "Unknown calendar action: " + action;
            };
        } catch (Exception e) {
            return "Calendar error: " + e.getMessage();
        }
    }

    private String runManageTasks(String input, String owner) {
        try {
            Map<String, Object> cmd = mapper.readValue(input, new TypeReference<>() {});
            String action = (String) cmd.getOrDefault("action", "list");
            return switch (action) {
                case "create" -> mapper.writeValueAsString(taskService.create(owner, cmd));
                case "list" -> mapper.writeValueAsString(taskService.list(owner, null));
                default -> "Unknown tasks action: " + action;
            };
        } catch (Exception e) {
            return "Tasks error: " + e.getMessage();
        }
    }

    // ── LLM call ─────────────────────────────────────────────────────────────

    private String callLlm(String ollamaUrl, String model, List<Map<String, Object>> messages) {
        try {
            Map<String, Object> request = Map.of(
                    "model", model,
                    "messages", messages,
                    "stream", false,
                    "options", Map.of("num_predict", 4096)
            );
            String url = ollamaUrl.replaceAll("/+$", "") + "/api/chat";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(resp.body()).path("message").path("content").asText();
        } catch (Exception e) {
            log.warn("[agent] LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Tool parsing ──────────────────────────────────────────────────────────

    private List<Map<String, Object>> parseToolCalls(String text) {
        List<Map<String, Object>> calls = new ArrayList<>();
        Matcher m = TOOL_BLOCK.matcher(text);
        while (m.find()) {
            calls.add(Map.of("type", m.group(1).trim(), "input", m.group(2).trim()));
        }
        return calls;
    }

    // ── SSE helpers ───────────────────────────────────────────────────────────

    private void sendSseEvent(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.debug("[agent] SSE send failed: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
