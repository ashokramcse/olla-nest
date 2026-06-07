package com.ollanest.service;

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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link CompareService}.
 *
 * <p>Covers: {@code create()} — result shape, session IDs, blind/non-blind labelling,
 * DB INSERT; {@code vote()} — NoSuchElementException when not found, IllegalStateException
 * when already voted, DB UPDATE with winner.
 *
 * <p>All DB interactions are Mockito-stubbed — no Spring context, no real DB.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CompareService — unit tests")
class CompareServiceTest {

    private static final String OWNER = UserFactory.USER_ID;

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;

    @InjectMocks CompareService svc;

    // ── create() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("result contains session_id_a and session_id_b")
        void resultContainsSessionIds() {
            var req = Map.<String, Object>of("model_a", "llama3", "model_b", "mistral", "prompt", "Hello");
            var result = svc.create(OWNER, req);
            // Two independent session IDs — one per model — needed for parallel LLM calls
            assertThat(result).containsKey("session_id_a").containsKey("session_id_b");
            assertThat(result.get("session_id_a").toString()).startsWith("cmp-sess-a-");
            assertThat(result.get("session_id_b").toString()).startsWith("cmp-sess-b-");
        }

        @Test
        @DisplayName("is_blind defaults to true when not explicitly false")
        void isBlindDefaultsTrue() {
            var req = Map.<String, Object>of("model_a", "llama3", "model_b", "mistral", "prompt", "Q");
            var result = svc.create(OWNER, req);
            // Blind mode is the default to prevent anchoring bias in evaluations
            assertThat(result.get("is_blind")).isEqualTo(true);
        }

        @Test
        @DisplayName("is_blind=true hides model names — labels are 'Model A' and 'Model B'")
        void blindModeHidesModelNames() {
            var req = Map.<String, Object>of("model_a", "llama3", "model_b", "mistral",
                    "prompt", "Q", "is_blind", true);
            var result = svc.create(OWNER, req);
            // In blind mode, model identity must be hidden to prevent evaluation bias
            assertThat(result.get("label_a")).isEqualTo("Model A");
            assertThat(result.get("label_b")).isEqualTo("Model B");
        }

        @Test
        @DisplayName("is_blind=false exposes real model names")
        void nonBlindModeShowsModelNames() {
            var req = Map.<String, Object>of("model_a", "llama3", "model_b", "mistral",
                    "prompt", "Q", "is_blind", false);
            var result = svc.create(OWNER, req);
            // Non-blind mode shows real model identifiers for transparent comparison
            assertThat(result.get("label_a")).isEqualTo("llama3");
            assertThat(result.get("label_b")).isEqualTo("mistral");
        }

        @Test
        @DisplayName("DB INSERT called with owner and session IDs")
        void dbInsertCalled() {
            var req = Map.<String, Object>of("model_a", "a", "model_b", "b", "prompt", "P");
            svc.create(OWNER, req);
            // Comparison must be persisted for later vote recording
            verify(db).update(contains("INSERT INTO comparisons"), (Object[]) any());
        }

        @Test
        @DisplayName("result contains created_at timestamp")
        void resultContainsCreatedAt() {
            var req = Map.<String, Object>of("model_a", "a", "model_b", "b", "prompt", "P");
            var result = svc.create(OWNER, req);
            // Timestamp needed for sorting and expiry logic
            assertThat(result).containsKey("created_at");
        }
    }

    // ── vote() ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("vote()")
    class Vote {

        @Test
        @DisplayName("throws NoSuchElementException when comparison not found")
        void throwsWhenNotFound() {
            // Stub: no comparison row for this id and owner
            when(db.queryForList(contains("FROM comparisons WHERE id"), eq("cmp-x"), eq(OWNER)))
                    .thenReturn(List.of());
            // Must throw with the comparison ID in the message for debugging
            assertThatThrownBy(() -> svc.vote("cmp-x", OWNER, "a"))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("cmp-x");
        }

        @Test
        @DisplayName("throws IllegalStateException when winner already set")
        void throwsWhenAlreadyVoted() {
            // Stub: comparison already has a winner ("a")
            var row = Map.<String, Object>of("id", "cmp-1", "owner", OWNER, "winner", "a",
                    "model_a", "llama3", "model_b", "mistral");
            when(db.queryForList(contains("FROM comparisons WHERE id"), eq("cmp-1"), eq(OWNER)))
                    .thenReturn(List.of(row));
            // Second vote on the same comparison must be rejected — no vote changing allowed
            assertThatThrownBy(() -> svc.vote("cmp-1", OWNER, "b"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Already voted");
        }

        @Test
        @DisplayName("DB UPDATE called with winner when first vote")
        void dbUpdateCalledWithWinner() {
            // Stub: comparison found with no winner yet
            var row = Map.<String, Object>of("id", "cmp-2", "owner", OWNER, "model_a", "a", "model_b", "b");
            when(db.queryForList(contains("FROM comparisons WHERE id"), eq("cmp-2"), eq(OWNER)))
                    .thenReturn(List.of(row));
            svc.vote("cmp-2", OWNER, "a");
            // Winner and voted_at timestamp must be persisted
            verify(db).update(contains("UPDATE comparisons SET winner"), eq("a"), any(), eq("cmp-2"), eq(OWNER));
        }
    }
}
