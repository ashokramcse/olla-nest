package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;

/**
 * OCD-level unit tests for {@link FunctionCallService}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Pins the contract the LLM function-calling loop depends on: the advertised
 * tool definitions, parsing of OpenAI-style {@code tool_calls} from a model
 * message, and the pure execution of built-in tools. A regression here would
 * silently break tool use (the model can no longer invoke tools) or — worse —
 * leak secrets via {@code get_system_info}, so these paths are exhaustively
 * asserted with a real JSON mapper.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>A real {@link ObjectMapper} is used (not a mock) because the service does
 * genuine JSON parsing that the tests need to exercise end-to-end.</li>
 * <li>{@link RagService} is mocked so the knowledge-base tool can be verified
 * for correct scoping without touching the vector store.</li>
 * <li>Lenient strictness avoids unnecessary-stubbing failures across nested
 * groups that share setup.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — initial creation; documented in the project-wide Javadoc
 * pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FunctionCallService — unit tests")
class FunctionCallServiceTest {

	/** Stable test user id used as the tool-execution caller. */
	private static final String USER_ID = UserFactory.USER_ID;

	/** Mocked RAG service used to assert knowledge-base retrieval scoping. */
	@Mock
	RagService ragService;

	/** Real JSON mapper — the service performs genuine parsing under test. */
	private final ObjectMapper realMapper = new ObjectMapper();

	/** System under test, constructed manually with the real mapper. */
	private FunctionCallService functionCallService;

	/**
	 * Constructs the service with the mocked RAG service and a real JSON mapper.
	 *
	 * <p>
	 * A real mapper is wired in (rather than {@code @InjectMocks}) because
	 * {@link FunctionCallService} needs working JSON parsing for the
	 * {@code parseToolCalls} tests.
	 *
	 * @since v2026.2.1
	 * @author Ashok Ram
	 * @version v2026.2.1
	 */
	@BeforeEach
	void setUp() {
		functionCallService = new FunctionCallService(ragService, realMapper);
	}

	// ── getToolDefinitions() ──────────────────────────────────────────────────

	/**
	 * Groups tests for {@link FunctionCallService#getToolDefinitions()} — the tool
	 * catalogue advertised to the model.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("getToolDefinitions()")
	class GetToolDefinitions {

		/**
		 * Verifies the tool catalogue is non-empty.
		 *
		 * <p>
		 * The model needs at least one tool for function calling to be useful, so an
		 * empty catalogue would be a regression.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns non-empty list")
		void returnsNonEmpty() {
			// The LLM needs at least one tool to use function calling
			assertThat(functionCallService.getToolDefinitions()).isNotEmpty();
		}

		/**
		 * Verifies every tool exposes {@code function.name} and
		 * {@code function.description}.
		 *
		 * <p>
		 * The OpenAI function-calling spec requires both keys so the model knows what
		 * each tool is and when to invoke it.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("each tool has name and description keys")
		@SuppressWarnings("unchecked")
		void eachHasNameAndDescription() {
			// OpenAI function-calling spec: each tool object must have function.name
			// and function.description for the model to understand what to invoke
			functionCallService.getToolDefinitions().forEach(tool -> {
				Map<String, Object> func = (Map<String, Object>) tool.get("function");
				assertThat(func).containsKey("name");
				assertThat(func).containsKey("description");
			});
		}

		/**
		 * Verifies the catalogue includes the {@code get_datetime} built-in.
		 *
		 * <p>
		 * {@code get_datetime} is the most frequently invoked built-in and must always
		 * be advertised.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("includes get_datetime tool")
		@SuppressWarnings("unchecked")
		void includesGetDatetime() {
			// get_datetime is the most frequently used built-in — must always be present
			assertThat(functionCallService.getToolDefinitions())
					.anyMatch(t -> "get_datetime".equals(((Map<String, Object>) t.get("function")).get("name")));
		}

		/**
		 * Verifies the catalogue includes the {@code calculate} built-in.
		 *
		 * <p>
		 * {@code calculate} lets the model delegate arithmetic to a safe evaluator
		 * instead of hallucinating results.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("includes calculate tool")
		void includesCalculate() {
			// calculate enables the LLM to delegate arithmetic to a safe evaluator
			assertThat(functionCallService.getToolDefinitions())
					.anyMatch(t -> "calculate".equals(((Map<?, ?>) t.get("function")).get("name")));
		}

		/**
		 * Verifies the catalogue includes the {@code search_knowledge_base} built-in.
		 *
		 * <p>
		 * {@code search_knowledge_base} exposes RAG retrieval to the model via a
		 * function call and must be advertised.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("includes search_knowledge_base tool")
		void includesSearchKnowledgeBase() {
			// search_knowledge_base enables RAG retrieval via function call
			assertThat(functionCallService.getToolDefinitions())
					.anyMatch(t -> "search_knowledge_base".equals(((Map<?, ?>) t.get("function")).get("name")));
		}
	}

	// ── parseToolCalls() ─────────────────────────────────────────────────────

	/**
	 * Groups tests for {@link FunctionCallService#parseToolCalls(JsonNode)} —
	 * extracting tool invocations from a model message node.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("parseToolCalls()")
	class ParseToolCalls {

		/**
		 * Verifies a {@code null} message node yields an empty list.
		 *
		 * <p>
		 * Null guard — streaming responses can produce null message nodes, which must
		 * not crash the tool loop.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns empty list for null message")
		void emptyForNull() {
			// Null guard — streaming responses can produce null message nodes
			assertThat(functionCallService.parseToolCalls(null)).isEmpty();
		}

		/**
		 * Verifies a plain assistant message with no {@code tool_calls} yields empty.
		 *
		 * <p>
		 * A normal text reply invokes no tools, so parsing must return an empty list.
		 *
		 * @throws Exception if the test JSON fails to parse
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns empty list when no tool_calls array")
		void emptyWhenNoToolCalls() throws Exception {
			// Standard text response (no function call invoked by the model)
			JsonNode msg = realMapper.readTree("{\"role\":\"assistant\",\"content\":\"hello\"}");
			assertThat(functionCallService.parseToolCalls(msg)).isEmpty();
		}

		/**
		 * Verifies a well-formed {@code tool_calls} array is parsed into name+args.
		 *
		 * <p>
		 * Proves the parser extracts exactly one call, reads the function name, and
		 * populates the {@code args} map from the {@code arguments} node.
		 *
		 * @throws Exception if the test JSON fails to parse
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
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

	/**
	 * Groups tests for
	 * {@link FunctionCallService#executeTool(String, Map, String)} — pure
	 * execution of each built-in tool.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("executeTool()")
	class ExecuteTool {

		/**
		 * Verifies {@code get_datetime} returns a non-null string carrying date info.
		 *
		 * <p>
		 * The tool has no external dependencies and must return the current system time
		 * as a JSON-ish string containing the {@code datetime} key.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("get_datetime returns non-null string with date info")
		void getDatetime() {
			// No external dependencies — returns current system time as JSON string
			String result = functionCallService.executeTool("get_datetime", Map.of(), USER_ID);
			// Result must be a non-null JSON-ish string containing the "datetime" key
			assertThat(result).isNotNull().contains("datetime");
		}

		/**
		 * Verifies {@code calculate} evaluates a simple expression correctly.
		 *
		 * <p>
		 * Proves the built-in calculator evaluates {@code 2+2} and surfaces the numeric
		 * answer in its result string.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("calculate returns result for simple expression 2+2")
		void calculateSimpleExpression() {
			// Simple arithmetic expression evaluated by the built-in calculator
			String result = functionCallService.executeTool("calculate", Map.of("expression", "2+2"), USER_ID);
			// Result string must contain the numeric answer
			assertThat(result).contains("4");
		}

		/**
		 * Verifies an unknown tool name returns a graceful error, never throws.
		 *
		 * <p>
		 * An exception here would crash the LLM tool loop, so the service must return an
		 * "Unknown tool" response instead.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("unknown tool returns error response without exception")
		void unknownToolNoException() {
			// Unknown tool names must return a graceful error, not throw (would crash the
			// LLM loop)
			assertThatCode(() -> {
				String result = functionCallService.executeTool("nonexistent_tool", Map.of(), USER_ID);
				assertThat(result).contains("Unknown tool");
			}).doesNotThrowAnyException();
		}

		/**
		 * Verifies {@code get_system_info} returns the Olla Nest product description.
		 *
		 * <p>
		 * The model uses this tool to describe the platform to users, so the response
		 * must contain the product name.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("get_system_info returns Olla Nest product info")
		void getSystemInfo() {
			// get_system_info is used by the LLM to describe the platform to users
			String result = functionCallService.executeTool("get_system_info", Map.of(), USER_ID);
			assertThat(result).contains("Olla Nest");
		}

		/**
		 * Verifies {@code get_system_info} never leaks environment secrets.
		 *
		 * <p>
		 * Security guard: a tool-call exfiltration vector would be {@code get_system_info}
		 * dumping {@code System.getenv()}. This proves the response contains only static
		 * product metadata and none of the sensitive key/secret/provider tokens.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("get_system_info does NOT leak env vars / secrets (exfiltration guard)")
		void getSystemInfoNoSecretLeak() {
			// SECURITY: a tool-call exfiltration vector would be get_system_info dumping
			// System.getenv(). It must expose only static product metadata.
			String result = functionCallService.executeTool("get_system_info", Map.of(), USER_ID).toLowerCase();
			assertThat(result).doesNotContain("api_key").doesNotContain("apikey").doesNotContain("password")
					.doesNotContain("secret").doesNotContain("token").doesNotContain("encryption_key")
					.doesNotContain("anthropic").doesNotContain("openai");
		}

		/**
		 * Verifies {@code search_knowledge_base} scopes retrieval to personal docs
		 * (BUG-018).
		 *
		 * <p>
		 * Proves the tool calls {@link RagService#retrieve} with the {@code personal:<id>}
		 * scope rather than the bare user id; otherwise the user's own uploads are
		 * silently never retrieved.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("search_knowledge_base scopes retrieval to the user's personal docs (BUG-018)")
		void searchKnowledgeBaseUsesPersonalScope() {
			when(ragService.retrieve(anyString(), anyString(), anyInt())).thenReturn(List.of());
			functionCallService.executeTool("search_knowledge_base", Map.of("query", "budget"), USER_ID);
			// Must query with the personal scope, not the raw user id, or the user's
			// own uploads (scope "personal:{id}") are silently never retrieved.
			verify(ragService).retrieve(eq("budget"), eq("personal:" + USER_ID), anyInt());
		}
	}
}
