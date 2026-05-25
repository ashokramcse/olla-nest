package com.ollanest.service;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Defines AI-callable tool specifications and executes tool calls dispatched by
 * the Ollama function-calling protocol.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Ollama models that support function/tool calling return structured
 * {@code tool_calls} objects in their response instead of (or in addition to)
 * plain text. This service is the single location where those tool definitions
 * are declared and where the corresponding business logic is executed, keeping
 * the streaming controllers free of tool-specific code.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Tool definitions are built at call time (not cached) so they are always
 * consistent with the current state of the service. The overhead is negligible
 * because the list is small and allocation is cheap.</li>
 * <li>The {@code calculate} tool passes the expression through a whitelist
 * regex before evaluation to prevent injection attacks via the JavaScript
 * engine.</li>
 * <li>The JavaScript engine ({@code ScriptEngineManager}) is used for
 * expression evaluation because Java 21 ships GraalJS via the JDK; if no engine
 * is available a graceful fallback message is returned.</li>
 * <li>{@link #parseToolCalls(JsonNode)} handles both object and text-encoded
 * (doubly-serialised) argument formats because different Ollama model versions
 * emit different JSON shapes.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial migration from Node.js function-call handler</li>
 * <li>v2026.1.4 — added {@code search_knowledge_base} and
 * {@code get_system_info} tools; added double-serialised argument parsing in
 * {@link #parseToolCalls(JsonNode)}</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.4
 */
@Service
public class FunctionCallService {

	/** RAG retrieval service used by the {@code search_knowledge_base} tool. */
	private final RagService ragService;

	/** Jackson mapper for serialising tool results to JSON strings. */
	private final ObjectMapper mapper;

	/**
	 * Constructs the service with its required collaborators.
	 *
	 * @param ragService the RAG service used for knowledge base search
	 * @param mapper     the Jackson {@link ObjectMapper} for JSON serialisation
	 * @since v2026.1.0
	 */
	public FunctionCallService(RagService ragService, ObjectMapper mapper) {
		this.ragService = ragService;
		this.mapper = mapper;
	}

	/**
	 * Returns the list of tool definitions to send to the AI model in Ollama's
	 * function-calling format.
	 *
	 * <p>
	 * Defines the following tools:
	 * <ul>
	 * <li>{@code get_datetime} — returns the current date/time with timezone</li>
	 * <li>{@code calculate} — evaluates a safe mathematical expression</li>
	 * <li>{@code search_knowledge_base} — searches uploaded RAG documents</li>
	 * <li>{@code get_system_info} — returns Olla Nest version and status</li>
	 * </ul>
	 *
	 * @return a list of tool-definition maps in Ollama's
	 *         {@code {type: "function", function: {...}}} format
	 * @since v2026.1.0
	 */
	public List<Map<String, Object>> getToolDefinitions() {
		List<Map<String, Object>> tools = new ArrayList<>();

		tools.add(buildTool("get_datetime",
				"Get the current date and time. Use this when asked about the current date, time, day of week, or year.",
				Map.of("type", "object", "properties", Map.of(), "required", List.of())));

		tools.add(buildTool("calculate",
				"Evaluate a mathematical expression and return the numeric result. Use for arithmetic, percentages, unit conversions.",
				Map.of("type", "object", "properties",
						Map.of("expression",
								Map.of("type", "string", "description",
										"A valid mathematical expression, e.g. '(15 * 8) / 100 + 42'")),
						"required", List.of("expression"))));

		tools.add(buildTool("search_knowledge_base",
				"Search uploaded company documents and knowledge base for relevant information. "
						+ "Use when the user asks about internal documents, policies, or company-specific information.",
				Map.of("type", "object", "properties",
						Map.of("query",
								Map.of("type", "string", "description", "The search query to find relevant documents")),
						"required", List.of("query"))));

		tools.add(buildTool("get_system_info",
				"Get Olla Nest system information: version, available models count, uptime status.",
				Map.of("type", "object", "properties", Map.of(), "required", List.of())));

		return tools;
	}

	/**
	 * Executes a named tool call with the provided arguments.
	 *
	 * <p>
	 * Dispatches to the appropriate private implementation method based on
	 * {@code toolName}. Returns a JSON string in all cases — either a success
	 * payload or an {@code {"error": "..."}} object.
	 *
	 * @param toolName the name of the tool to execute (e.g. {@code "calculate"})
	 * @param args     a map of argument name to value as parsed from the model
	 *                 response; must not be {@code null}
	 * @param userId   the requesting user's ID, used by tools that are user-scoped
	 *                 (e.g. {@code search_knowledge_base})
	 * @return a JSON string representing the tool result
	 * @since v2026.1.0
	 */
	public String executeTool(String toolName, Map<String, Object> args, String userId) {
		return switch (toolName) {
		case "get_datetime" -> executeGetDatetime();
		case "calculate" -> executeCalculate((String) args.getOrDefault("expression", ""));
		case "search_knowledge_base" -> executeSearchKnowledgeBase((String) args.getOrDefault("query", ""), userId);
		case "get_system_info" -> executeGetSystemInfo();
		default -> "{\"error\": \"Unknown tool: " + toolName + "\"}";
		};
	}

	/**
	 * Parses the {@code tool_calls} array from an Ollama model response message
	 * node.
	 *
	 * <p>
	 * Each entry in the returned list is a map with keys {@code name} (the tool
	 * function name) and {@code args} (a map of argument name to string value).
	 * Handles both directly-object argument nodes and text-encoded
	 * (doubly-serialised) argument nodes produced by some Ollama model versions.
	 *
	 * @param message the {@code message} JSON node from the Ollama response; may be
	 *                {@code null}
	 * @return a list of parsed tool-call maps, empty if no tool calls are present
	 *         or {@code message} is {@code null}
	 * @since v2026.1.4
	 */
	public List<Map<String, Object>> parseToolCalls(JsonNode message) {
		List<Map<String, Object>> calls = new ArrayList<>();
		if (message == null)
			return calls;
		JsonNode toolCalls = message.get("tool_calls");
		if (toolCalls == null || !toolCalls.isArray())
			return calls;
		for (JsonNode call : toolCalls) {
			JsonNode func = call.get("function");
			if (func == null)
				continue;
			String name = func.path("name").asText("");
			JsonNode argsNode = func.get("arguments");
			Map<String, Object> args = new LinkedHashMap<>();
			if (argsNode != null) {
				if (argsNode.isObject()) {
					argsNode.properties().forEach(e -> args.put(e.getKey(), e.getValue().asText()));
				} else if (argsNode.isTextual()) {
					try {
						JsonNode parsed = mapper.readTree(argsNode.asText());
						parsed.properties().forEach(e -> args.put(e.getKey(), e.getValue().asText()));
					} catch (Exception ignored) {
					}
				}
			}
			Map<String, Object> tc = new LinkedHashMap<>();
			tc.put("name", name);
			tc.put("args", args);
			calls.add(tc);
		}
		return calls;
	}

	// --- Tool implementations ---

	/**
	 * Returns a JSON object containing the current date, time, day-of-week, and
	 * timezone.
	 *
	 * @return a JSON string with keys {@code datetime}, {@code date}, {@code time},
	 *         {@code dayOfWeek}, and {@code timezone}
	 */
	private String executeGetDatetime() {
		ZonedDateTime now = ZonedDateTime.now();
		Map<String, String> result = new LinkedHashMap<>();
		result.put("datetime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		result.put("date", now.format(DateTimeFormatter.ISO_LOCAL_DATE));
		result.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
		result.put("dayOfWeek", now.getDayOfWeek().name());
		result.put("timezone", now.getZone().getId());
		try {
			return mapper.writeValueAsString(result);
		} catch (Exception e) {
			return "{\"datetime\":\"" + now + "\"}";
		}
	}

	/**
	 * Evaluates a mathematical expression string and returns the numeric result.
	 *
	 * <p>
	 * The expression is sanitised by stripping all characters except digits, basic
	 * arithmetic operators, parentheses, the percent sign, and spaces before being
	 * passed to the JavaScript engine. Expressions containing only unsafe
	 * characters after sanitisation are rejected.
	 *
	 * @param expression the mathematical expression to evaluate
	 * @return a JSON string with keys {@code expression} and {@code result}, or
	 *         {@code {"error": "..."}} on failure
	 */
	private String executeCalculate(String expression) {
		if (expression == null || expression.isBlank())
			return "{\"error\": \"No expression provided\"}";
		// Safe evaluation: only allow numbers and basic operators
		String safe = expression.replaceAll("[^0-9+\\-*/().% ]", "");
		if (safe.isBlank())
			return "{\"error\": \"Expression contains unsafe characters\"}";
		try {
			javax.script.ScriptEngineManager mgr = new javax.script.ScriptEngineManager();
			javax.script.ScriptEngine engine = mgr.getEngineByName("JavaScript");
			if (engine == null) {
				return "{\"result\": \"Script engine unavailable — try a simpler expression\"}";
			}
			Object result = engine.eval(safe);
			return "{\"expression\": \"" + expression + "\", \"result\": " + result + "}";
		} catch (Exception e) {
			return "{\"error\": \"Could not evaluate: " + e.getMessage().replace("\"", "'") + "\"}";
		}
	}

	/**
	 * Searches the user's RAG knowledge base for documents relevant to the query
	 * and returns the top three results.
	 *
	 * @param query  the natural-language search query
	 * @param userId the requesting user's ID (scopes the search to their documents)
	 * @return a JSON string with keys {@code found}, {@code count}, and
	 *         {@code sources} (each with {@code source}, {@code excerpt},
	 *         {@code relevance}), or {@code {"found": false}} if no results
	 */
	private String executeSearchKnowledgeBase(String query, String userId) {
		try {
			List<Map<String, Object>> results = ragService.retrieve(query, userId, 3);
			if (results.isEmpty())
				return "{\"found\": false, \"message\": \"No relevant documents found\"}";
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("found", true);
			response.put("count", results.size());
			List<Map<String, Object>> sources = new ArrayList<>();
			for (Map<String, Object> r : results) {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("source", r.get("docName"));
				String content = r.get("content").toString();
				s.put("excerpt", content.substring(0, Math.min(300, content.length())) + "...");
				s.put("relevance", r.get("score"));
				sources.add(s);
			}
			response.put("sources", sources);
			return mapper.writeValueAsString(response);
		} catch (Exception e) {
			return "{\"error\": \"Search failed: " + e.getMessage().replace("\"", "'") + "\"}";
		}
	}

	/**
	 * Returns a JSON object with Olla Nest product, version, runtime, and status
	 * information.
	 *
	 * @return a JSON string with keys {@code product}, {@code version},
	 *         {@code runtime}, and {@code status}
	 */
	private String executeGetSystemInfo() {
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("product", "Olla Nest");
		info.put("version", "v2026.1.1");
		info.put("runtime", "Java 26 + Spring Boot 3.5.3");
		info.put("status", "healthy");
		try {
			return mapper.writeValueAsString(info);
		} catch (Exception e) {
			return "{\"product\":\"Olla Nest\"}";
		}
	}

	/**
	 * Assembles a single tool-definition map in Ollama's function-calling format.
	 *
	 * @param name        the tool function name
	 * @param description a natural-language description of when to use this tool
	 * @param parameters  the JSON Schema object describing the function parameters
	 * @return a map with {@code type="function"} and a nested {@code function} map
	 */
	private Map<String, Object> buildTool(String name, String description, Map<String, Object> parameters) {
		Map<String, Object> func = new LinkedHashMap<>();
		func.put("name", name);
		func.put("description", description);
		func.put("parameters", parameters);
		Map<String, Object> tool = new LinkedHashMap<>();
		tool.put("type", "function");
		tool.put("function", func);
		return tool;
	}
}
