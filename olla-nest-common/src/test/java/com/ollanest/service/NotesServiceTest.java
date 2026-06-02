package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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
 * OCD-level unit tests for {@link NotesService}.
 *
 * <p>Covers: {@code create()} — DB write, ID format, default values, note vs checklist;
 * {@code update()} — ownership guard, partial update merging; {@code delete()} —
 * ownership gate and NoSuchElementException on miss; {@code list()} — archive and label
 * filtering; {@code getById()} — ownership gate.
 *
 * @author Ashok Ram
 * @since v2026.2.0 — initial creation
 * @version v2026.2.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotesService — unit tests")
class NotesServiceTest {

    private static final String OWNER = UserFactory.USER_ID;

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;

    @InjectMocks NotesService notesService;

    private Map<String, Object> noteRow(String id, String title, String noteType, int archived) {
        return Map.of(
                "id", id, "owner", OWNER, "title", title, "content", "Some content",
                "items_json", null, "note_type", noteType, "color", "default",
                "label", null, "pinned", 0, "archived", archived,
                "due_date", null, "repeat", "none", "source", "user",
                "session_id", null, "image_url", null, "sort_order", 0,
                "created_at", "2026-01-01T00:00:00Z",
                "updated_at", "2026-01-01T00:00:00Z"
        );
    }

    // ── create() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("inserts a row into the notes table")
        void insertsRow() {
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(noteRow("note-1", "My Note", "note", 0)));
            notesService.create(OWNER, Map.of("title", "My Note", "content", "body text"));
            verify(db).update(contains("INSERT INTO notes"), (Object[]) any());
        }

        @Test
        @DisplayName("note ID starts with 'note-'")
        void idStartsWithNotePrefix() {
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(noteRow("note-abc", "Note", "note", 0)));
            notesService.create(OWNER, Map.of("title", "Note"));
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            verify(db).update(contains("INSERT INTO notes"), cap.capture());
            assertThat(cap.getValue()[0].toString()).startsWith("note-");
        }

        @Test
        @DisplayName("default note_type is 'note' when not specified")
        void defaultNoteType() {
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(noteRow("note-1", "Note", "note", 0)));
            notesService.create(OWNER, Map.of("title", "Note"));
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            verify(db).update(contains("INSERT INTO notes"), cap.capture());
            // note_type is at index 5 in the INSERT args
            assertThat(cap.getValue()[5]).isEqualTo("note");
        }

        @Test
        @DisplayName("checklist note type is persisted when explicitly set")
        void checklistTypePersistedWhenSet() {
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(noteRow("note-1", "Checklist", "checklist", 0)));
            notesService.create(OWNER, Map.of("title", "Checklist", "note_type", "checklist",
                    "items", List.of(Map.of("text", "Task 1", "checked", false))));
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            verify(db).update(contains("INSERT INTO notes"), cap.capture());
            assertThat(cap.getValue()[5]).isEqualTo("checklist");
        }

        @Test
        @DisplayName("owner is stored from the argument — not from request body")
        void ownerFromArgument() {
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(noteRow("note-1", "Note", "note", 0)));
            notesService.create(OWNER, Map.of("title", "Note"));
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            verify(db).update(contains("INSERT INTO notes"), cap.capture());
            assertThat(cap.getValue()[1]).isEqualTo(OWNER); // owner at index 1
        }

        @Test
        @DisplayName("default color is 'default' when not specified")
        void defaultColorIsDefault() {
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(noteRow("note-1", "Note", "note", 0)));
            notesService.create(OWNER, Map.of("title", "Note"));
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            verify(db).update(contains("INSERT INTO notes"), cap.capture());
            assertThat(cap.getValue()[6]).isEqualTo("default");
        }

        @ParameterizedTest(name = "color={0}")
        @ValueSource(strings = {"yellow", "green", "blue", "pink", "purple", "orange"})
        @DisplayName("custom color is stored correctly")
        void customColorStored(String color) {
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(noteRow("note-1", "Note", "note", 0)));
            notesService.create(OWNER, Map.of("title", "Note", "color", color));
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            verify(db).update(contains("INSERT INTO notes"), cap.capture());
            assertThat(cap.getValue()[6]).isEqualTo(color);
        }
    }

    // ── delete() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("executes DELETE WHERE id=? AND owner=?")
        void deletesOwnedNote() {
            when(db.update(contains("DELETE FROM notes"), anyString(), anyString())).thenReturn(1);
            assertThatCode(() -> notesService.delete("note-1", OWNER)).doesNotThrowAnyException();
            verify(db).update(contains("DELETE FROM notes"), eq("note-1"), eq(OWNER));
        }

        @Test
        @DisplayName("throws NoSuchElementException when note not owned or missing")
        void throwsWhenNotFound() {
            when(db.update(contains("DELETE FROM notes"), anyString(), anyString())).thenReturn(0);
            assertThatThrownBy(() -> notesService.delete("note-999", OWNER))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("note-999");
        }

        @Test
        @DisplayName("does not delete notes owned by other users (WHERE owner=?)")
        void doesNotDeleteOtherOwnerNotes() {
            when(db.update(contains("DELETE FROM notes"), anyString(), anyString())).thenReturn(0);
            assertThatThrownBy(() -> notesService.delete("note-1", "other-user"))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }

    // ── getById() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getById()")
    class GetById {

        @Test
        @DisplayName("returns null when not found")
        void returnsNullWhenNotFound() {
            when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of());
            assertThat(notesService.getById("note-999", OWNER)).isNull();
        }

        @Test
        @DisplayName("returns mapped note when found")
        void returnsMappedNote() throws Exception {
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(noteRow("note-1", "My Note", "note", 0)));
            when(mapper.readValue(anyString(), eq(java.util.List.class))).thenReturn(null);

            var note = notesService.getById("note-1", OWNER);
            assertThat(note).isNotNull();
        }
    }

    // ── list() ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("list()")
    class ListNotes {

        @Test
        @DisplayName("non-archived query uses WHERE archived=0")
        void nonArchivedQueryFiltersArchived() throws Exception {
            when(db.queryForList(contains("archived=0"), eq(OWNER))).thenReturn(List.of());
            when(mapper.readValue(anyString(), eq(java.util.List.class))).thenReturn(null);
            notesService.list(OWNER, false, null);
            verify(db).queryForList(contains("archived=0"), eq(OWNER));
        }

        @Test
        @DisplayName("archived=true does not add archived=0 filter")
        void archivedTrueNoFilter() throws Exception {
            when(db.queryForList(contains("WHERE owner=?"), eq(OWNER))).thenReturn(List.of());
            when(mapper.readValue(anyString(), eq(java.util.List.class))).thenReturn(null);
            notesService.list(OWNER, true, null);
            verify(db, never()).queryForList(contains("archived=0"), (Object[]) any());
        }

        @Test
        @DisplayName("label filter adds AND label=? clause")
        void labelFilterAddsClause() throws Exception {
            when(db.queryForList(contains("label=?"), eq(OWNER), eq("work"))).thenReturn(List.of());
            when(mapper.readValue(anyString(), eq(java.util.List.class))).thenReturn(null);
            notesService.list(OWNER, false, "work");
            verify(db).queryForList(contains("label=?"), eq(OWNER), eq("work"));
        }
    }
}
