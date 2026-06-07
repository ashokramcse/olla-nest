package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.CalendarService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * REST controller for calendars and events: CRUD plus iCalendar ({@code .ics})
 * export.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Provides the user-facing surface over a personal scheduling system — multiple
 * calendars, events within them, range queries, and standards-compliant
 * {@code .ics} export for interoperability with external calendar apps.
 * Persistence, range filtering, and serialization are delegated to
 * {@link CalendarService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Endpoints are grouped into calendar-level and event-level operations
 * (marked by the section comments below).</li>
 * <li>Every endpoint resolves the caller via {@link BaseController#requireAuth}
 * and scopes operations to that user's id.</li>
 * <li>{@link #listEvents} applies wide default bounds when {@code from}/{@code to}
 * are omitted, effectively returning all events.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — documented as part of the project-wide Javadoc pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@RestController
@RequestMapping("/api/calendar")
public class CalendarController extends BaseController {

    /** Service backing calendar/event persistence and iCalendar export. */
    private final CalendarService calendarService;

    /**
     * Constructor-injects the calendar service.
     *
     * @param calendarService the service backing all calendar operations
     * @since v2026.2.1
     */
    public CalendarController(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    // ── Calendars ─────────────────────────────────────────────────────────────

    /**
     * Lists the calling user's calendars.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @return an OK response with the user's calendars
     * @since v2026.2.1
     */
    @GetMapping("/calendars")
    public ResponseEntity<?> listCalendars(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(calendarService.listCalendars(user.id));
    }

    /**
     * Creates a new calendar for the calling user.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param body the calendar definition
     * @return a CREATED response with the persisted calendar
     * @since v2026.2.1
     */
    @PostMapping("/calendars")
    public ResponseEntity<?> createCalendar(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(calendarService.createCalendar(user.id, body));
    }

    /**
     * Deletes a calendar owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the calendar to delete
     * @return an OK response acknowledging the deletion
     * @since v2026.2.1
     */
    @DeleteMapping("/calendars/{id}")
    public ResponseEntity<?> deleteCalendar(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        calendarService.deleteCalendar(id, user.id);
        return ok(Map.of("ok", true));
    }

    /**
     * Exports a calendar as a downloadable iCalendar ({@code .ics}) file.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the calendar to export
     * @return a {@code text/calendar} attachment containing the calendar
     * @since v2026.2.1
     */
    @GetMapping("/calendars/{id}/export.ics")
    public ResponseEntity<String> exportIcs(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        String ics = calendarService.exportCalendarAsIcs(id, user.id);
        return ResponseEntity.ok()
                .header("Content-Type", "text/calendar; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"calendar.ics\"")
                .body(ics);
    }

    // ── Events ────────────────────────────────────────────────────────────────

    /**
     * Lists the calling user's events within an optional time range.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param from inclusive ISO-8601 start bound (defaults to a far past date)
     * @param to   inclusive ISO-8601 end bound (defaults to a far future date)
     * @return an OK response with the events in range
     * @since v2026.2.1
     */
    @GetMapping("/events")
    public ResponseEntity<?> listEvents(HttpServletRequest req,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        User user = requireAuth(req);
        String f = from != null ? from : "2000-01-01T00:00:00Z";
        String t = to != null ? to : "2099-12-31T23:59:59Z";
        return ok(calendarService.listEventsInRange(user.id, f, t));
    }

    /**
     * Creates an event within a calendar owned by the calling user.
     *
     * @param req        the HTTP request, used to resolve the authenticated user
     * @param calendarId the calendar to add the event to
     * @param body       the event definition
     * @return a CREATED response with the persisted event
     * @since v2026.2.1
     */
    @PostMapping("/calendars/{calendarId}/events")
    public ResponseEntity<?> createEvent(HttpServletRequest req,
            @PathVariable String calendarId, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(calendarService.createEvent(calendarId, user.id, body));
    }

    /**
     * Updates an existing event owned by the calling user.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param id   the id of the event to update
     * @param body the updated event fields
     * @return an OK response with the updated event
     * @since v2026.2.1
     */
    @PutMapping("/events/{id}")
    public ResponseEntity<?> updateEvent(HttpServletRequest req,
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return ok(calendarService.updateEvent(id, user.id, body));
    }

    /**
     * Deletes an event owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the event to delete
     * @return an OK response acknowledging the deletion
     * @since v2026.2.1
     */
    @DeleteMapping("/events/{id}")
    public ResponseEntity<?> deleteEvent(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        calendarService.deleteEvent(id, user.id);
        return ok(Map.of("ok", true));
    }
}
