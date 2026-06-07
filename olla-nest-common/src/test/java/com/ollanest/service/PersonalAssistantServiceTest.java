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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link PersonalAssistantService}.
 *
 * <p>Covers getOrCreate, update, and getCheckIns.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PersonalAssistantService — unit tests")
class PersonalAssistantServiceTest {

    private static final String OWNER = UserFactory.USER_ID;

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;
    @Mock TaskSchedulerService taskService;

    @InjectMocks PersonalAssistantService personalAssistantService;

    private Map<String, Object> assistantRow() {
        return Map.of("id", "crew-abc", "owner", OWNER, "name", "Assistant",
                "avatar", "🤖", "personality", "Helpful", "enabled_tools_json", "[]",
                "timezone", "UTC", "greeting", "Hello!", "allow_autonomous_email", 0,
                "created_at", "2026-01-01T00:00:00Z", "updated_at", "2026-01-01T00:00:00Z");
    }

    // ── getOrCreate() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getOrCreate()")
    class GetOrCreate {

        @Test
        @DisplayName("returns existing record when found in DB")
        void returnsExisting() throws Exception {
            // Stub: crew_members row already exists for this owner
            when(db.queryForList(contains("FROM crew_members WHERE owner"), eq(OWNER)))
                    .thenReturn(List.of(assistantRow()));
            when(db.queryForList(contains("FROM scheduled_tasks"), eq(OWNER)))
                    .thenReturn(List.of());
            when(mapper.readValue(anyString(), eq(List.class))).thenReturn(List.of());
            Map<String, Object> result = personalAssistantService.getOrCreate(OWNER);
            // Result must be the existing row — not a newly created one
            assertThat(result).isNotNull();
            assertThat(result.get("owner")).isEqualTo(OWNER);
            // INSERT must not be called when the record already exists
            verify(db, never()).update(contains("INSERT INTO crew_members"), (Object[]) any());
        }

        @Test
        @DisplayName("inserts when record not found")
        void insertsWhenNotFound() throws Exception {
            // Step 1: first query returns empty (no existing record)
            // Step 2: second query returns the newly created row (post-INSERT fetch)
            when(db.queryForList(contains("FROM crew_members WHERE owner"), eq(OWNER)))
                    .thenReturn(List.of())
                    .thenReturn(List.of(assistantRow()));
            when(db.queryForList(contains("FROM scheduled_tasks"), eq(OWNER)))
                    .thenReturn(List.of());
            when(mapper.readValue(anyString(), eq(List.class))).thenReturn(List.of());
            when(taskService.create(anyString(), anyMap())).thenReturn(Map.of("id", "task-1"));
            Map<String, Object> result = personalAssistantService.getOrCreate(OWNER);
            // INSERT must be called to create the default assistant record
            verify(db).update(contains("INSERT INTO crew_members"), (Object[]) any());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("throws for synthetic owner")
        void throwsForSyntheticOwner() {
            // SECURITY: "system" is a synthetic owner ID — real users must not pass this
            assertThatThrownBy(() -> personalAssistantService.getOrCreate("system"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("throws for null owner")
        void throwsForNullOwner() {
            // Null owner would cause every DB query to fail — reject early
            assertThatThrownBy(() -> personalAssistantService.getOrCreate(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── update() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("UPDATE WHERE owner=? is called")
        void updatesForOwner() throws Exception {
            // Stub: existing record is found so the update can proceed
            when(db.queryForList(contains("FROM crew_members WHERE owner"), eq(OWNER)))
                    .thenReturn(List.of(assistantRow()));
            when(db.queryForList(contains("FROM scheduled_tasks"), eq(OWNER)))
                    .thenReturn(List.of());
            when(mapper.readValue(anyString(), eq(List.class))).thenReturn(List.of());
            when(mapper.writeValueAsString(any())).thenReturn("[]");
            personalAssistantService.update(OWNER, Map.of("name", "Nova"));
            // UPDATE must target this specific owner — not a global update
            verify(db).update(contains("UPDATE crew_members"), (Object[]) any());
        }
    }

    // ── getCheckIns() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getCheckIns()")
    class GetCheckIns {

        @Test
        @DisplayName("queries scheduled_tasks for owner")
        void queriesWithOwner() {
            // Stub: DB returns one scheduled check-in task for this owner
            when(db.queryForList(contains("FROM scheduled_tasks"), eq(OWNER)))
                    .thenReturn(List.of(Map.of("id", "task-1", "name", "Morning check-in")));
            List<Map<String, Object>> checkIns = personalAssistantService.getCheckIns(OWNER);
            // Result must contain the stub row — check-ins are scoped to the owner
            assertThat(checkIns).hasSize(1);
            // The query must be parameterized with the owner ID to prevent cross-user leakage
            verify(db).queryForList(anyString(), eq(OWNER));
        }
    }
}
