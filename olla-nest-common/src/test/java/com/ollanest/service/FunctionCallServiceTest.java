package com.ollanest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OCD-level unit tests for {@link FunctionCallService}.
 *
 * <p>Covers tool definitions, tool call parsing, and pure tool execution.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FunctionCallService — unit tests")
class FunctionCallServiceTest {

    private static final String USER_ID = UserFactory.USER_ID;

    @Mock RagService ragService;

    private final ObjectMapper realMapper = new ObjectMapper();

    // Use real mapper for FunctionCallService since it needs JSON parsing
    private FunctionCallService functionCallService;

    @BeforeEach
    void setUp() {
        functionCallService = new FunctionCallService(ragService, realMapper);
    }

    // ── getToolDefinitions() ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getToolDefinitions()")
    class GetToolDefinitions {

        @Test
        @DisplayName("returns non-empty list")
        void returnsNonEmpty() {
            // The LLM needs at least one tool to use function calling
            assertThat(functionCallService.getToolDefinitions()).isNotEmpty();
        }

        @Test
        @DisplayName("each tool has name and description keys")
        void eachHasNameAndDescription() {
            // OpenAI function-calling spec: each tool object must have function.name
            // and function.description for the model to understand what to invoke
            functionCallService.getToolDefinitions().forEach(tool -> {
                Map<String, Object> func = (Map<String, Object>) tool.get("function");
                assertThat(func).containsKey("name");
                assertThat(func).containsKey("description");
            });
        }

        @Test
        @DisplayName("includes get_datetime tool")
        void includesGetDatetime() {
            // get_datetime is the most frequently used built-in — must always be present
            assertThat(functionCallService.getToolDefinitions())
                    .anyMatch(t -> "get_datetime".equals(((Map<String, Object>) t.get("function")).get("name")));
        }

        @Test
        @DisplayName("includes calculate tool")
        void includesCalculate() {
            // calculate enables the LLM to delegate arithmetic to a safe evaluator
            assertThat(functionCallService.getToolDefinitions())
                    .anyMatch(t -> "calculate".equals(((Map<?, ?>) t.get("function")).get("name")));
        }

        @Test
        @DisplayName("includes search_knowledge_base tool")
        void includesSearchKnowledgeBase() {
            // search_knowledge_base enables RAG retrieval via function call
            assertThat(functionCallService.getToolDefinitions())
                    .anyMatch(t -> "search_knowledge_base".equals(((Map<?, ?>) t.get("function")).get("name")));
        }
    }

    // ── parseToolCalls() ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseToolCalls()")
    class ParseToolCalls {

        @Test
        @DisplayName("returns empty list for null message")
        void emptyForNull() {
            // Null guard — streaming responses can produce null message nodes
            assertThat(functionCallService.parseToolCalls(null)).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when no tool_calls array")
        void emptyWhenNoToolCalls() throws Exception {
            // Standard text response (no function call invoked by the model)
            JsonNode msg = realMapper.readTree("{\"role\":\"assistant\",\"content\":\"hello\"}");
            assertThat(functionCallService.parseToolCalls(msg)).isEmpty();
        }

        @Test
        @DisplayName("parses valid tool_calls array correctly")
        void parsesValidToolCalls() throws Exception {
            // Construct a message node matching the OpenAI tool_calls format
            String json = "{\"tool_calls\":[{\"function\":{\"name\":\"calculate\",\"arguments\":{\"expression\":\"2+2\"}}}]}";
            JsonNode msg = realMapper.readTree(json);
            List<Map<String, Object>> calls = functionCallService.parseToolCalls(msg);
            // Exactly one tool call extracted
            assertThat(calls).hasSize(1);
            assertThat(calls.get(0).get("name")).isEqualTo("calculate");
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) calls.get(0).get("args");
            // args map populated from the "arguments" node
            assertThat(args.get("expression")).isEqualTo("2+2");
        }
    }

    // ── executeTool() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("executeTool()")
    class ExecuteTool {

        @Test
        @DisplayName("get_datetime returns non-null string with date info")
        void getDatetime() {
            // No external dependencies — returns current system time as JSON string
            String result = functionCallService.executeTool("get_datetime", Map.of(), USER_ID);
            // Result must be a non-null JSON-ish string containing the "datetime" key
            assertThat(result).isNotNull().contains("datetime");
        }

        @Test
        @DisplayName("calculate returns result for simple expression 2+2")
        void calculateSimpleExpression() {
            // Simple arithmetic expression evaluated by the built-in calculator
            String result = functionCallService.executeTool("calculate",
                    Map.of("expression", "2+2"), USER_ID);
            // Result string must contain the numeric answer
            assertThat(result).contains("4");
        }

        @Test
        @DisplayName("unknown tool returns error response without exception")
        void unknownToolNoException() {
            // Unknown tool names must return a graceful error, not throw (would crash the LLM loop)
            assertThatCode(() -> {
                String result = functionCallService.executeTool("nonexistent_tool", Map.of(), USER_ID);
                assertThat(result).contains("Unknown tool");
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("get_system_info returns Olla Nest product info")
        void getSystemInfo() {
            // get_system_info is used by the LLM to describe the platform to users
            String result = functionCallService.executeTool("get_system_info", Map.of(), USER_ID);
            assertThat(result).contains("Olla Nest");
        }

        @Test
        @DisplayName("get_system_info does NOT leak env vars / secrets (exfiltration guard)")
        void getSystemInfoNoSecretLeak() {
            // SECURITY: a tool-call exfiltration vector would be get_system_info dumping
            // System.getenv(). It must expose only static product metadata.
            String result = functionCallService.executeTool("get_system_info", Map.of(), USER_ID).toLowerCase();
            assertThat(result)
                    .doesNotContain("api_key").doesNotContain("apikey").doesNotContain("password")
                    .doesNotContain("secret").doesNotContain("token").doesNotContain("encryption_key")
                    .doesNotContain("anthropic").doesNotContain("openai");
        }

        @Test
        @DisplayName("search_knowledge_base scopes retrieval to the user's personal docs (BUG-018)")
        void searchKnowledgeBaseUsesPersonalScope() {
            when(ragService.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());
            functionCallService.executeTool("search_knowledge_base",
                    Map.of("query", "budget"), USER_ID);
            // Must query with the personal scope, not the raw user id, or the user's
            // own uploads (scope "personal:{id}") are silently never retrieved.
            verify(ragService).retrieve(eq("budget"), eq("personal:" + USER_ID), anyInt());
        }
    }
}
