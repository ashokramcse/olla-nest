package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.CalendarService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Calendar API — CRUD for calendars and events, .ics export. */
@RestController
@RequestMapping("/api/calendar")
public class CalendarController extends BaseController {

    private final CalendarService calendarService;

    public CalendarController(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    // ── Calendars ─────────────────────────────────────────────────────────────

    @GetMapping("/calendars")
    public ResponseEntity<?> listCalendars(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(calendarService.listCalendars(user.id));
    }

    @PostMapping("/calendars")
    public ResponseEntity<?> createCalendar(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(calendarService.createCalendar(user.id, body));
    }

    @DeleteMapping("/calendars/{id}")
    public ResponseEntity<?> deleteCalendar(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        calendarService.deleteCalendar(id, user.id);
        return ok(Map.of("ok", true));
    }

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

    @GetMapping("/events")
    public ResponseEntity<?> listEvents(HttpServletRequest req,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        User user = requireAuth(req);
        String f = from != null ? from : "2000-01-01T00:00:00Z";
        String t = to != null ? to : "2099-12-31T23:59:59Z";
        return ok(calendarService.listEventsInRange(user.id, f, t));
    }

    @PostMapping("/calendars/{calendarId}/events")
    public ResponseEntity<?> createEvent(HttpServletRequest req,
            @PathVariable String calendarId, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(calendarService.createEvent(calendarId, user.id, body));
    }

    @PutMapping("/events/{id}")
    public ResponseEntity<?> updateEvent(HttpServletRequest req,
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return ok(calendarService.updateEvent(id, user.id, body));
    }

    @DeleteMapping("/events/{id}")
    public ResponseEntity<?> deleteEvent(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        calendarService.deleteEvent(id, user.id);
        return ok(Map.of("ok", true));
    }
}
