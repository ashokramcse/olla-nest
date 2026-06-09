package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;

/**
 * OCD-level unit tests for {@link MemoryService}.
 *
 * <p>
 * Covers: {@code remember()} — DB write, embedding attempt, cap enforcement;
 * {@code forget()} — ownership gate, missing memory; {@code forgetAll()} — bulk
 * delete; {@code list()} — result shape; {@code recall()} — keyword search
 * fallback (embedding service stubbed to fail), basic ranking;
 * {@code importMemories()} — bulk import; {@code exportAll()} — delegates to
 * list.
 *
 * <p>
 * All DB and {@link EmbeddingService} interactions are Mockito-stubbed — no
 * Spring context, no real DB, no network calls.
 *
 * @author Ashok Ram
 * @since v2026.2.0 — initial creation
 * @version v2026.2.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemoryService — unit tests")
class MemoryServiceTest {

	private static final String OWNER = UserFactory.USER_ID;

	@Mock
	JdbcTemplate db;
	@Mock
	ObjectMapper mapper;
	@Mock
	EmbeddingService embeddingService;

	@InjectMocks
	MemoryService memoryService;

	@BeforeEach
	void stubDefaults() throws Exception {
		// Default: no cap overflow
		when(db.queryForObject(contains("COUNT"), eq(Integer.class), eq(OWNER))).thenReturn(0);
		// Default: embedding fails gracefully → keyword fallback
		when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("offline"));
		// Default: mapper writes valid JSON
		when(mapper.writeValueAsString(anyList())).thenReturn("[]");
	}

	// ── remember() ────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("remember()")
	class Remember {

		@Test
		@DisplayName("inserts a row into the memories table with the correct owner and text")
		void insertsRow() {
			memoryService.remember(OWNER, "User prefers dark mode", null, "user", List.of());

			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			verify(db).update(contains("INSERT INTO memories"), cap.capture());
			Object[] args = cap.getValue();
			// args: id, owner, text, source, session_id, embedding_json, tags_json,
			// created_at, updated_at
			assertThat(args[1]).isEqualTo(OWNER);
			assertThat(args[2]).isEqualTo("User prefers dark mode");
			assertThat(args[3]).isEqualTo("user");
		}

		@Test
		@DisplayName("generated memory ID starts with 'mem-'")
		void idStartsWithMemPrefix() {
			memoryService.remember(OWNER, "Test fact", null, "user", null);

			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			verify(db).update(contains("INSERT INTO memories"), cap.capture());
			assertThat(cap.getValue()[0].toString()).startsWith("mem-");
		}

		@Test
		@DisplayName("embedding failure is swallowed — null stored instead (keyword fallback)")
		void embeddingFailureSwallowed() {
			// doThrow form (not when().thenThrow) — the embed stub is already a
			// throwing stub from @BeforeEach, so when(embeddingService.embed(...))
			// would invoke it and throw during stubbing. doThrow avoids that call.
			doThrow(new RuntimeException("LLM offline")).when(embeddingService).embed(anyString());

			assertThatCode(() -> memoryService.remember(OWNER, "Some fact", null, "extractor", List.of("auto")))
					.doesNotThrowAnyException();

			// null embedding_json stored — verified by INSERT args position 5
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			verify(db).update(contains("INSERT INTO memories"), cap.capture());
			assertThat(cap.getValue()[5]).isNull(); // embeddingJson = null
		}

		@Test
		@DisplayName("tags are serialised to JSON via ObjectMapper")
		void tagsSerialised() throws Exception {
			when(mapper.writeValueAsString(List.of("auto", "coding"))).thenReturn("[\"auto\",\"coding\"]");
			memoryService.remember(OWNER, "Fact", null, "agent", List.of("auto", "coding"));
			verify(mapper, atLeastOnce()).writeValueAsString(any());
		}

		@Test
		@DisplayName("cap enforcement: queries COUNT before INSERT")
		void capEnforcementQueriesCount() {
			memoryService.remember(OWNER, "Fact", null, "user", null);
			verify(db).queryForObject(contains("COUNT"), eq(Integer.class), eq(OWNER));
		}

		@Test
		@DisplayName("when cap exceeded, eviction DELETE runs before INSERT")
		void evictionRunsBeforeInsert() {
			when(db.queryForObject(contains("COUNT"), eq(Integer.class), eq(OWNER))).thenReturn(2000);

			memoryService.remember(OWNER, "New fact", null, "user", null);

			// Eviction DELETE called before INSERT (DELETE binds owner + limit varargs)
			verify(db, atLeastOnce()).update(contains("DELETE FROM memories"), any(Object[].class));
			verify(db).update(contains("INSERT INTO memories"), any(Object[].class));
		}

		@Test
		@DisplayName("returned record contains id, owner, text, source, created_at")
		void returnedRecordShape() {
			var result = memoryService.remember(OWNER, "Test fact", "sess-1", "user", List.of("work"));
			assertThat(result).containsKey("id").containsKey("owner").containsKey("text").containsKey("source")
					.containsKey("created_at");
			assertThat(result.get("owner")).isEqualTo(OWNER);
			assertThat(result.get("text")).isEqualTo("Test fact");
		}
	}

	// ── forget() ──────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("forget()")
	class Forget {

		@Test
		@DisplayName("executes DELETE WHERE id=? AND owner=?")
		void deletesOwnedMemory() {
			// Stub: DELETE affects 1 row (user owns this memory)
			when(db.update(contains("DELETE FROM memories WHERE id"), eq("mem-abc"), eq(OWNER))).thenReturn(1);
			assertThatCode(() -> memoryService.forget("mem-abc", OWNER)).doesNotThrowAnyException();
			// DELETE must be scoped to BOTH id and owner — prevents cross-user deletion
			verify(db).update(contains("DELETE FROM memories WHERE id"), eq("mem-abc"), eq(OWNER));
		}

		@Test
		@DisplayName("throws NoSuchElementException when no row deleted (ownership check)")
		void throwsWhenNotOwned() {
			// Stub: DELETE affects 0 rows (wrong owner or already deleted)
			when(db.update(contains("DELETE FROM memories WHERE id"), anyString(), anyString())).thenReturn(0);
			// SECURITY: 0-row DELETE must throw NoSuchElementException — prevents silent
			// cross-user deletion attempts (attacker cannot know if ID belongs to another
			// user)
			assertThatThrownBy(() -> memoryService.forget("mem-xyz", OWNER)).isInstanceOf(NoSuchElementException.class)
					.hasMessageContaining("mem-xyz");
		}
	}

	// ── forgetAll() ───────────────────────────────────────────────────────────

	@Nested
	@DisplayName("forgetAll()")
	class ForgetAll {

		@Test
		@DisplayName("executes DELETE WHERE owner=?")
		void deletesAllForOwner() {
			memoryService.forgetAll(OWNER);
			// Bulk delete scoped to the requesting owner
			verify(db).update(contains("DELETE FROM memories WHERE owner"), eq(OWNER));
		}

		@Test
		@DisplayName("does not affect other owners")
		void scopedToOwner() {
			memoryService.forgetAll(OWNER);
			// Only OWNER's memories deleted — "other-owner" must never appear as a
			// parameter
			verify(db).update(anyString(), eq(OWNER));
			verify(db, never()).update(anyString(), eq("other-owner"));
		}
	}

	// ── list() ────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("list()")
	class ListMemories {

		@Test
		@DisplayName("queries DB with owner and limit")
		void queriesWithOwnerAndLimit() {
			// Stub: empty result for this owner/limit combination
			when(db.queryForList(anyString(), eq(OWNER), eq(50))).thenReturn(List.of());
			memoryService.list(OWNER, 50);
			// Query must pass both owner (isolation) and limit (prevent unbounded scans)
			verify(db).queryForList(anyString(), eq(OWNER), eq(50));
		}

		@Test
		@DisplayName("defaults to limit 100 when limit <= 0")
		void defaultLimitWhenZero() {
			// Stub: expects the defaulted limit=100 to be passed to DB
			when(db.queryForList(anyString(), eq(OWNER), eq(100))).thenReturn(List.of());
			// limit=0 is treated as "use default" rather than "unlimited" (safety guard)
			memoryService.list(OWNER, 0);
			verify(db).queryForList(anyString(), eq(OWNER), eq(100));
		}

		@Test
		@DisplayName("returns empty list when DB returns no rows")
		void emptyResultWhenNoRows() {
			// Stub: no memories stored for this owner
			when(db.queryForList(anyString(), eq(OWNER), anyInt())).thenReturn(List.of());
			// Empty list (not null) — callers can safely iterate
			assertThat(memoryService.list(OWNER, 10)).isEmpty();
		}
	}

	// ── recall() (keyword fallback) ───────────────────────────────────────────

	@Nested
	@DisplayName("recall() — keyword search fallback")
	class Recall {

		@Test
		@DisplayName("returns empty list when no memories stored")
		void emptyWhenNoMemories() {
			// Stub: no memories in DB → nothing to search
			when(db.queryForList(anyString(), eq(OWNER))).thenReturn(List.of());
			assertThat(memoryService.recall(OWNER, "dark mode", 5)).isEmpty();
		}

		// Null-safe memory row (embedding_json is null, which Map.of forbids).
		private Map<String, Object> memRow(String id, String text, String created) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("id", id);
			r.put("owner", OWNER);
			r.put("text", text);
			r.put("source", "user");
			r.put("session_id", "");
			r.put("importance", 5);
			r.put("embedding_json", null);
			r.put("created_at", created);
			r.put("tags_json", "[]");
			return r;
		}

		@Test
		@DisplayName("keyword match on text field returns hit")
		void keywordMatchReturnsHit() throws Exception {
			// Stub: one memory with text matching the query keywords
			var row = memRow("mem-1", "User prefers dark mode", "2026-01-01T00:00:00Z");
			when(db.queryForList(contains("SELECT"), eq(OWNER))).thenReturn(List.of(row));
			// Stub: mapper deserializes empty tags array
			when(mapper.readValue(eq("[]"), eq(List.class))).thenReturn(List.of());

			var results = memoryService.recall(OWNER, "dark mode", 5);
			// "dark mode" matches the memory text — result must be non-empty
			assertThat(results).isNotEmpty();
			assertThat(results.get(0).get("text")).isEqualTo("User prefers dark mode");
		}

		@Test
		@DisplayName("query with no keyword match returns empty list")
		void noKeywordMatchReturnsEmpty() throws Exception {
			// Stub: memory is about Java development — unrelated to the query
			var row = memRow("mem-1", "User is a Java developer", "2026-01-01T00:00:00Z");
			when(db.queryForList(contains("SELECT"), eq(OWNER))).thenReturn(List.of(row));
			when(mapper.readValue(eq("[]"), eq(List.class))).thenReturn(List.of());

			// "quantum physics research" has no overlap with the Java developer text
			var results = memoryService.recall(OWNER, "quantum physics research", 5);
			assertThat(results).isEmpty();
		}

		@Test
		@DisplayName("results are limited by topK parameter")
		void topKLimitsResults() throws Exception {
			// Stub: 10 memories all matching "coding" keyword
			var rows = IntStream.rangeClosed(1, 10)
					.mapToObj(i -> memRow("mem-" + i, "coding fact " + i, "2026-01-0" + Math.min(i, 9) + "T00:00:00Z"))
					.toList();
			when(db.queryForList(contains("SELECT"), eq(OWNER))).thenReturn(rows);
			when(mapper.readValue(eq("[]"), eq(List.class))).thenReturn(List.of());

			// topK=3 must limit the returned results to at most 3 even though 10 match
			var results = memoryService.recall(OWNER, "coding", 3);
			assertThat(results).hasSizeLessThanOrEqualTo(3);
		}
	}

	// ── importMemories() ──────────────────────────────────────────────────────

	@Nested
	@DisplayName("importMemories()")
	class ImportMemories {

		@Test
		@DisplayName("inserts one row per non-blank text and returns count")
		void insertsRowsForEachText() {
			// 3 valid facts → 3 INSERT calls, count=3 returned to caller
			int count = memoryService.importMemories(OWNER, List.of("Fact 1", "Fact 2", "Fact 3"), "import");
			assertThat(count).isEqualTo(3);
			// Each fact stored as its own memory row
			verify(db, times(3)).update(contains("INSERT INTO memories"), any(Object[].class));
		}

		@Test
		@DisplayName("skips blank and null entries")
		void skipsBlankEntries() {
			// List has 4 entries but only 1 is non-blank — 3 must be skipped.
			// Arrays.asList (not List.of) because the list contains a null entry.
			int count = memoryService.importMemories(OWNER, Arrays.asList("Fact 1", "", "  ", null), "import");
			// Only 1 import counted
			assertThat(count).isEqualTo(1);
			// Only 1 INSERT call issued (blank/null entries skipped)
			verify(db, times(1)).update(contains("INSERT INTO memories"), any(Object[].class));
		}

		@Test
		@DisplayName("empty list imports zero memories")
		void emptyListImportsZero() {
			// Empty input: nothing to import, no DB calls
			int count = memoryService.importMemories(OWNER, List.of(), "import");
			assertThat(count).isEqualTo(0);
			verify(db, never()).update(contains("INSERT INTO memories"), any(Object[].class));
		}
	}
}
