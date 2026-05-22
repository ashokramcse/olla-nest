package com.ollanest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Defines AI-callable tools and executes them.
 * Tools: get_datetime, calculate, search_knowledge_base, get_system_info
 */
@Service
public class FunctionCallService {

    private final RagService ragService;
    private final ObjectMapper mapper;

    public FunctionCallService(RagService ragService, ObjectMapper mapper) {
        this.ragService = ragService;
        this.mapper = mapper;
    }

    /** Returns the tools array to send to the AI model (Ollama format) */
    public List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(buildTool("get_datetime",
            "Get the current date and time. Use this when asked about the current date, time, day of week, or year.",
            Map.of("type", "object", "properties", Map.of(), "required", List.of())));

        tools.add(buildTool("calculate",
            "Evaluate a mathematical expression and return the numeric result. Use for arithmetic, percentages, unit conversions.",
            Map.of("type", "object",
                "properties", Map.of("expression", Map.of("type", "string", "description", "A valid mathematical expression, e.g. '(15 * 8) / 100 + 42'")),
                "required", List.of("expression"))));

        tools.add(buildTool("search_knowledge_base",
            "Search uploaded company documents and knowledge base for relevant information. Use when the user asks about internal documents, policies, or company-specific information.",
            Map.of("type", "object",
                "properties", Map.of("query", Map.of("type", "string", "description", "The search query to find relevant documents")),
                "required", List.of("query"))));

        tools.add(buildTool("get_system_info",
            "Get Olla Nest system information: version, available models count, uptime status.",
            Map.of("type", "object", "properties", Map.of(), "required", List.of())));

        return tools;
    }

    /** Execute a tool call by name with given arguments */
    public String executeTool(String toolName, Map<String, Object> args, String userId) {
        return switch (toolName) {
            case "get_datetime" -> executeGetDatetime();
            case "calculate" -> executeCalculate((String) args.getOrDefault("expression", ""));
            case "search_knowledge_base" -> executeSearchKnowledgeBase((String) args.getOrDefault("query", ""), userId);
            case "get_system_info" -> executeGetSystemInfo();
            default -> "{\"error\": \"Unknown tool: " + toolName + "\"}";
        };
    }

    /** Parse tool calls from Ollama's response message */
    public List<Map<String, Object>> parseToolCalls(JsonNode message) {
        List<Map<String, Object>> calls = new ArrayList<>();
        if (message == null) return calls;
        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls == null || !toolCalls.isArray()) return calls;
        for (JsonNode call : toolCalls) {
            JsonNode func = call.get("function");
            if (func == null) continue;
            String name = func.path("name").asText("");
            JsonNode argsNode = func.get("arguments");
            Map<String, Object> args = new LinkedHashMap<>();
            if (argsNode != null) {
                if (argsNode.isObject()) {
                    argsNode.fields().forEachRemaining(e -> args.put(e.getKey(), e.getValue().asText()));
                } else if (argsNode.isTextual()) {
                    try {
                        JsonNode parsed = mapper.readTree(argsNode.asText());
                        parsed.fields().forEachRemaining(e -> args.put(e.getKey(), e.getValue().asText()));
                    } catch (Exception ignored) {}
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

    private String executeGetDatetime() {
        ZonedDateTime now = ZonedDateTime.now();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("datetime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        result.put("date", now.format(DateTimeFormatter.ISO_LOCAL_DATE));
        result.put("time", now.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        result.put("dayOfWeek", now.getDayOfWeek().name());
        result.put("timezone", now.getZone().getId());
        try { return mapper.writeValueAsString(result); } catch (Exception e) { return "{\"datetime\":\"" + now + "\"}"; }
    }

    private String executeCalculate(String expression) {
        if (expression == null || expression.isBlank()) return "{\"error\": \"No expression provided\"}";
        // Safe evaluation: only allow numbers and basic operators
        String safe = expression.replaceAll("[^0-9+\\-*/().% ]", "");
        if (safe.isBlank()) return "{\"error\": \"Expression contains unsafe characters\"}";
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

    private String executeSearchKnowledgeBase(String query, String userId) {
        try {
            List<Map<String, Object>> results = ragService.retrieve(query, userId, 3);
            if (results.isEmpty()) return "{\"found\": false, \"message\": \"No relevant documents found\"}";
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

    private String executeGetSystemInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("product", "Olla Nest");
        info.put("version", "v2026.1.1");
        info.put("runtime", "Java 26 + Spring Boot 3.5.3");
        info.put("status", "healthy");
        try { return mapper.writeValueAsString(info); } catch (Exception e) { return "{\"product\":\"Olla Nest\"}"; }
    }

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
