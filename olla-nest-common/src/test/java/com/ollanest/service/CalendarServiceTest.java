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
 * OCD-level unit tests for {@link CalendarService}.
 *
 * <p>Covers calendar CRUD, event CRUD, range queries, and ICS export.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CalendarService — unit tests")
class CalendarServiceTest {

    private static final String OWNER = UserFactory.USER_ID;

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;
    @Mock CryptoService cryptoService;

    @InjectMocks CalendarService calendarService;

    private Map<String, Object> calRow(String id) {
        return Map.of("id", id, "owner", OWNER, "name", "Work", "color", "#F5C800",
                "is_default", 1, "created_at", "2026-01-01T00:00:00Z");
    }

    private Map<String, Object> evtRow(String id, String calId) {
        return Map.of("id", id, "calendar_id", calId, "uid", "uid-1",
                "title", "Meeting", "start_at", "2026-06-01T09:00:00Z",
                "end_at", "2026-06-01T10:00:00Z", "status", "confirmed");
    }

    // ── createCalendar() ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("createCalendar()")
    class CreateCalendar {

        @Test
        @DisplayName("INSERT is called with the owner")
        void insertsOwner() {
            when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), eq(OWNER))).thenReturn(0);
            when(db.queryForList(contains("FROM calendars WHERE id"), anyString(), anyString()))
                    .thenReturn(List.of(calRow("cal-abc")));
            calendarService.createCalendar(OWNER, Map.of("name", "Work"));
            verify(db).update(contains("INSERT INTO calendars"), (Object[]) any());
        }

        @Test
        @DisplayName("returned id starts with 'cal-'")
        void idStartsWithPrefix() {
            when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), eq(OWNER))).thenReturn(0);
            when(db.queryForList(contains("FROM calendars WHERE id"), anyString(), anyString()))
                    .thenReturn(List.of(calRow("cal-xyz")));
            Map<String, Object> result = calendarService.createCalendar(OWNER, Map.of());
            assertThat(result.get("id").toString()).startsWith("cal-");
        }

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

    @Nested
    @DisplayName("getCalendar()")
    class GetCalendar {

        @Test
        @DisplayName("returns null when empty result")
        void nullWhenEmpty() {
            when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of());
            assertThat(calendarService.getCalendar("cal-1", OWNER)).isNull();
        }

        @Test
        @DisplayName("returns mapped row when found")
        void returnsMappedRow() {
            when(db.queryForList(anyString(), anyString(), anyString()))
                    .thenReturn(List.of(calRow("cal-1")));
            Map<String, Object> result = calendarService.getCalendar("cal-1", OWNER);
            assertThat(result).isNotNull();
            assertThat(result.get("id")).isEqualTo("cal-1");
        }
    }

    // ── listCalendars() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("listCalendars()")
    class ListCalendars {

        @Test
        @DisplayName("queries with owner parameter")
        void queriesWithOwner() {
            when(db.queryForList(anyString(), eq(OWNER))).thenReturn(List.of(calRow("cal-1")));
            List<Map<String, Object>> results = calendarService.listCalendars(OWNER);
            assertThat(results).hasSize(1);
            verify(db).queryForList(anyString(), eq(OWNER));
        }
    }

    // ── deleteCalendar() ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteCalendar()")
    class DeleteCalendar {

        @Test
        @DisplayName("DELETE WHERE id=? AND owner=? is called")
        void deletesWithIdAndOwner() {
            calendarService.deleteCalendar("cal-1", OWNER);
            verify(db).update(contains("DELETE FROM calendars WHERE id=? AND owner=?"), eq("cal-1"), eq(OWNER));
        }
    }

    // ── createEvent() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createEvent()")
    class CreateEvent {

        @Test
        @DisplayName("INSERT is called, id starts with 'evt-'")
        void insertsEvent() {
            when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
            when(db.queryForList(contains("FROM calendar_events WHERE id"), anyString()))
                    .thenReturn(List.of(evtRow("evt-1", "cal-1")));
            Map<String, Object> result = calendarService.createEvent("cal-1", OWNER,
                    Map.of("title", "Meeting", "start_at", "2026-06-01T09:00:00Z", "end_at", "2026-06-01T10:00:00Z"));
            verify(db).update(contains("INSERT INTO calendar_events"), (Object[]) any());
            assertThat(result.get("id").toString()).startsWith("evt-");
        }

        @Test
        @DisplayName("calendar_id is stored in returned record")
        void calendarIdStored() {
            when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
            when(db.queryForList(contains("FROM calendar_events WHERE id"), anyString()))
                    .thenReturn(List.of(evtRow("evt-1", "cal-1")));
            Map<String, Object> result = calendarService.createEvent("cal-1", OWNER, Map.of());
            assertThat(result.get("calendar_id")).isEqualTo("cal-1");
        }
    }

    // ── updateEvent() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateEvent()")
    class UpdateEvent {

        @Test
        @DisplayName("UPDATE WHERE id=? is called")
        void updatesById() {
            when(db.queryForList(contains("FROM calendar_events WHERE id"), eq("evt-1")))
                    .thenReturn(List.of(evtRow("evt-1", "cal-1")));
            when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
            calendarService.updateEvent("evt-1", OWNER, Map.of("title", "Updated Meeting"));
            verify(db).update(contains("UPDATE calendar_events"), (Object[]) any());
        }
    }

    // ── deleteEvent() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteEvent()")
    class DeleteEvent {

        @Test
        @DisplayName("DELETE WHERE id=? is called when event exists")
        void deletesWhenFound() {
            when(db.queryForList(contains("FROM calendar_events WHERE id"), eq("evt-1")))
                    .thenReturn(List.of(evtRow("evt-1", "cal-1")));
            when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
            calendarService.deleteEvent("evt-1", OWNER);
            verify(db).update(contains("DELETE FROM calendar_events WHERE id=?"), eq("evt-1"));
        }

        @Test
        @DisplayName("no delete when event not found")
        void noDeleteWhenMissing() {
            when(db.queryForList(contains("FROM calendar_events WHERE id"), anyString())).thenReturn(List.of());
            calendarService.deleteEvent("evt-999", OWNER);
            verify(db, never()).update(contains("DELETE FROM calendar_events"), (Object[]) any());
        }
    }

    // ── listEventsInRange() ───────────────────────────────────────────────────

    @Nested
    @DisplayName("listEventsInRange()")
    class ListEventsInRange {

        @Test
        @DisplayName("queries with owner, from, to parameters")
        void queriesWithAllParams() {
            when(db.queryForList(anyString(), eq(OWNER), eq("2026-06-01T00:00:00Z"), eq("2026-06-30T23:59:59Z")))
                    .thenReturn(List.of(evtRow("evt-1", "cal-1")));
            List<Map<String, Object>> results = calendarService.listEventsInRange(OWNER, "2026-06-01T00:00:00Z", "2026-06-30T23:59:59Z");
            assertThat(results).hasSize(1);
        }
    }

    // ── exportCalendarAsIcs() ─────────────────────────────────────────────────

    @Nested
    @DisplayName("exportCalendarAsIcs()")
    class ExportCalendarAsIcs {

        @Test
        @DisplayName("returns string starting with BEGIN:VCALENDAR")
        void returnsValidIcs() {
            when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
            when(db.queryForList(contains("FROM calendar_events WHERE calendar_id"), anyString()))
                    .thenReturn(List.of(evtRow("evt-1", "cal-1")));
            String ics = calendarService.exportCalendarAsIcs("cal-1", OWNER);
            assertThat(ics).startsWith("BEGIN:VCALENDAR");
            assertThat(ics).contains("END:VCALENDAR");
        }

        @Test
        @DisplayName("contains event data in ICS output")
        void containsEventData() {
            Map<String, Object> evt = Map.of(
                    "id", "evt-1", "calendar_id", "cal-1", "uid", "uid-abc",
                    "title", "Team Meeting", "start_at", "2026-06-01T09:00:00Z",
                    "end_at", "2026-06-01T10:00:00Z", "status", "confirmed");
            when(db.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
            when(db.queryForList(contains("FROM calendar_events WHERE calendar_id"), anyString()))
                    .thenReturn(List.of(evt));
            String ics = calendarService.exportCalendarAsIcs("cal-1", OWNER);
            assertThat(ics).contains("BEGIN:VEVENT");
            assertThat(ics).contains("END:VEVENT");
            assertThat(ics).contains("Team Meeting");
        }
    }
}
