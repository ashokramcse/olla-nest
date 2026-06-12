package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;

/**
 * OCD-level unit tests for {@link NotesService}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * {@link NotesService} stores user notes and checklists. These tests guard the
 * behaviours most likely to regress under change: NOT-NULL column defaulting
 * (BUG-019, where explicit JSON nulls must coerce to sane defaults rather than
 * crash with a 500), unique-id generation under concurrent creates (BUG-013),
 * and the owner gate on every read/update/delete so users never reach each
 * other's notes.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Runs under {@link MockitoExtension} with {@link Strictness#LENIENT};
 * {@link JdbcTemplate} and {@link ObjectMapper} are mocked and injected.</li>
 * <li>{@link ArgumentCaptor} is used to assert on the exact positional INSERT
 * arguments, since column ordering encodes the defaulting contract.</li>
 * <li>{@link #noteRow(String, String, String, int)} builds a representative
 * row for post-write fetch stubs.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.0 — initial creation covering create/update/delete/list/getById
 * with BUG-019 and BUG-013 regressions.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.0
 * @version v2026.2.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotesService — unit tests")
class NotesServiceTest {

	/** Canonical owner id used across all note fixtures. */
	private static final String OWNER = UserFactory.USER_ID;

	/** Mocked JDBC template capturing the SQL and positional args the service issues. */
	@Mock
	JdbcTemplate db;
	/** Mocked JSON mapper for checklist {@code items_json} (de)serialisation. */
	@Mock
	ObjectMapper mapper;

	/** Service under test with mocks injected. */
	@InjectMocks
	NotesService notesService;

	/**
	 * Builds a representative note DB row.
	 *
	 * @param id       the note id to embed
	 * @param title    the note title
	 * @param noteType the note type ({@code note} or {@code checklist})
	 * @param archived the archived flag (0/1)
	 * @return a mutable map mirroring a {@code notes} row
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	private Map<String, Object> noteRow(String id, String title, String noteType, int archived) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", id);
		row.put("owner", OWNER);
		row.put("title", title);
		row.put("content", "Some content");
		row.put("items_json", null);
		row.put("note_type", noteType);
		row.put("color", "default");
		row.put("label", null);
		row.put("pinned", 0);
		row.put("archived", archived);
		row.put("due_date", null);
		row.put("repeat", "none");
		row.put("source", "user");
		row.put("session_id", null);
		row.put("image_url", null);
		row.put("sort_order", 0);
		row.put("created_at", "2026-01-01T00:00:00Z");
		row.put("updated_at", "2026-01-01T00:00:00Z");
		return row;
	}

	// ── create() ──────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code create()} — persistence, defaulting and id generation.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("create()")
	class Create {

		/**
		 * Verifies creating a note issues an INSERT into {@code notes}.
		 *
		 * <p>
		 * With the post-write fetch stubbed, exactly one INSERT must fire so the
		 * note is persisted.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("inserts a row into the notes table")
		void insertsRow() {
			// Stub DB to return the newly created note after INSERT
			when(db.queryForList(anyString(), anyString(), anyString()))
					.thenReturn(List.of(noteRow("note-1", "My Note", "note", 0)));
			notesService.create(OWNER, Map.of("title", "My Note", "content", "body text"));
			// Verify the INSERT was executed — note must be persisted
			verify(db).update(contains("INSERT INTO notes"), any(Object[].class));
		}

		/**
		 * Verifies explicit JSON nulls on NOT-NULL columns coerce to defaults
		 * (BUG-019 regression).
		 *
		 * <p>
		 * A client sending {@code null} for title/note_type/color/repeat/source/
		 * sort_order must not hit a NOT-NULL constraint; the captured INSERT args
		 * must hold the defaulted values ({@code ""}, {@code note},
		 * {@code default}, {@code none}, {@code user}, {@code 0}).
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("explicit JSON nulls for NOT-NULL columns are coerced to defaults (BUG-019)")
		void explicitNullsCoercedToDefaults() {
			when(db.queryForList(anyString(), anyString(), anyString()))
					.thenReturn(List.of(noteRow("note-x", "", "note", 0)));
			// A client sending {"title":null,"note_type":null,...} must not hit a
			// NOT NULL constraint (500). HashMap because Map.of forbids null values.
			Map<String, Object> req = new HashMap<>();
			req.put("title", null);
			req.put("content", null);
			req.put("note_type", null);
			req.put("color", null);
			req.put("repeat", null);
			req.put("source", null);
			req.put("sort_order", null);
			notesService.create(OWNER, req);
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			verify(db).update(contains("INSERT INTO notes"), cap.capture());
			Object[] a = cap.getValue();
			// NOT-NULL columns (title=2, note_type=5, color=6, repeat=11, source=12,
			// sort_order=15)
			assertThat(a[2]).isEqualTo("");
			assertThat(a[5]).isEqualTo("note");
			assertThat(a[6]).isEqualTo("default");
			assertThat(a[11]).isEqualTo("none");
			assertThat(a[12]).isEqualTo("user");
			assertThat(a[15]).isEqualTo(0);
		}

		/**
		 * Verifies generated note ids carry the {@code note-} prefix.
		 *
		 * <p>
		 * The captured INSERT id argument (index 0) must start with
		 * {@code "note-"} for a consistent id namespace.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("note ID starts with 'note-'")
		void idStartsWithNotePrefix() {
			// Stub DB to return a note row after creation
			when(db.queryForList(anyString(), anyString(), anyString()))
					.thenReturn(List.of(noteRow("note-abc", "Note", "note", 0)));
			notesService.create(OWNER, Map.of("title", "Note"));
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			verify(db).update(contains("INSERT INTO notes"), cap.capture());
			// arg[0] = id — must use the "note-" prefix for consistent ID namespace
			assertThat(cap.getValue()[0].toString()).startsWith("note-");
		}

		/**
		 * Verifies rapid creates produce unique ids (BUG-013 concurrency
		 * regression).
		 *
		 * <p>
		 * Timestamp-only ids previously collided under load; 50 sequential creates
		 * must yield 50 distinct ids in the captured INSERT arguments.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("rapid creates produce UNIQUE ids (BUG-013 concurrency regression)")
		void rapidCreatesProduceUniqueIds() {
			// Note ids were timestamp-only -> PK collisions under concurrent load
			// (k6: ~73% of POST /api/notes failed at 30 VUs). Ids must be unique.
			when(db.queryForList(anyString(), anyString(), anyString()))
					.thenReturn(List.of(noteRow("note-x", "N", "note", 0)));
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			for (int i = 0; i < 50; i++)
				notesService.create(OWNER, Map.of("title", "N" + i));
			verify(db, times(50)).update(contains("INSERT INTO notes"), cap.capture());
			var ids = new HashSet<String>();
			for (Object[] a : cap.getAllValues())
				ids.add(a[0].toString());
			assertThat(ids).hasSize(50);
		}

		/**
		 * Verifies {@code note_type} defaults to {@code "note"} when unspecified.
		 *
		 * <p>
		 * The captured INSERT arg at index 5 must be {@code "note"}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("default note_type is 'note' when not specified")
		void defaultNoteType() {
			// Stub DB to return a basic note row
			when(db.queryForList(anyString(), anyString(), anyString()))
					.thenReturn(List.of(noteRow("note-1", "Note", "note", 0)));
			notesService.create(OWNER, Map.of("title", "Note"));
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			verify(db).update(contains("INSERT INTO notes"), cap.capture());
			// note_type is at index 5 in the INSERT args — must default to "note"
			assertThat(cap.getValue()[5]).isEqualTo("note");
		}

		/**
		 * Verifies an explicit {@code checklist} type is persisted.
		 *
		 * <p>
		 * When {@code note_type=checklist} is supplied, the captured INSERT arg at
		 * index 5 must be {@code "checklist"}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("checklist note type is persisted when explicitly set")
		void checklistTypePersistedWhenSet() {
			// Stub DB to return a checklist note row
			when(db.queryForList(anyString(), anyString(), anyString()))
					.thenReturn(List.of(noteRow("note-1", "Checklist", "checklist", 0)));
			notesService.create(OWNER, Map.of("title", "Checklist", "note_type", "checklist", "items",
					List.of(Map.of("text", "Task 1", "checked", false))));
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			verify(db).update(contains("INSERT INTO notes"), cap.capture());
			// note_type at index 5 must be "checklist" when explicitly specified
			assertThat(cap.getValue()[5]).isEqualTo("checklist");
		}

		/**
		 * Verifies the owner is taken from the method argument, not the body.
		 *
		 * <p>
		 * SECURITY: the captured INSERT owner arg (index 1) must equal the
		 * authenticated owner passed to {@code create()}, never a body-supplied
		 * value.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("owner is stored from the argument — not from request body")
		void ownerFromArgument() {
			// SECURITY: owner must come from the authenticated session argument, not the
			// request body
			when(db.queryForList(anyString(), anyString(), anyString()))
					.thenReturn(List.of(noteRow("note-1", "Note", "note", 0)));
			notesService.create(OWNER, Map.of("title", "Note"));
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			verify(db).update(contains("INSERT INTO notes"), cap.capture());
			// args[1] = owner — must be exactly the owner passed to create()
			assertThat(cap.getValue()[1]).isEqualTo(OWNER);
		}

		/**
		 * Verifies {@code color} defaults to {@code "default"} when unspecified.
		 *
		 * <p>
		 * The captured INSERT arg at index 6 must be {@code "default"}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("default color is 'default' when not specified")
		void defaultColorIsDefault() {
			// Stub DB for the post-insert fetch
			when(db.queryForList(anyString(), anyString(), anyString()))
					.thenReturn(List.of(noteRow("note-1", "Note", "note", 0)));
			notesService.create(OWNER, Map.of("title", "Note"));
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			verify(db).update(contains("INSERT INTO notes"), cap.capture());
			// args[6] = color — must default to "default" when not provided
			assertThat(cap.getValue()[6]).isEqualTo("default");
		}

		/**
		 * Verifies a caller-supplied color is persisted verbatim.
		 *
		 * <p>
		 * For each colour in the value source, the captured INSERT arg at index 6
		 * must equal the supplied colour.
		 *
		 * @param color the colour supplied by the value source
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@ParameterizedTest(name = "color={0}")
		@ValueSource(strings = { "yellow", "green", "blue", "pink", "purple", "orange" })
		@DisplayName("custom color is stored correctly")
		void customColorStored(String color) {
			// Stub DB to return note row with the specified color
			when(db.queryForList(anyString(), anyString(), anyString()))
					.thenReturn(List.of(noteRow("note-1", "Note", "note", 0)));
			notesService.create(OWNER, Map.of("title", "Note", "color", color));
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			verify(db).update(contains("INSERT INTO notes"), cap.capture());
			// args[6] = color — the caller-supplied value must be persisted verbatim
			assertThat(cap.getValue()[6]).isEqualTo(color);
		}
	}

	// ── delete() ─────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code delete()} — owner gate and miss handling.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("delete()")
	class Delete {

		/**
		 * Verifies deleting an owned note issues a scoped DELETE and does not
		 * throw.
		 *
		 * <p>
		 * With the DB reporting one row affected, the DELETE must include both id
		 * and owner in its WHERE clause.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("executes DELETE WHERE id=? AND owner=?")
		void deletesOwnedNote() {
			// Stub: DB reports one row deleted (note found and owned)
			when(db.update(contains("DELETE FROM notes"), anyString(), anyString())).thenReturn(1);
			assertThatCode(() -> notesService.delete("note-1", OWNER)).doesNotThrowAnyException();
			// Both id and owner must be in the WHERE clause to prevent cross-user deletion
			verify(db).update(contains("DELETE FROM notes"), eq("note-1"), eq(OWNER));
		}

		/**
		 * Verifies a no-op delete raises {@link NoSuchElementException}.
		 *
		 * <p>
		 * When the DB reports 0 rows affected (missing or not owned), the service
		 * must throw with the note id in the message for diagnostics.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("throws NoSuchElementException when note not owned or missing")
		void throwsWhenNotFound() {
			// Stub: DB reports 0 rows deleted — note not found or not owned by this user
			when(db.update(contains("DELETE FROM notes"), anyString(), anyString())).thenReturn(0);
			assertThatThrownBy(() -> notesService.delete("note-999", OWNER)).isInstanceOf(NoSuchElementException.class)
					// Exception message must include the note ID for diagnostics
					.hasMessageContaining("note-999");
		}

		/**
		 * Verifies a user cannot delete another user's note.
		 *
		 * <p>
		 * SECURITY: a delete with a foreign owner returns 0 rows and must surface
		 * as {@link NoSuchElementException}, never a silent success.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("does not delete notes owned by other users (WHERE owner=?)")
		void doesNotDeleteOtherOwnerNotes() {
			// Stub: deletion attempt for a different owner returns 0 rows
			when(db.update(contains("DELETE FROM notes"), anyString(), anyString())).thenReturn(0);
			// SECURITY: attempting to delete another user's note must fail with
			// NoSuchElementException
			assertThatThrownBy(() -> notesService.delete("note-1", "other-user"))
					.isInstanceOf(NoSuchElementException.class);
		}
	}

	// ── getById() ─────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code getById()} — lookup hit and miss.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("getById()")
	class GetById {

		/**
		 * Verifies a miss returns null rather than throwing.
		 *
		 * <p>
		 * An empty result set is the "not found" contract and must yield
		 * {@code null}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("returns null when not found")
		void returnsNullWhenNotFound() {
			// Stub: DB returns empty list — note does not exist for this owner
			when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of());
			// Null is the contract for "not found" — must not throw
			assertThat(notesService.getById("note-999", OWNER)).isNull();
		}

		/**
		 * Verifies a hit returns the mapped note.
		 *
		 * <p>
		 * With a matching row stubbed, the returned note must be non-null,
		 * confirming the row mapped successfully.
		 *
		 * @throws Exception if the mocked JSON mapper signals a checked failure
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("returns mapped note when found")
		void returnsMappedNote() throws Exception {
			// Stub: DB returns one matching row
			when(db.queryForList(anyString(), anyString(), anyString()))
					.thenReturn(List.of(noteRow("note-1", "My Note", "note", 0)));
			when(mapper.readValue(anyString(), eq(List.class))).thenReturn(null);

			var note = notesService.getById("note-1", OWNER);
			// Non-null result means the row was found and mapped successfully
			assertThat(note).isNotNull();
		}
	}

	// ── list() ────────────────────────────────────────────────────────────────

	/**
	 * Tests for {@code list()} — archive and label filtering.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("list()")
	class ListNotes {

		/**
		 * Verifies the default view filters out archived notes.
		 *
		 * <p>
		 * Listing with {@code archived=false} must issue a query carrying the
		 * {@code archived=0} guard.
		 *
		 * @throws Exception if the mocked JSON mapper signals a checked failure
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("non-archived query uses WHERE archived=0")
		void nonArchivedQueryFiltersArchived() throws Exception {
			// Stub: query with archived=0 filter returns empty list
			when(db.queryForList(contains("archived=0"), eq(OWNER))).thenReturn(List.of());
			when(mapper.readValue(anyString(), eq(List.class))).thenReturn(null);
			notesService.list(OWNER, false, null);
			// Verify the SQL includes the archived=0 guard — default view must exclude
			// archived notes
			verify(db).queryForList(contains("archived=0"), eq(OWNER));
		}

		/**
		 * Verifies the archived view omits the {@code archived=0} clause.
		 *
		 * <p>
		 * Listing with {@code archived=true} must never issue a query containing
		 * {@code archived=0}, so archived notes are included.
		 *
		 * @throws Exception if the mocked JSON mapper signals a checked failure
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("archived=true does not add archived=0 filter")
		void archivedTrueNoFilter() throws Exception {
			// Stub: archived list query includes all notes regardless of archived flag
			when(db.queryForList(contains("WHERE owner=?"), eq(OWNER))).thenReturn(List.of());
			when(mapper.readValue(anyString(), eq(List.class))).thenReturn(null);
			notesService.list(OWNER, true, null);
			// When requesting archived notes, the "archived=0" clause must NOT appear
			verify(db, never()).queryForList(contains("archived=0"), any(Object[].class));
		}

		/**
		 * Verifies a label filter is appended as a bound parameter.
		 *
		 * <p>
		 * Listing with a label must issue a query containing {@code label=?} with
		 * the owner and label bound — never string-concatenated.
		 *
		 * @throws Exception if the mocked JSON mapper signals a checked failure
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("label filter adds AND label=? clause")
		void labelFilterAddsClause() throws Exception {
			// Stub: query with label filter returns empty list
			when(db.queryForList(contains("label=?"), eq(OWNER), eq("work"))).thenReturn(List.of());
			when(mapper.readValue(anyString(), eq(List.class))).thenReturn(null);
			notesService.list(OWNER, false, "work");
			// Label filter must be appended as a bound parameter — not string-concatenated
			verify(db).queryForList(contains("label=?"), eq(OWNER), eq("work"));
		}
	}
}
