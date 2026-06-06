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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    @org.junit.jupiter.api.BeforeEach
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
            assertThat(functionCallService.getToolDefinitions()).isNotEmpty();
        }

        @Test
        @DisplayName("each tool has name and description keys")
        void eachHasNameAndDescription() {
            functionCallService.getToolDefinitions().forEach(tool -> {
                Map<?, ?> func = (Map<?, ?>) tool.get("function");
                assertThat(func).containsKey("name");
                assertThat(func).containsKey("description");
            });
        }

        @Test
        @DisplayName("includes get_datetime tool")
        void includesGetDatetime() {
            assertThat(functionCallService.getToolDefinitions())
                    .anyMatch(t -> "get_datetime".equals(((Map<?, ?>) t.get("function")).get("name")));
        }

        @Test
        @DisplayName("includes calculate tool")
        void includesCalculate() {
            assertThat(functionCallService.getToolDefinitions())
                    .anyMatch(t -> "calculate".equals(((Map<?, ?>) t.get("function")).get("name")));
        }

        @Test
        @DisplayName("includes search_knowledge_base tool")
        void includesSearchKnowledgeBase() {
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
            assertThat(functionCallService.parseToolCalls(null)).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when no tool_calls array")
        void emptyWhenNoToolCalls() throws Exception {
            JsonNode msg = realMapper.readTree("{\"role\":\"assistant\",\"content\":\"hello\"}");
            assertThat(functionCallService.parseToolCalls(msg)).isEmpty();
        }

        @Test
        @DisplayName("parses valid tool_calls array correctly")
        void parsesValidToolCalls() throws Exception {
            String json = "{\"tool_calls\":[{\"function\":{\"name\":\"calculate\",\"arguments\":{\"expression\":\"2+2\"}}}]}";
            JsonNode msg = realMapper.readTree(json);
            List<Map<String, Object>> calls = functionCallService.parseToolCalls(msg);
            assertThat(calls).hasSize(1);
            assertThat(calls.get(0).get("name")).isEqualTo("calculate");
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) calls.get(0).get("args");
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
            String result = functionCallService.executeTool("get_datetime", Map.of(), USER_ID);
            assertThat(result).isNotNull().contains("datetime");
        }

        @Test
        @DisplayName("calculate returns result for simple expression 2+2")
        void calculateSimpleExpression() {
            String result = functionCallService.executeTool("calculate",
                    Map.of("expression", "2+2"), USER_ID);
            assertThat(result).contains("4");
        }

        @Test
        @DisplayName("unknown tool returns error response without exception")
        void unknownToolNoException() {
            assertThatCode(() -> {
                String result = functionCallService.executeTool("nonexistent_tool", Map.of(), USER_ID);
                assertThat(result).contains("Unknown tool");
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("get_system_info returns Olla Nest product info")
        void getSystemInfo() {
            String result = functionCallService.executeTool("get_system_info", Map.of(), USER_ID);
            assertThat(result).contains("Olla Nest");
        }
    }
}
