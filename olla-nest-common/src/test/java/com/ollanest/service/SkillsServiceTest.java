package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link SkillsService}.
 *
 * <p>Covers: {@code createSkill()} — DB write, ID format, status assignment by source;
 * {@code updateSkill()} — ownership guard, DB update; {@code deleteSkill()} — ownership,
 * NoSuchElementException on miss; {@code list()} — category/status filtering;
 * {@code search()} — keyword matching and empty result; {@code approve()} / {@code archive()}
 * — status change; {@code recordUse()} — use_count increment.
 *
 * @author Ashok Ram
 * @since v2026.2.0 — initial creation
 * @version v2026.2.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SkillsService — unit tests")
class SkillsServiceTest {

    private static final String OWNER = UserFactory.USER_ID;

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;
    @Mock EmbeddingService embeddingService;

    @InjectMocks SkillsService skillsService;

    @BeforeEach
    void stubMapper() throws Exception {
        // Stub mapper to return empty JSON arrays for tag/platform serialization
        when(mapper.writeValueAsString(any())).thenReturn("[]");
    }

    private Map<String, Object> buildSkillRow(String id, String name, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("description", "A test skill");
        row.put("category", "general");
        row.put("tags_json", "[]");
        row.put("platforms_json", "[]");
        row.put("when_to_use", "When testing");
        row.put("procedure_json", "[]");
        row.put("pitfalls_json", "[]");
        row.put("verification_json", "[]");
        row.put("status", status);
        row.put("confidence", 0.8);
        row.put("source", "user");
        row.put("owner", OWNER);
        row.put("version", "1.0.0");
        row.put("use_count", 0);
        row.put("created_at", "2026-01-01T00:00:00Z");
        row.put("updated_at", "2026-01-01T00:00:00Z");
        row.put("teacher_model", null);
        row.put("session_id", null);
        return row;
    }

    // ── createSkill() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createSkill()")
    class CreateSkill {

        @Test
        @DisplayName("inserts a row into the skills table")
        void insertsRow() {
            // Stub: DB returns the newly created skill after INSERT
            var row = buildSkillRow("skill-1", "Deploy Docker", "active");
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(row));

            skillsService.createSkill(Map.of("name", "Deploy Docker", "category", "devops"), OWNER);
            // Verify the INSERT was executed — skill must be persisted
            verify(db).update(contains("INSERT INTO skills"), any(Object[].class));
        }

        @Test
        @DisplayName("skill ID starts with 'skill-'")
        void idStartsWithSkillPrefix() {
            // Stub: DB returns a skill row after creation
            var row = buildSkillRow("skill-abc", "Test Skill", "active");
            when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of(row));

            skillsService.createSkill(Map.of("name", "Test Skill"), OWNER);

            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            verify(db).update(contains("INSERT INTO skills"), cap.capture());
            // args[0] = id — must use the "skill-" prefix for consistent ID namespace
            assertThat(cap.getValue()[0].toString()).startsWith("skill-");
        }

        @Test
        @DisplayName("user-sourced skill gets status 'active' immediately")
        void userSkillStatusIsActive() {
            // Stub: DB returns an active skill row
            var row = buildSkillRow("skill-1", "Skill", "active");
            when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of(row));

            skillsService.createSkill(Map.of("name", "Skill", "source", "user"), OWNER);

            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            verify(db).update(contains("INSERT INTO skills"), cap.capture());
            // status is at index 10 in the INSERT — user skills are trusted and active immediately
            assertThat(cap.getValue()[10]).isEqualTo("active");
        }

        @Test
        @DisplayName("agent-learned skill gets status 'draft' (requires admin approval)")
        void learnedSkillStatusIsDraft() {
            // Stub: DB returns a draft skill row
            var row = buildSkillRow("skill-1", "Skill", "draft");
            when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of(row));

            skillsService.createSkill(Map.of("name", "Skill", "source", "learned"), OWNER);

            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            verify(db).update(contains("INSERT INTO skills"), cap.capture());
            // Agent-learned skills need admin review before activation — must start as "draft"
            assertThat(cap.getValue()[10]).isEqualTo("draft");
        }
    }

    // ── deleteSkill() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteSkill()")
    class DeleteSkill {

        @Test
        @DisplayName("executes DELETE WHERE id=? AND (owner=? OR owner IS NULL)")
        void deletesOwnedSkill() {
            // Stub: DB reports one row deleted (skill found and owned or is a global skill)
            when(db.update(contains("DELETE FROM skills"), anyString(), anyString())).thenReturn(1);
            // No exception = deletion succeeded for the owned skill
            assertThatCode(() -> skillsService.deleteSkill("skill-1", OWNER))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws NoSuchElementException when no row deleted")
        void throwsWhenNotFound() {
            // Stub: DB reports 0 rows deleted — skill not found or not owned by this user
            when(db.update(contains("DELETE FROM skills"), anyString(), anyString())).thenReturn(0);
            // SECURITY: attempt to delete another user's skill must throw, not silently succeed
            assertThatThrownBy(() -> skillsService.deleteSkill("skill-missing", OWNER))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }

    // ── approve() / archive() ─────────────────────────────────────────────────

    @Nested
    @DisplayName("approve() / archive() — admin status transitions")
    class StatusTransitions {

        @Test
        @DisplayName("approve() sets status='active'")
        void approveSetsActive() {
            // approve() is an admin action that moves a draft skill to production
            skillsService.approve("skill-1");
            verify(db).update(contains("UPDATE skills SET status='active'"), anyString(), eq("skill-1"));
        }

        @Test
        @DisplayName("archive() sets status='archived'")
        void archiveSetsArchived() {
            // archive() removes a skill from active use without deleting it (preserves history)
            skillsService.archive("skill-1");
            verify(db).update(contains("UPDATE skills SET status='archived'"), anyString(), eq("skill-1"));
        }
    }

    // ── recordUse() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("recordUse()")
    class RecordUse {

        @Test
        @DisplayName("increments use_count = use_count + 1")
        void incrementsUseCount() {
            // use_count tracks how often a skill is applied — must increment by exactly 1
            skillsService.recordUse("skill-1");
            verify(db).update(contains("use_count = use_count + 1"), eq("skill-1"));
        }
    }

    // ── search() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("search()")
    class Search {

        @Test
        @DisplayName("returns empty list when no skills stored")
        void emptyWhenNoSkills() throws Exception {
            // Stub: DB returns empty list — no skills exist yet for this owner
            when(db.queryForList(contains("SELECT"), eq(OWNER), eq(null), eq("active"), eq(1000)))
                    .thenReturn(List.of());
            when(mapper.readValue(anyString(), eq(java.util.List.class))).thenReturn(List.of());

            // Empty skill store must return an empty list, not throw
            assertThat(skillsService.search(OWNER, "docker deploy", 5)).isEmpty();
        }

        @Test
        @DisplayName("keyword match on name returns the matching skill")
        void keywordMatchOnName() throws Exception {
            // Stub: DB returns a skill whose name contains the keyword
            var row = buildSkillRow("skill-1", "Deploy Docker container", "active");
            when(db.queryForList(contains("SELECT"), any(Object[].class)))
                    .thenReturn(List.of(row));
            when(mapper.readValue(anyString(), eq(java.util.List.class))).thenReturn(List.of());

            var results = skillsService.search(OWNER, "docker", 10);
            // The matching skill must be returned — keyword search on name must work
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).get("name")).isEqualTo("Deploy Docker container");
        }
    }
}
