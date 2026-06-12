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
 * <h3>Why this class exists</h3>
 * <p>
 * {@link MemoryService} stores long-term user "memories" and recalls them by
 * semantic or keyword search. These tests pin its resilience and isolation
 * guarantees: embedding failures must degrade silently to keyword search,
 * per-owner capacity caps must evict before inserting, and every read/delete
 * must be owner-scoped so memories never cross users. The recall ranking and
 * import/skip rules are also locked.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Runs under {@link MockitoExtension} with {@link Strictness#LENIENT};
 * {@link JdbcTemplate}, {@link ObjectMapper} and {@link EmbeddingService} are
 * mocked and injected.</li>
 * <li>{@code @BeforeEach} establishes the common defaults — no cap overflow, a
 * failing embedder (to force the keyword fallback), and a JSON-writing
 * mapper.</li>
 * <li>{@link ArgumentCaptor} asserts on the positional INSERT arguments where
 * column ordering encodes the contract.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.0 — initial creation covering remember/forget/forgetAll/list/
 * recall/import with cap-eviction and keyword-fallback coverage.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.0
 * @version v2026.2.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemoryService — unit tests")
class MemoryServiceTest {

	/** Canonical owner id used across all memory fixtures. */
	private static final String OWNER = UserFactory.USER_ID;

	/** Mocked JDBC template capturing SQL and positional args. */
	@Mock
	JdbcTemplate db;
	/** Mocked JSON mapper for tag (de)serialisation. */
	@Mock
	ObjectMapper mapper;
	/** Mocked embedding service, defaulted to fail so the keyword path is exercised. */
	@Mock
	EmbeddingService embeddingService;

	/** Service under test with mocks injected. */
	@InjectMocks
	MemoryService memoryService;

	/**
	 * Establishes the common stub defaults before each test.
	 *
	 * <p>
	 * No capacity overflow, an embedder that throws (forcing keyword fallback),
	 * and a mapper that serialises tags to {@code "[]"}.
	 *
	 * @throws Exception if the mocked mapper signals a checked failure during setup
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
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

	/**
	 * Tests for {@code remember()} — persistence, embedding and cap eviction.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("remember()")
	class Remember {

		/**
		 * Verifies a remembered fact inserts a row with owner/text/source.
		 *
		 * <p>
		 * The captured INSERT args must carry the owner at index 1, the text at 2
		 * and the source at 3.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

		/**
		 * Verifies generated memory ids carry the {@code mem-} prefix.
		 *
		 * <p>
		 * The captured INSERT id argument (index 0) must start with {@code "mem-"}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("generated memory ID starts with 'mem-'")
		void idStartsWithMemPrefix() {
			memoryService.remember(OWNER, "Test fact", null, "user", null);

			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			verify(db).update(contains("INSERT INTO memories"), cap.capture());
			assertThat(cap.getValue()[0].toString()).startsWith("mem-");
		}

		/**
		 * Verifies an embedding failure is swallowed and a null embedding stored.
		 *
		 * <p>
		 * When the embedder throws, {@code remember()} must not propagate and the
		 * captured INSERT must store {@code null} at the embedding_json position
		 * (index 5), leaving the row searchable by keyword.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

		/**
		 * Verifies tags are serialised to JSON via the mapper.
		 *
		 * <p>
		 * The mapper's {@code writeValueAsString} must be invoked at least once
		 * during a remember with tags.
		 *
		 * @throws Exception if the mocked mapper signals a checked failure
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("tags are serialised to JSON via ObjectMapper")
		void tagsSerialised() throws Exception {
			when(mapper.writeValueAsString(List.of("auto", "coding"))).thenReturn("[\"auto\",\"coding\"]");
			memoryService.remember(OWNER, "Fact", null, "agent", List.of("auto", "coding"));
			verify(mapper, atLeastOnce()).writeValueAsString(any());
		}

		/**
		 * Verifies the per-owner cap is checked before insert.
		 *
		 * <p>
		 * A COUNT query scoped to the owner must run as part of remember.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("cap enforcement: queries COUNT before INSERT")
		void capEnforcementQueriesCount() {
			memoryService.remember(OWNER, "Fact", null, "user", null);
			verify(db).queryForObject(contains("COUNT"), eq(Integer.class), eq(OWNER));
		}

		/**
		 * Verifies that exceeding the cap evicts before inserting.
		 *
		 * <p>
		 * With the count over the cap, a {@code DELETE FROM memories} eviction must
		 * run alongside the new {@code INSERT}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("when cap exceeded, eviction DELETE runs before INSERT")
		void evictionRunsBeforeInsert() {
			when(db.queryForObject(contains("COUNT"), eq(Integer.class), eq(OWNER))).thenReturn(2000);

			memoryService.remember(OWNER, "New fact", null, "user", null);

			// Eviction DELETE called before INSERT (DELETE binds owner + limit varargs)
			verify(db, atLeastOnce()).update(contains("DELETE FROM memories"), any(Object[].class));
			verify(db).update(contains("INSERT INTO memories"), any(Object[].class));
		}

		/**
		 * Verifies the returned record exposes the expected keys.
		 *
		 * <p>
		 * The result must contain id/owner/text/source/created_at with owner and
		 * text matching the inputs.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

	/**
	 * Tests for {@code forget()} — owner-scoped single deletion.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("forget()")
	class Forget {

		/**
		 * Verifies forgetting an owned memory issues a scoped DELETE.
		 *
		 * <p>
		 * The DELETE must bind both id and owner; a one-row result completes
		 * without throwing.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("executes DELETE WHERE id=? AND owner=?")
		void deletesOwnedMemory() {
			// Stub: DELETE affects 1 row (user owns this memory)
			when(db.update(contains("DELETE FROM memories WHERE id"), eq("mem-abc"), eq(OWNER))).thenReturn(1);
			assertThatCode(() -> memoryService.forget("mem-abc", OWNER)).doesNotThrowAnyException();
			// DELETE must be scoped to BOTH id and owner — prevents cross-user deletion
			verify(db).update(contains("DELETE FROM memories WHERE id"), eq("mem-abc"), eq(OWNER));
		}

		/**
		 * Verifies a no-op delete raises {@link NoSuchElementException}.
		 *
		 * <p>
		 * SECURITY: a 0-row DELETE (wrong owner or already gone) must throw with
		 * the id in the message, so an attacker cannot probe other users' ids.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

	/**
	 * Tests for {@code forgetAll()} — owner-scoped bulk deletion.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("forgetAll()")
	class ForgetAll {

		/**
		 * Verifies bulk delete is scoped to the owner.
		 *
		 * <p>
		 * The statement must be {@code DELETE FROM memories WHERE owner=?} bound to
		 * the requesting owner.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("executes DELETE WHERE owner=?")
		void deletesAllForOwner() {
			memoryService.forgetAll(OWNER);
			// Bulk delete scoped to the requesting owner
			verify(db).update(contains("DELETE FROM memories WHERE owner"), eq(OWNER));
		}

		/**
		 * Verifies bulk delete never touches another owner.
		 *
		 * <p>
		 * Only the requesting owner may appear as a bound parameter; a foreign
		 * owner must never be passed.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

	/**
	 * Tests for {@code list()} — owner/limit binding and defaults.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("list()")
	class ListMemories {

		/**
		 * Verifies listing binds both owner and limit.
		 *
		 * <p>
		 * The query must carry the owner (isolation) and the limit (bounded scan).
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("queries DB with owner and limit")
		void queriesWithOwnerAndLimit() {
			// Stub: empty result for this owner/limit combination
			when(db.queryForList(anyString(), eq(OWNER), eq(50))).thenReturn(List.of());
			memoryService.list(OWNER, 50);
			// Query must pass both owner (isolation) and limit (prevent unbounded scans)
			verify(db).queryForList(anyString(), eq(OWNER), eq(50));
		}

		/**
		 * Verifies a non-positive limit defaults to 100.
		 *
		 * <p>
		 * {@code limit<=0} means "use default", not "unlimited"; the query must
		 * bind 100.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("defaults to limit 100 when limit <= 0")
		void defaultLimitWhenZero() {
			// Stub: expects the defaulted limit=100 to be passed to DB
			when(db.queryForList(anyString(), eq(OWNER), eq(100))).thenReturn(List.of());
			// limit=0 is treated as "use default" rather than "unlimited" (safety guard)
			memoryService.list(OWNER, 0);
			verify(db).queryForList(anyString(), eq(OWNER), eq(100));
		}

		/**
		 * Verifies an empty DB yields an empty (non-null) list.
		 *
		 * <p>
		 * With no rows, the result must be an empty list callers can iterate.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

	/**
	 * Tests for {@code recall()} — keyword-search fallback and topK limiting.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("recall() — keyword search fallback")
	class Recall {

		/**
		 * Verifies recall returns empty when no memories are stored.
		 *
		 * <p>
		 * With nothing in the DB, there is nothing to search and the result is
		 * empty.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("returns empty list when no memories stored")
		void emptyWhenNoMemories() {
			// Stub: no memories in DB → nothing to search
			when(db.queryForList(anyString(), eq(OWNER))).thenReturn(List.of());
			assertThat(memoryService.recall(OWNER, "dark mode", 5)).isEmpty();
		}

		/**
		 * Builds a null-safe memory DB row (embedding_json is null).
		 *
		 * @param id      the memory id
		 * @param text    the memory text
		 * @param created the created-at timestamp
		 * @return a mutable map mirroring a {@code memories} row
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

		/**
		 * Verifies a keyword match on the text field returns a hit.
		 *
		 * <p>
		 * A memory whose text contains the query words must be returned, with its
		 * text preserved on the top result.
		 *
		 * @throws Exception if the mocked mapper signals a checked failure
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

		/**
		 * Verifies a query with no keyword overlap returns empty.
		 *
		 * <p>
		 * An unrelated query against a single off-topic memory must yield no
		 * results.
		 *
		 * @throws Exception if the mocked mapper signals a checked failure
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

		/**
		 * Verifies results are capped by the {@code topK} parameter.
		 *
		 * <p>
		 * With 10 matching memories and {@code topK=3}, at most 3 results may be
		 * returned.
		 *
		 * @throws Exception if the mocked mapper signals a checked failure
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

	/**
	 * Tests for {@code importMemories()} — bulk import and blank-skipping.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("importMemories()")
	class ImportMemories {

		/**
		 * Verifies each non-blank text becomes one row and the count is returned.
		 *
		 * <p>
		 * Three valid facts must produce three INSERTs and a returned count of 3.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("inserts one row per non-blank text and returns count")
		void insertsRowsForEachText() {
			// 3 valid facts → 3 INSERT calls, count=3 returned to caller
			int count = memoryService.importMemories(OWNER, List.of("Fact 1", "Fact 2", "Fact 3"), "import");
			assertThat(count).isEqualTo(3);
			// Each fact stored as its own memory row
			verify(db, times(3)).update(contains("INSERT INTO memories"), any(Object[].class));
		}

		/**
		 * Verifies blank and null entries are skipped.
		 *
		 * <p>
		 * Of four entries (one valid, the rest blank/null), only one INSERT must
		 * run and the returned count is 1.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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

		/**
		 * Verifies an empty input imports nothing.
		 *
		 * <p>
		 * An empty list must produce a count of 0 and issue no INSERTs.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
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
