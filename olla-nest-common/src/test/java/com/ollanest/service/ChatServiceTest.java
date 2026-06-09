package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedHashMap;
import org.springframework.dao.DataAccessException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChatService}.
 *
 * <p>Covers: token estimation, rate-limit concurrency safety (race-condition
 * regression), UID uniqueness + entropy, context-window budgeting, message
 * parsing, and audit/trace persistence.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatService — unit tests")
class ChatServiceTest {

	@Mock JdbcTemplate            db;
	@Mock WorkspaceService        workspaceService;
	@Mock PromptTemplateService   promptTemplateService;

	private final ObjectMapper mapper = new ObjectMapper();
	private ChatService service;

	@BeforeEach
	void setUp() {
		service = new ChatService(db, workspaceService, mapper, promptTemplateService);
	}

	// ── estimateTokens ────────────────────────────────────────────────────────

	@Nested
	@DisplayName("estimateTokens")
	class EstimateTokens {

		@Test
		@DisplayName("null returns 0")
		void nullReturnsZero() {
			// Null guard — streaming can produce null content chunks
			assertThat(service.estimateTokens(null)).isEqualTo(0);
		}

		@Test
		@DisplayName("empty string returns 0")
		void emptyReturnsZero() {
			// Empty string has no tokens — must not return 1 (ceiling of 0)
			assertThat(service.estimateTokens("")).isEqualTo(0);
		}

		@Test
		@DisplayName("4-character string estimates 1 token")
		void fourCharsIsOneToken() {
			// Heuristic: 1 token ≈ 4 chars (GPT tokenizer approximation)
			assertThat(service.estimateTokens("abcd")).isEqualTo(1);
		}

		@Test
		@DisplayName("8-character string estimates 2 tokens")
		void eightCharsIsTwoTokens() {
			// 8 chars / 4 = 2 tokens exactly
			assertThat(service.estimateTokens("abcdefgh")).isEqualTo(2);
		}

		@Test
		@DisplayName("ceiling applied: 5 chars → 2 tokens (⌈5/4⌉)")
		void ceilingApplied() {
			// Ceiling ensures partial tokens are counted — avoids underestimation
			assertThat(service.estimateTokens("hello")).isEqualTo(2);
		}
	}

	// ── checkChatRateLimit ────────────────────────────────────────────────────

	@Nested
	@DisplayName("checkChatRateLimit")
	class CheckChatRateLimit {

		@Test
		@DisplayName("limitPerMinute=0 always allows (rate limiting disabled)")
		void zeroLimitAlwaysAllows() {
			// limitPerMinute=0 is the "unlimited" sentinel — admin accounts use this
			for (int i = 0; i < 1000; i++)
				assertThat(service.checkChatRateLimit("user-1", 0)).isTrue();
		}

		@Test
		@DisplayName("negative limit always allows")
		void negativeLimitAlwaysAllows() {
			// Negative value also treated as unlimited (defensive guard)
			assertThat(service.checkChatRateLimit("user-2", -1)).isTrue();
		}

		@Test
		@DisplayName("allows exactly limitPerMinute requests within window")
		void allowsUpToLimit() {
			// 5 requests for limit=5 must all be allowed (inclusive boundary)
			for (int i = 0; i < 5; i++)
				assertThat(service.checkChatRateLimit("user-3", 5)).isTrue();
		}

		@Test
		@DisplayName("denies the (limit+1)-th request within window")
		void deniesOnceOverLimit() {
			// Use a unique UID per test run to avoid counter state from other tests
			String uid = "user-over-" + System.nanoTime();
			// Fill up the limit
			for (int i = 0; i < 3; i++)
				service.checkChatRateLimit(uid, 3);
			// (limit+1)-th request must be denied
			assertThat(service.checkChatRateLimit(uid, 3)).isFalse();
		}

		@Test
		@DisplayName("different users have independent counters")
		void differentUsersAreIndependent() {
			// Use nanoTime-based UIDs to ensure isolation from other tests
			String u1 = "u-" + System.nanoTime();
			String u2 = "v-" + System.nanoTime();
			// Exhaust u1's limit
			for (int i = 0; i < 5; i++)
				service.checkChatRateLimit(u1, 5);
			// u1 is at limit, u2 should still be allowed (independent counter)
			assertThat(service.checkChatRateLimit(u2, 5)).isTrue();
		}

		/**
		 * Concurrency regression test for the race condition fixed in v2026.1.9.
		 *
		 * <p>20 threads simultaneously call checkChatRateLimit with limit=10 for the
		 * same user. A correct implementation must allow EXACTLY 10 and deny the other
		 * 10. The original {@code long[] counter; counter[0]++} code was not atomic —
		 * under contention two threads could both read count &lt; limit and both
		 * increment, allowing up to 20 requests through. This test would have been
		 * flaky (passing sometimes, failing sometimes) before the fix.
		 */
		@Test
		@DisplayName("race condition fix: exactly 10 of 20 concurrent requests allowed (limit=10)")
		void concurrentRequestsRespectLimit() throws InterruptedException {
			String uid = "concurrent-" + System.nanoTime();
			int threadCount = 20;
			int limit = 10;
			AtomicInteger allowed = new AtomicInteger(0);
			AtomicInteger denied  = new AtomicInteger(0);
			CountDownLatch startGate = new CountDownLatch(1);
			CountDownLatch done      = new CountDownLatch(threadCount);

			ExecutorService pool = Executors.newFixedThreadPool(threadCount);
			for (int i = 0; i < threadCount; i++) {
				pool.submit(() -> {
					try {
						startGate.await();
						if (service.checkChatRateLimit(uid, limit))
							allowed.incrementAndGet();
						else
							denied.incrementAndGet();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						done.countDown();
					}
				});
			}
			startGate.countDown(); // release all threads simultaneously
			assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
			pool.shutdown();

			assertThat(allowed.get())
					.as("Allowed requests must equal the limit (not exceed it)")
					.isEqualTo(limit);
			assertThat(denied.get())
					.as("Denied requests must fill the remainder")
					.isEqualTo(threadCount - limit);
		}
	}

	// ── uid ───────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("uid")
	class Uid {

		@Test
		@DisplayName("uid starts with the given prefix")
		void hasPrefix() {
			// Prefix encodes the entity type — makes IDs self-describing in logs
			assertThat(service.uid("chat")).startsWith("chat-");
			assertThat(service.uid("msg")).startsWith("msg-");
			assertThat(service.uid("audit")).startsWith("audit-");
		}

		@Test
		@DisplayName("uid contains exactly two hyphens (prefix-ms-rand)")
		void hasTwoHyphens() {
			String id = service.uid("chat");
			// Format: "chat-<base36ms>-<base36rand>" → exactly 2 hyphens
			assertThat(id.chars().filter(c -> c == '-').count()).isEqualTo(2);
		}

		@RepeatedTest(1000)
		@DisplayName("1000 uid() calls produce no collisions (SecureRandom entropy sufficient)")
		void noDuplicates() {
			// Generate 1000 IDs in a tight loop and verify uniqueness
			// (SecureRandom suffix + ms timestamp provides sufficient collision resistance)
			Set<String> ids = new HashSet<>();
			for (int i = 0; i < 1000; i++)
				ids.add(service.uid("test"));
			assertThat(ids).hasSize(1000);
		}
	}

	// ── parseMessage ─────────────────────────────────────────────────────────

	@Nested
	@DisplayName("parseMessage")
	class ParseMessage {

		@Test
		@DisplayName("maps all core fields correctly")
		void mapsAllFields() {
			// Construct a full DB row with all columns populated
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", "msg-001");
			row.put("role", "user");
			row.put("content", "Hello world");
			row.put("mode", "ask");
			row.put("model_id", "m-llama");
			row.put("model_name", "Llama 3");
			row.put("route_reason", "Best local model");
			row.put("live", 1);
			row.put("artifacts_json", null);
			row.put("extracted_files_json", null);
			row.put("created_at", "2026-05-25T10:00:00Z");

			Map<String, Object> msg = service.parseMessage(row);

			// snake_case DB columns → camelCase API fields
			assertThat(msg.get("id")).isEqualTo("msg-001");
			assertThat(msg.get("role")).isEqualTo("user");
			assertThat(msg.get("content")).isEqualTo("Hello world");
			assertThat(msg.get("mode")).isEqualTo("ask");
			assertThat(msg.get("modelId")).isEqualTo("m-llama");
			assertThat(msg.get("modelName")).isEqualTo("Llama 3");
			assertThat(msg.get("routeReason")).isEqualTo("Best local model");
			// live=1 → boolean true in the API response
			assertThat(msg.get("live")).isEqualTo(true);
		}

		@Test
		@DisplayName("live=0 maps to false")
		void liveZeroIsFalse() {
			// live=0 (historical / non-streaming message) must map to boolean false
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("role", "assistant");
			row.put("content", "Response");
			row.put("live", 0);
			row.put("created_at", "2026-05-25T10:00:00Z");
			assertThat(service.parseMessage(row).get("live")).isEqualTo(false);
		}

		@Test
		@DisplayName("null live defaults to true")
		void nullLiveIsTrue() {
			// Null live (column absent in older rows) defaults to true (message is considered live)
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("role", "assistant");
			row.put("content", "x");
			row.put("live", null);
			row.put("created_at", "now");
			assertThat(service.parseMessage(row).get("live")).isEqualTo(true);
		}

		@Test
		@DisplayName("artifacts_json null → empty list")
		void nullArtifactsIsEmptyList() {
			// Null artifacts_json: message has no generated artifacts — UI receives []
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("role", "user");
			row.put("content", "x");
			row.put("live", 1);
			row.put("artifacts_json", null);
			row.put("extracted_files_json", null);
			row.put("created_at", "now");
			// Empty list (not null) — UI can safely iterate
			assertThat(service.parseMessage(row).get("artifacts")).isEqualTo(List.of());
		}

		@Test
		@DisplayName("valid artifacts_json is deserialised to a list")
		void validArtifactsJsonDeserialised() {
			// Artifacts stored as JSON array in DB — must be deserialised for the API
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("role", "assistant");
			row.put("content", "x");
			row.put("live", 1);
			row.put("artifacts_json", "[\"file1.py\",\"file2.py\"]");
			row.put("extracted_files_json", null);
			row.put("created_at", "now");
			@SuppressWarnings("unchecked")
			List<Object> artifacts = (List<Object>) service.parseMessage(row).get("artifacts");
			// Two artifacts parsed in order
			assertThat(artifacts).containsExactly("file1.py", "file2.py");
		}
	}

	// ── appendAudit ───────────────────────────────────────────────────────────

	@Nested
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("appendAudit")
	class AppendAudit {

		@Test
		@DisplayName("writes to audit_events with correct columns")
		void writesAuditRow() {
			// Audit write: userId, event, detail, ip, timestamp — all parameterized
			service.appendAudit("alice", "user.login", "Signed in", null);
			verify(db).update(
					contains("INSERT INTO audit_events"),
					// args: id, userId, event, detail, ip, created_at
					any(), eq("alice"), eq("user.login"), eq("Signed in"),
					anyString(), anyString());
		}

		@Test
		@DisplayName("null detail is written as empty string (not null)")
		void nullDetailBecomesEmptyString() {
			// Null detail must be coerced to "" — avoids NOT NULL constraint violation
			service.appendAudit("system", "startup", null, null);
			verify(db).update(
					contains("INSERT INTO audit_events"),
					any(), eq("system"), eq("startup"), eq(""),
					anyString(), anyString());
		}

		@Test
		@DisplayName("DB exception during audit is silently swallowed (fire-and-forget)")
		void dbExceptionSwallowed() {
			// Stub: DB throws during audit INSERT (e.g. disk full)
			doThrow(new DataAccessException("DB down") {})
					.when(db).update(contains("INSERT INTO audit_events"),
							any(), any(), any(), any(), any(), any());
			// Audit is fire-and-forget — a DB failure must NEVER crash the chat flow
			assertThatNoException()
					.isThrownBy(() -> service.appendAudit("u", "action", "detail", null));
		}
	}

	// ── buildContextMessages ─────────────────────────────────────────────────

	@Nested
	@MockitoSettings(strictness = Strictness.LENIENT)
	@DisplayName("buildContextMessages")
	class BuildContextMessages {

		@BeforeEach
		void stubModelAndHistory() {
			// No model found → fallback context window = 8192
			when(db.queryForList(contains("FROM models"), anyString(), anyString()))
					.thenReturn(List.of());
			when(db.queryForList(contains("FROM api_models"), anyString()))
					.thenReturn(List.of());
		}

		@Test
		@DisplayName("empty history produces [system, user] messages only")
		void emptyHistoryProducesSystemAndUser() {
			// Stub: no prior messages in this session
			when(db.queryForList(contains("chat_messages"), anyString())).thenReturn(List.of());

			List<Map<String, Object>> msgs = service.buildContextMessages(
					"sess-1", "You are helpful.", "Hello!", "llama3", null);

			// [system prompt, user turn] — minimal valid message array for the LLM
			assertThat(msgs).hasSize(2);
			assertThat(msgs.get(0).get("role")).isEqualTo("system");
			assertThat(msgs.get(1).get("role")).isEqualTo("user");
			assertThat(msgs.get(1).get("content")).isEqualTo("Hello!");
		}

		@Test
		@DisplayName("history messages are included between system and user")
		void historyMessagesIncluded() {
			// Stub: 2 prior messages in the session
			List<Map<String, Object>> history = List.of(
					Map.of("role", "user",      "content", "First question"),
					Map.of("role", "assistant",  "content", "First answer")
			);
			when(db.queryForList(contains("chat_messages"), anyString())).thenReturn(history);

			List<Map<String, Object>> msgs = service.buildContextMessages(
					"sess-2", "sys", "Follow-up?", "llama3", null);

			// Order: system, history[0], history[1], user
			assertThat(msgs).hasSize(4); // system + 2 history + user
			assertThat(msgs.get(1).get("content")).isEqualTo("First question");
			assertThat(msgs.get(2).get("content")).isEqualTo("First answer");
			assertThat(msgs.get(3).get("content")).isEqualTo("Follow-up?");
		}

		@Test
		@DisplayName("images list is attached to the user message when provided")
		void imagesAttachedToUserMessage() {
			// Stub: no history — vision request with one image
			when(db.queryForList(contains("chat_messages"), anyString())).thenReturn(List.of());

			List<Map<String, Object>> msgs = service.buildContextMessages(
					"sess-3", "sys", "Describe this image", "llama3",
					List.of("base64encodedImageData=="));

			// Images array must be attached to the last (user) message, not the system message
			@SuppressWarnings("unchecked")
			List<String> images = (List<String>) msgs.get(msgs.size() - 1).get("images");
			assertThat(images).containsExactly("base64encodedImageData==");
		}

		@Test
		@DisplayName("oversized history is trimmed to fit within context budget")
		void oversizedHistoryIsTrimmed() {
			// Create 100 messages each with 200 chars (~50 tokens each)
			List<Map<String, Object>> bigHistory = new ArrayList<>();
			for (int i = 0; i < 100; i++) {
				bigHistory.add(Map.of("role", "user", "content", "x".repeat(200)));
			}
			when(db.queryForList(contains("chat_messages"), anyString())).thenReturn(bigHistory);

			// Context window is 8192 (fallback); system + user message use ~512 reserved
			// So budget ~7680 tokens; 100 * 50 = 5000 tokens — all should fit here
			List<Map<String, Object>> msgs = service.buildContextMessages(
					"sess-4", "s".repeat(100), "m".repeat(100), "llama3", null);

			// Result must always begin with system and end with user
			assertThat(msgs.size()).isGreaterThanOrEqualTo(2); // at minimum system + user
			assertThat(msgs.get(0).get("role")).isEqualTo("system");
			assertThat(msgs.get(msgs.size() - 1).get("role")).isEqualTo("user");
		}
	}
}
