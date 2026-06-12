package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;

/**
 * OCD-level unit tests for {@link CalendarService}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * {@link CalendarService} backs the calendar feature: calendars, events, range
 * queries and ICS export. The tests pin owner-scoping on every mutation (so one
 * user can never touch another's calendar), the BUG-027 validation regressions
 * (missing or inverted times must produce a 400, never a NOT-NULL 500), and the
 * structural correctness of exported iCalendar text.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Runs under {@link MockitoExtension} with {@link Strictness#LENIENT} so the
 * shared count/lookup stubs reused across nested groups do not raise
 * unnecessary-stubbing failures.</li>
 * <li>{@link JdbcTemplate} and {@link ObjectMapper} are mocked and injected;
 * {@link #calRow(String)} and {@link #evtRow(String, String)} build
 * representative rows.</li>
 * <li>Nested classes mirror the public method surface so a failure name points
 * straight at the operation under test.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — calendar/event CRUD, range queries, ICS export and BUG-027
 * time-validation coverage.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CalendarService — unit tests")
class CalendarServiceTest {

	/** Canonical owner id used across all calendar/event fixtures. */
	private static final String OWNER = UserFactory.USER_ID;

	/** Mocked JDBC template capturing the SQL the service issues. */
	@Mock
	JdbcTemplate db;
	/** Mocked JSON mapper for event metadata (de)serialisation paths. */
	@Mock
	ObjectMapper mapper;

	/** Service under test with mocks injected. */
	@InjectMocks
	CalendarService calendarService;

	/**
	 * Builds a representative calendar DB row for the given id.
	 *
	 * @param id the calendar id to embed
	 * @return an immutable map mirroring a {@code calendars} row
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	private Map<String, Object> calRow(String id) {
		return Map.of("id", id, "owner", OWNER, "name", "Work", "color", "#F5C800", "is_default", 1, "created_at",
				"2026-01-01T00:00:00Z");
	}

	/**
	 * Builds a representative calendar-event DB row.
	 *
	 * @param id    the event id to embed
	 * @param calId the owning calendar id
	 * @return an immutable map mirroring a {@code calendar_events} row
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	private Map<String, Object> evtRow(String id, String calId) {
		return Map.of("id", id, "calendar_id", calId, "uid", "uid-1", "title", "Meeting", "start_at",
				"2026-06-01T09:00:00Z", "end_at", "2026-06-01T10:00:00Z", "status", "confirmed");
	}

	// ── createCalendar() ──────────────────────────────────────────────────────

	/**
	 * Tests for {@code createCalendar()} — creation, id prefix and ownership.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("createCalendar()")
	class CreateCalendar {

		/**
		 * Verifies creating a calendar issues an INSERT carrying the owner.
		 *
		 * <p>
		 * With no existing calendars and the created row stubbed, exactly one
		 * INSERT into {@code calendars} must fire so the owner is persisted.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("INSERT is called with the owner")
		void insertsOwner() {
			// Stub: no existing calendars (count=0), then return the created row
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), eq(OWNER))).thenReturn(0);
			when(db.queryForList(contains("FROM calendars WHERE id"), anyString(), anyString()))
					.thenReturn(List.of(calRow("cal-abc")));
			calendarService.createCalendar(OWNER, Map.of("name", "Work"));
			// Verify INSERT was called — owner must be persisted
			verify(db).update(contains("INSERT INTO calendars"), any(Object[].class));
		}

		/**
		 * Verifies the generated calendar id carries the {@code cal-} prefix.
		 *
		 * <p>
		 * The prefix identifies calendar ids across the system; the returned row's
		 * id must start with {@code "cal-"}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returned id starts with 'cal-'")
		void idStartsWithPrefix() {
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), eq(OWNER))).thenReturn(0);
			when(db.queryForList(contains("FROM calendars WHERE id"), anyString(), anyString()))
					.thenReturn(List.of(calRow("cal-xyz")));
			Map<String, Object> result = calendarService.createCalendar(OWNER, Map.of());
			// cal- prefix identifies calendar IDs across the system
			assertThat(result.get("id").toString()).startsWith("cal-");
		}

		/**
		 * Verifies the owner is recorded on the created calendar.
		 *
		 * <p>
		 * The returned record's {@code owner} must equal the caller for later
		 * authorization checks.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("owner is stored in returned record")
		void ownerStored() {
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), eq(OWNER))).thenReturn(1);
			when(db.queryForList(contains("FROM calendars WHERE id"), anyString(), anyString()))
					.thenReturn(List.of(calRow("cal-1")));
			Map<String, Object> result = calendarService.createCalendar(OWNER, Map.of());
			assertThat(result.get("owner")).isEqualTo(OWNER);
		}
	}

	// ── getCalendar() ─────────────────────────────────────────────────────────

	/**
	 * Tests for {@code getCalendar()} — lookup hit and miss.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("getCalendar()")
	class GetCalendar {

		/**
		 * Verifies a miss returns null.
		 *
		 * <p>
		 * An empty result (missing calendar or another owner's) yields
		 * {@code null}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns null when empty result")
		void nullWhenEmpty() {
			// Stub: no rows found — calendar does not exist or belongs to another owner
			when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of());
			assertThat(calendarService.getCalendar("cal-1", OWNER)).isNull();
		}

		/**
		 * Verifies a hit returns the mapped row.
		 *
		 * <p>
		 * When the calendar exists, the result is non-null and its id matches the
		 * requested id.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns mapped row when found")
		void returnsMappedRow() {
			// Stub: calendar found in DB
			when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of(calRow("cal-1")));
			Map<String, Object> result = calendarService.getCalendar("cal-1", OWNER);
			assertThat(result).isNotNull();
			assertThat(result.get("id")).isEqualTo("cal-1");
		}
	}

	// ── listCalendars() ───────────────────────────────────────────────────────

	/**
	 * Tests for {@code listCalendars()} — owner-scoped listing.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("listCalendars()")
	class ListCalendars {

		/**
		 * Verifies listing binds the owner as the WHERE parameter.
		 *
		 * <p>
		 * The single stubbed calendar is returned and the owner is confirmed as
		 * the query parameter, guarding against cross-user leakage.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("queries with owner parameter")
		void queriesWithOwner() {
			// Stub: one calendar for this owner
			when(db.queryForList(anyString(), eq(OWNER))).thenReturn(List.of(calRow("cal-1")));
			List<Map<String, Object>> results = calendarService.listCalendars(OWNER);
			assertThat(results).hasSize(1);
			// Verify owner was passed as WHERE parameter — no cross-user data leakage
			verify(db).queryForList(anyString(), eq(OWNER));
		}
	}

	// ── deleteCalendar() ──────────────────────────────────────────────────────

	/**
	 * Tests for {@code deleteCalendar()} — owner-scoped deletion.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("deleteCalendar()")
	class DeleteCalendar {

		/**
		 * Verifies deletion is scoped by both id and owner.
		 *
		 * <p>
		 * The DELETE must contain {@code WHERE id=? AND owner=?} to prevent one
		 * user deleting another's calendar.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("DELETE WHERE id=? AND owner=? is called")
		void deletesWithIdAndOwner() {
			calendarService.deleteCalendar("cal-1", OWNER);
			// Both id and owner must be in WHERE clause — prevents cross-user deletion
			verify(db).update(contains("DELETE FROM calendars WHERE id=? AND owner=?"), eq("cal-1"), eq(OWNER));
		}
	}

	// ── createEvent() ─────────────────────────────────────────────────────────

	/**
	 * Tests for {@code createEvent()} — creation and time validation (BUG-027).
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("createEvent()")
	class CreateEvent {

		/**
		 * Verifies a valid event inserts and gets an {@code evt-}-prefixed id.
		 *
		 * <p>
		 * With the parent calendar present, exactly one INSERT into
		 * {@code calendar_events} fires and the returned id starts with
		 * {@code "evt-"}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("INSERT is called, id starts with 'evt-'")
		void insertsEvent() {
			// Stub: calendar exists (count > 0), then return the created event row
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
			when(db.queryForList(contains("FROM calendar_events WHERE id"), anyString()))
					.thenReturn(List.of(evtRow("evt-1", "cal-1")));
			Map<String, Object> result = calendarService.createEvent("cal-1", OWNER,
					Map.of("title", "Meeting", "start_at", "2026-06-01T09:00:00Z", "end_at", "2026-06-01T10:00:00Z"));
			verify(db).update(contains("INSERT INTO calendar_events"), any(Object[].class));
			assertThat(result.get("id").toString()).startsWith("evt-");
		}

		/**
		 * Verifies the parent {@code calendar_id} is stored on the event.
		 *
		 * <p>
		 * The returned record's {@code calendar_id} must equal the parent so
		 * events can be listed by calendar.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("calendar_id is stored in returned record")
		void calendarIdStored() {
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
			when(db.queryForList(contains("FROM calendar_events WHERE id"), anyString()))
					.thenReturn(List.of(evtRow("evt-1", "cal-1")));
			Map<String, Object> result = calendarService.createEvent("cal-1", OWNER,
					Map.of("start_at", "2026-06-01T09:00:00Z", "end_at", "2026-06-01T10:00:00Z"));
			// calendar_id must be stored so events can be listed by calendar
			assertThat(result.get("calendar_id")).isEqualTo("cal-1");
		}

		/**
		 * Verifies missing start/end times raise a 400, not a NOT-NULL 500
		 * (BUG-027 regression).
		 *
		 * <p>
		 * Omitting the NOT-NULL {@code start_at}/{@code end_at} columns must throw
		 * {@link IllegalArgumentException} before any INSERT, rather than letting a
		 * null reach the database.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects an event with missing start_at/end_at (400, not a 500 NOT-NULL crash) — BUG-027")
		void rejectsMissingTimes() {
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
			// start_at / end_at are NOT-NULL columns; omitting them previously let a null
			// reach the INSERT and surfaced as a misleading 500. Must be a 400 instead.
			assertThatThrownBy(() -> calendarService.createEvent("cal-1", OWNER, Map.of("title", "NoTimes")))
					.isInstanceOf(IllegalArgumentException.class);
			verify(db, never()).update(contains("INSERT INTO calendar_events"), any(Object[].class));
		}

		/**
		 * Verifies an event whose end precedes its start is rejected.
		 *
		 * <p>
		 * An inverted time range must throw {@link IllegalArgumentException} and
		 * never persist a malformed event.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("rejects an event whose end is before its start (400, not a stored bad event)")
		void rejectsEndBeforeStart() {
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
			assertThatThrownBy(() -> calendarService.createEvent("cal-1", OWNER,
					Map.of("title", "X", "start_at", "2027-01-02T10:00:00Z", "end_at", "2027-01-01T10:00:00Z")))
					.isInstanceOf(IllegalArgumentException.class);
			// Must not have written a malformed event.
			verify(db, never()).update(contains("INSERT INTO calendar_events"), any(Object[].class));
		}
	}

	// ── updateEvent() ─────────────────────────────────────────────────────────

	/**
	 * Tests for {@code updateEvent()} — id-scoped updates.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("updateEvent()")
	class UpdateEvent {

		/**
		 * Verifies updating an existing event issues an UPDATE by id.
		 *
		 * <p>
		 * With the event present and its parent calendar owned by the caller, the
		 * service must issue an {@code UPDATE calendar_events} statement.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("UPDATE WHERE id=? is called")
		void updatesById() {
			// Stub: event exists in the calendar
			when(db.queryForList(contains("FROM calendar_events WHERE id"), eq("evt-1")))
					.thenReturn(List.of(evtRow("evt-1", "cal-1")));
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
			calendarService.updateEvent("evt-1", OWNER, Map.of("title", "Updated Meeting"));
			verify(db).update(contains("UPDATE calendar_events"), any(Object[].class));
		}
	}

	// ── deleteEvent() ─────────────────────────────────────────────────────────

	/**
	 * Tests for {@code deleteEvent()} — present and missing event paths.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("deleteEvent()")
	class DeleteEvent {

		/**
		 * Verifies an existing event is deleted by id.
		 *
		 * <p>
		 * When the event exists in the owner's calendar, a
		 * {@code DELETE FROM calendar_events WHERE id=?} must fire for that id.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("DELETE WHERE id=? is called when event exists")
		void deletesWhenFound() {
			// Stub: event exists and belongs to the owner's calendar
			when(db.queryForList(contains("FROM calendar_events WHERE id"), eq("evt-1")))
					.thenReturn(List.of(evtRow("evt-1", "cal-1")));
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
			calendarService.deleteEvent("evt-1", OWNER);
			verify(db).update(contains("DELETE FROM calendar_events WHERE id=?"), eq("evt-1"));
		}

		/**
		 * Verifies a missing event triggers no delete.
		 *
		 * <p>
		 * When the lookup returns no rows, the service must not issue any DELETE.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("no delete when event not found")
		void noDeleteWhenMissing() {
			// Stub: event not found — no rows returned
			when(db.queryForList(contains("FROM calendar_events WHERE id"), anyString())).thenReturn(List.of());
			calendarService.deleteEvent("evt-999", OWNER);
			// No delete should occur for a non-existent event
			verify(db, never()).update(contains("DELETE FROM calendar_events"), any(Object[].class));
		}
	}

	// ── listEventsInRange() ───────────────────────────────────────────────────

	/**
	 * Tests for {@code listEventsInRange()} — owner/from/to filtering.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("listEventsInRange()")
	class ListEventsInRange {

		/**
		 * Verifies the range query binds owner, from and to.
		 *
		 * <p>
		 * All three parameters must reach the query for correct date filtering;
		 * the single stubbed in-range event is returned.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("queries with owner, from, to parameters")
		void queriesWithAllParams() {
			// Stub: one event in the date range
			when(db.queryForList(anyString(), eq(OWNER), eq("2026-06-01T00:00:00Z"), eq("2026-06-30T23:59:59Z")))
					.thenReturn(List.of(evtRow("evt-1", "cal-1")));
			List<Map<String, Object>> results = calendarService.listEventsInRange(OWNER, "2026-06-01T00:00:00Z",
					"2026-06-30T23:59:59Z");
			// All three parameters (owner, from, to) must be passed for correct filtering
			assertThat(results).hasSize(1);
		}
	}

	// ── exportCalendarAsIcs() ─────────────────────────────────────────────────

	/**
	 * Tests for {@code exportCalendarAsIcs()} — iCalendar serialisation.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("exportCalendarAsIcs()")
	class ExportCalendarAsIcs {

		/**
		 * Verifies the export is wrapped in a VCALENDAR envelope.
		 *
		 * <p>
		 * Valid iCalendar output must start with {@code BEGIN:VCALENDAR} and
		 * contain {@code END:VCALENDAR}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns string starting with BEGIN:VCALENDAR")
		void returnsValidIcs() {
			// Stub: calendar exists and has one event
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
			when(db.queryForList(contains("FROM calendar_events WHERE calendar_id"), anyString()))
					.thenReturn(List.of(evtRow("evt-1", "cal-1")));
			String ics = calendarService.exportCalendarAsIcs("cal-1", OWNER);
			// Valid iCalendar format requires BEGIN/END:VCALENDAR wrapping
			assertThat(ics).startsWith("BEGIN:VCALENDAR");
			assertThat(ics).contains("END:VCALENDAR");
		}

		/**
		 * Verifies each event is serialised into a VEVENT block with its title.
		 *
		 * <p>
		 * A single titled event must produce {@code BEGIN:VEVENT}/
		 * {@code END:VEVENT} blocks containing the event title in the output.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("contains event data in ICS output")
		void containsEventData() {
			Map<String, Object> evt = Map.of("id", "evt-1", "calendar_id", "cal-1", "uid", "uid-abc", "title",
					"Team Meeting", "start_at", "2026-06-01T09:00:00Z", "end_at", "2026-06-01T10:00:00Z", "status",
					"confirmed");
			when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
			// Stub: calendar has one specific event to verify ICS content
			when(db.queryForList(contains("FROM calendar_events WHERE calendar_id"), anyString()))
					.thenReturn(List.of(evt));
			String ics = calendarService.exportCalendarAsIcs("cal-1", OWNER);
			// Each event must produce BEGIN:VEVENT / END:VEVENT blocks with the event title
			assertThat(ics).contains("BEGIN:VEVENT");
			assertThat(ics).contains("END:VEVENT");
			assertThat(ics).contains("Team Meeting");
		}
	}
}
