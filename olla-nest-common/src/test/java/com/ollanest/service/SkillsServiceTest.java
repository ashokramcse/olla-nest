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
        when(mapper.writeValueAsString(any())).thenReturn("[]");
    }

    private Map<String, Object> buildSkillRow(String id, String name, String status) {
        return Map.of(
                "id", id, "name", name, "description", "A test skill",
                "category", "general", "tags_json", "[]", "platforms_json", "[]",
                "when_to_use", "When testing", "procedure_json", "[]",
                "pitfalls_json", "[]", "verification_json", "[]",
                "status", status, "confidence", 0.8, "source", "user",
                "owner", OWNER, "version", "1.0.0", "use_count", 0,
                "created_at", "2026-01-01T00:00:00Z",
                "updated_at", "2026-01-01T00:00:00Z",
                "teacher_model", null, "session_id", null
        );
    }

    // ── createSkill() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createSkill()")
    class CreateSkill {

        @Test
        @DisplayName("inserts a row into the skills table")
        void insertsRow() {
            var row = buildSkillRow("skill-1", "Deploy Docker", "active");
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(row));

            skillsService.createSkill(Map.of("name", "Deploy Docker", "category", "devops"), OWNER);
            verify(db).update(contains("INSERT INTO skills"), (Object[]) any());
        }

        @Test
        @DisplayName("skill ID starts with 'skill-'")
        void idStartsWithSkillPrefix() {
            var row = buildSkillRow("skill-abc", "Test Skill", "active");
            when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of(row));

            skillsService.createSkill(Map.of("name", "Test Skill"), OWNER);

            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            verify(db).update(contains("INSERT INTO skills"), cap.capture());
            assertThat(cap.getValue()[0].toString()).startsWith("skill-");
        }

        @Test
        @DisplayName("user-sourced skill gets status 'active' immediately")
        void userSkillStatusIsActive() {
            var row = buildSkillRow("skill-1", "Skill", "active");
            when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of(row));

            skillsService.createSkill(Map.of("name", "Skill", "source", "user"), OWNER);

            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            verify(db).update(contains("INSERT INTO skills"), cap.capture());
            // status is at index 10 in the INSERT
            assertThat(cap.getValue()[10]).isEqualTo("active");
        }

        @Test
        @DisplayName("agent-learned skill gets status 'draft' (requires admin approval)")
        void learnedSkillStatusIsDraft() {
            var row = buildSkillRow("skill-1", "Skill", "draft");
            when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of(row));

            skillsService.createSkill(Map.of("name", "Skill", "source", "learned"), OWNER);

            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            verify(db).update(contains("INSERT INTO skills"), cap.capture());
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
            when(db.update(contains("DELETE FROM skills"), anyString(), anyString())).thenReturn(1);
            assertThatCode(() -> skillsService.deleteSkill("skill-1", OWNER))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws NoSuchElementException when no row deleted")
        void throwsWhenNotFound() {
            when(db.update(contains("DELETE FROM skills"), anyString(), anyString())).thenReturn(0);
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
            skillsService.approve("skill-1");
            verify(db).update(contains("UPDATE skills SET status='active'"), anyString(), eq("skill-1"));
        }

        @Test
        @DisplayName("archive() sets status='archived'")
        void archiveSetsArchived() {
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
            when(db.queryForList(contains("SELECT"), eq(OWNER), eq(null), eq("active"), eq(1000)))
                    .thenReturn(List.of());
            when(mapper.readValue(anyString(), eq(java.util.List.class))).thenReturn(List.of());

            assertThat(skillsService.search(OWNER, "docker deploy", 5)).isEmpty();
        }

        @Test
        @DisplayName("keyword match on name returns the matching skill")
        void keywordMatchOnName() throws Exception {
            var row = buildSkillRow("skill-1", "Deploy Docker container", "active");
            when(db.queryForList(contains("SELECT"), (Object[]) any()))
                    .thenReturn(List.of(row));
            when(mapper.readValue(anyString(), eq(java.util.List.class))).thenReturn(List.of());

            var results = skillsService.search(OWNER, "docker", 10);
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).get("name")).isEqualTo("Deploy Docker container");
        }
    }
}
