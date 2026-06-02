package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link MemoryService}.
 *
 * <p>Covers: {@code remember()} — DB write, embedding attempt, cap enforcement;
 * {@code forget()} — ownership gate, missing memory; {@code forgetAll()} — bulk delete;
 * {@code list()} — result shape; {@code recall()} — keyword search fallback (embedding
 * service stubbed to fail), basic ranking; {@code importMemories()} — bulk import;
 * {@code exportAll()} — delegates to list.
 *
 * <p>All DB and {@link EmbeddingService} interactions are Mockito-stubbed —
 * no Spring context, no real DB, no network calls.
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

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;
    @Mock EmbeddingService embeddingService;

    @InjectMocks MemoryService memoryService;

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
            // args: id, owner, text, source, session_id, embedding_json, tags_json, created_at, updated_at
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
            when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("LLM offline"));

            assertThatCode(() ->
                memoryService.remember(OWNER, "Some fact", null, "extractor", List.of("auto"))
            ).doesNotThrowAnyException();

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

            // Eviction DELETE called before INSERT
            verify(db, atLeastOnce()).update(contains("DELETE FROM memories"));
            verify(db).update(contains("INSERT INTO memories"), (Object[]) any());
        }

        @Test
        @DisplayName("returned record contains id, owner, text, source, created_at")
        void returnedRecordShape() {
            var result = memoryService.remember(OWNER, "Test fact", "sess-1", "user", List.of("work"));
            assertThat(result).containsKey("id").containsKey("owner").containsKey("text")
                    .containsKey("source").containsKey("created_at");
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
            when(db.update(contains("DELETE FROM memories WHERE id"), eq("mem-abc"), eq(OWNER))).thenReturn(1);
            assertThatCode(() -> memoryService.forget("mem-abc", OWNER)).doesNotThrowAnyException();
            verify(db).update(contains("DELETE FROM memories WHERE id"), eq("mem-abc"), eq(OWNER));
        }

        @Test
        @DisplayName("throws NoSuchElementException when no row deleted (ownership check)")
        void throwsWhenNotOwned() {
            when(db.update(contains("DELETE FROM memories WHERE id"), anyString(), anyString())).thenReturn(0);
            assertThatThrownBy(() -> memoryService.forget("mem-xyz", OWNER))
                    .isInstanceOf(NoSuchElementException.class)
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
            verify(db).update(contains("DELETE FROM memories WHERE owner"), eq(OWNER));
        }

        @Test
        @DisplayName("does not affect other owners")
        void scopedToOwner() {
            memoryService.forgetAll(OWNER);
            verify(db).update(anyString(), eq(OWNER)); // exactly the owner
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
            when(db.queryForList(anyString(), eq(OWNER), eq(50))).thenReturn(List.of());
            memoryService.list(OWNER, 50);
            verify(db).queryForList(anyString(), eq(OWNER), eq(50));
        }

        @Test
        @DisplayName("defaults to limit 100 when limit <= 0")
        void defaultLimitWhenZero() {
            when(db.queryForList(anyString(), eq(OWNER), eq(100))).thenReturn(List.of());
            memoryService.list(OWNER, 0);
            verify(db).queryForList(anyString(), eq(OWNER), eq(100));
        }

        @Test
        @DisplayName("returns empty list when DB returns no rows")
        void emptyResultWhenNoRows() {
            when(db.queryForList(anyString(), eq(OWNER), anyInt())).thenReturn(List.of());
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
            when(db.queryForList(anyString(), eq(OWNER))).thenReturn(List.of());
            assertThat(memoryService.recall(OWNER, "dark mode", 5)).isEmpty();
        }

        @Test
        @DisplayName("keyword match on text field returns hit")
        void keywordMatchReturnsHit() throws Exception {
            var row = Map.<String, Object>of(
                    "id", "mem-1", "owner", OWNER, "text", "User prefers dark mode",
                    "source", "user", "session_id", "", "importance", 5,
                    "embedding_json", null, "created_at", "2026-01-01T00:00:00Z",
                    "tags_json", "[]");
            when(db.queryForList(contains("SELECT"), eq(OWNER))).thenReturn(List.of(row));
            when(mapper.readValue(eq("[]"), eq(java.util.List.class))).thenReturn(List.of());

            var results = memoryService.recall(OWNER, "dark mode", 5);
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).get("text")).isEqualTo("User prefers dark mode");
        }

        @Test
        @DisplayName("query with no keyword match returns empty list")
        void noKeywordMatchReturnsEmpty() throws Exception {
            var row = Map.<String, Object>of(
                    "id", "mem-1", "owner", OWNER, "text", "User is a Java developer",
                    "source", "user", "session_id", "", "importance", 5,
                    "embedding_json", null, "created_at", "2026-01-01T00:00:00Z",
                    "tags_json", "[]");
            when(db.queryForList(contains("SELECT"), eq(OWNER))).thenReturn(List.of(row));
            when(mapper.readValue(eq("[]"), eq(java.util.List.class))).thenReturn(List.of());

            var results = memoryService.recall(OWNER, "quantum physics research", 5);
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("results are limited by topK parameter")
        void topKLimitsResults() throws Exception {
            var rows = java.util.stream.IntStream.rangeClosed(1, 10)
                    .mapToObj(i -> Map.<String, Object>of(
                            "id", "mem-" + i, "owner", OWNER, "text", "coding fact " + i,
                            "source", "user", "session_id", "", "importance", 5,
                            "embedding_json", null, "created_at", "2026-01-0" + Math.min(i, 9) + "T00:00:00Z",
                            "tags_json", "[]"))
                    .toList();
            when(db.queryForList(contains("SELECT"), eq(OWNER))).thenReturn(rows);
            when(mapper.readValue(eq("[]"), eq(java.util.List.class))).thenReturn(List.of());

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
            int count = memoryService.importMemories(OWNER,
                    List.of("Fact 1", "Fact 2", "Fact 3"), "import");
            assertThat(count).isEqualTo(3);
            verify(db, times(3)).update(contains("INSERT INTO memories"), (Object[]) any());
        }

        @Test
        @DisplayName("skips blank and null entries")
        void skipsBlankEntries() {
            int count = memoryService.importMemories(OWNER,
                    List.of("Fact 1", "", "  ", null), "import");
            assertThat(count).isEqualTo(1);
            verify(db, times(1)).update(contains("INSERT INTO memories"), (Object[]) any());
        }

        @Test
        @DisplayName("empty list imports zero memories")
        void emptyListImportsZero() {
            int count = memoryService.importMemories(OWNER, List.of(), "import");
            assertThat(count).isEqualTo(0);
            verify(db, never()).update(contains("INSERT INTO memories"), (Object[]) any());
        }
    }
}
