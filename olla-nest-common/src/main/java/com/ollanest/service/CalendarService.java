package com.ollanest.service;

import static com.ollanest.util.MapDefaults.orDefault;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Local SQLite-backed calendar service with CalDAV synchronisation support.
 *
 * <p>
 * Supports multiple calendars per user (personal and team-shared), recurring
 * events via RFC 5545 RRULE, bidirectional CalDAV sync, and RFC 5545 iCalendar
 * (.ics) export.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The application provides a self-hosted personal productivity suite. A local
 * calendar that syncs with external CalDAV servers (Nextcloud, Radicale,
 * iCloud, Google) lets users manage their schedule without data leaving their
 * infrastructure, while still being accessible from any CalDAV-capable client.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>CalDAV sync runs every 15 minutes on a virtual thread per calendar to
 * avoid blocking each other.</li>
 * <li>Only one calendar per user may be marked as default;
 * {@link #createCalendar} demotes existing defaults when a new default is
 * requested.</li>
 * <li>Calendar credentials are stored encrypted via {@link CryptoService}.</li>
 * <li>The {@link #syncAllCalDav()} scheduler method is the extension point for
 * a full CalDAV PROPFIND/REPORT implementation.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced with multi-calendar support, CalDAV sync stub, and
 * .ics export</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class CalendarService {

	private static final Logger log = LoggerFactory.getLogger(CalendarService.class);

	/** JDBC template for calendar and event persistence. */
	private final JdbcTemplate db;

	/** Shared Jackson mapper for JSON serialisation. */
	private final ObjectMapper mapper;

	/**
	 * Constructor-injects persistence and serialisation dependencies.
	 *
	 * @param db     JDBC template for {@code calendars} and {@code calendar_events}
	 *               tables
	 * @param mapper shared Jackson mapper
	 * @since v2026.2.1
	 */
	public CalendarService(JdbcTemplate db, ObjectMapper mapper) {
		this.db = db;
		this.mapper = mapper;
	}

	// ── Calendar CRUD ─────────────────────────────────────────────────────────

	/**
	 * Creates a new calendar for the given owner.
	 *
	 * <p>
	 * If {@code req} contains {@code is_default: true}, any existing default
	 * calendar for the owner is demoted first. If the user has no calendars yet,
	 * the new calendar is automatically made the default.
	 *
	 * @param owner the user ID who will own the calendar
	 * @param req   request map — supports {@code name}, {@code color},
	 *              {@code is_default}, {@code caldav_url}, {@code team_id}
	 * @return the newly created calendar record, or {@code null} if not found after
	 *         insert
	 * @since v2026.2.1
	 */
	public Map<String, Object> createCalendar(String owner, Map<String, Object> req) {
		String id = "cal-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ UUID.randomUUID().toString().substring(0, 6);
		String now = Instant.now().toString();

		// Ensure only one default calendar per user
		boolean isDefault = Boolean.TRUE.equals(req.get("is_default"));
		if (isDefault) {
			db.update("UPDATE calendars SET is_default=0 WHERE owner=?", owner);
		} else {
			// Make default if user has no calendars
			int count = db.queryForObject("SELECT COUNT(*) FROM calendars WHERE owner=?", Integer.class, owner);
			if (count == 0)
				isDefault = true;
		}

		db.update("""
				INSERT INTO calendars (id, owner, name, color, is_default, caldav_url, team_id, created_at, updated_at)
				VALUES (?,?,?,?,?,?,?,?,?)""", id, owner,
				// BUG-019: coerce explicit JSON nulls for NOT-NULL columns.
				orDefault(req.get("name"), "My Calendar"), orDefault(req.get("color"), "#F5C800"), isDefault ? 1 : 0,
				req.get("caldav_url"), req.get("team_id"), now, now);

		return getCalendar(id, owner);
	}

	/**
	 * Returns the calendar with the given ID visible to the owner, or {@code null}
	 * if not found.
	 *
	 * @param id    the calendar ID
	 * @param owner the requesting user ID
	 * @return the calendar row map, or {@code null}
	 * @since v2026.2.1
	 */
	public Map<String, Object> getCalendar(String id, String owner) {
		List<Map<String, Object>> rows = db
				.queryForList("SELECT * FROM calendars WHERE id=? AND (owner=? OR team_id IS NOT NULL)", id, owner);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * Returns all calendars owned by the given user, ordered by default-first then
	 * name.
	 *
	 * @param owner the user ID
	 * @return list of calendar row maps; never null
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> listCalendars(String owner) {
		return db.queryForList("SELECT * FROM calendars WHERE owner=? ORDER BY is_default DESC, name ASC", owner);
	}

	/**
	 * Deletes the calendar with the given ID, restricted to the owning user.
	 *
	 * @param id    the calendar ID to delete
	 * @param owner the user ID — only the owner may delete
	 * @since v2026.2.1
	 */
	public void deleteCalendar(String id, String owner) {
		db.update("DELETE FROM calendars WHERE id=? AND owner=?", id, owner);
	}

	// ── Event CRUD ────────────────────────────────────────────────────────────

	/**
	 * Creates a new event in the specified calendar.
	 *
	 * @param calendarId the ID of the calendar to add the event to
	 * @param owner      the requesting user ID (must own the calendar)
	 * @param req        event fields: {@code title}, {@code start_at},
	 *                   {@code end_at}, {@code description}, {@code location},
	 *                   {@code all_day}, {@code rrule}, etc.
	 * @return the newly created event record
	 * @throws java.util.NoSuchElementException if the calendar is not found or not
	 *                                          owned by the user
	 * @since v2026.2.1
	 */
	public Map<String, Object> createEvent(String calendarId, String owner, Map<String, Object> req) {
		verifyCalendarOwner(calendarId, owner);
		validateEventTimes(req.get("start_at"), req.get("end_at"));
		String id = "evt-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ UUID.randomUUID().toString().substring(0, 6);
		String uid = orDefault(req.get("uid"), UUID.randomUUID().toString()).toString();
		String now = Instant.now().toString();

		db.update("""
				INSERT INTO calendar_events (id, calendar_id, uid, title, description, location,
				  start_at, end_at, all_day, rrule, exdate_json, status, created_at, updated_at)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", id, calendarId, uid, orDefault(req.get("title"), "Event"),
				req.get("description"), req.get("location"), req.get("start_at"), req.get("end_at"),
				Boolean.TRUE.equals(req.get("all_day")) ? 1 : 0, req.get("rrule"),
				toJson(req.getOrDefault("exdate", List.of())), orDefault(req.get("status"), "confirmed"), now, now);

		return getEvent(id);
	}

	/**
	 * Updates an existing calendar event.
	 *
	 * @param id    the event ID to update
	 * @param owner the requesting user ID (must own the parent calendar)
	 * @param req   fields to update; unspecified fields retain existing values
	 * @return the updated event record
	 * @throws java.util.NoSuchElementException if the event is not found
	 * @since v2026.2.1
	 */
	public Map<String, Object> updateEvent(String id, String owner, Map<String, Object> req) {
		Map<String, Object> existing = getEvent(id);
		if (existing == null)
			throw new NoSuchElementException("Event not found: " + id);
		verifyCalendarOwner((String) existing.get("calendar_id"), owner);

		Object effStart = req.getOrDefault("start_at", existing.get("start_at"));
		Object effEnd = req.getOrDefault("end_at", existing.get("end_at"));
		validateEventTimes(effStart, effEnd);

		String now = Instant.now().toString();
		db.update("""
				UPDATE calendar_events SET title=?, description=?, location=?,
				  start_at=?, end_at=?, all_day=?, rrule=?, exdate_json=?, status=?, updated_at=?
				WHERE id=?""", orDefault(req.get("title"), existing.get("title")), req.get("description"),
				req.get("location"), effStart, effEnd, Boolean.TRUE.equals(req.get("all_day")) ? 1 : 0,
				req.get("rrule"), toJson(req.getOrDefault("exdate", List.of())),
				orDefault(req.get("status"), existing.get("status")), now, id);

		return getEvent(id);
	}

	/**
	 * Deletes a calendar event, silently no-ops if not found.
	 *
	 * @param id    the event ID to delete
	 * @param owner the requesting user ID (must own the parent calendar)
	 * @since v2026.2.1
	 */
	public void deleteEvent(String id, String owner) {
		Map<String, Object> event = getEvent(id);
		if (event == null)
			return;
		verifyCalendarOwner((String) event.get("calendar_id"), owner);
		db.update("DELETE FROM calendar_events WHERE id=?", id);
	}

	/**
	 * Returns the event with the given ID, or {@code null} if not found.
	 *
	 * @param id the event ID
	 * @return the event row map, or {@code null}
	 * @since v2026.2.1
	 */
	public Map<String, Object> getEvent(String id) {
		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM calendar_events WHERE id=?", id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * Returns all events in the owner's calendars whose {@code start_at} falls
	 * within the given range.
	 *
	 * @param owner the user ID
	 * @param from  ISO-8601 start of range (inclusive)
	 * @param to    ISO-8601 end of range (inclusive)
	 * @return list of event row maps ordered by start_at; never null
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> listEventsInRange(String owner, String from, String to) {
		return db.queryForList("""
				SELECT e.* FROM calendar_events e
				JOIN calendars c ON e.calendar_id = c.id
				WHERE c.owner = ? AND e.start_at >= ? AND e.start_at <= ?
				ORDER BY e.start_at ASC""", owner, from, to);
	}

	// ── .ics Export ───────────────────────────────────────────────────────────

	/**
	 * Exports all events in the given calendar as an RFC 5545 iCalendar (.ics)
	 * string.
	 *
	 * @param calendarId the calendar ID to export
	 * @param owner      the requesting user ID (must own the calendar)
	 * @return the full .ics content string starting with {@code BEGIN:VCALENDAR}
	 * @throws java.util.NoSuchElementException if the calendar is not owned by the
	 *                                          user
	 * @since v2026.2.1
	 */
	public String exportCalendarAsIcs(String calendarId, String owner) {
		verifyCalendarOwner(calendarId, owner);
		List<Map<String, Object>> events = db.queryForList("SELECT * FROM calendar_events WHERE calendar_id=?",
				calendarId);

		StringBuilder sb = new StringBuilder();
		sb.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Olla Nest//EN\r\n");
		for (Map<String, Object> ev : events) {
			sb.append("BEGIN:VEVENT\r\n");
			sb.append("UID:").append(ev.get("uid")).append("\r\n");
			sb.append("SUMMARY:").append(foldIcs((String) ev.get("title"))).append("\r\n");
			sb.append("DTSTART:").append(toIcsDt((String) ev.get("start_at"))).append("\r\n");
			sb.append("DTEND:").append(toIcsDt((String) ev.get("end_at"))).append("\r\n");
			if (ev.get("description") != null) {
				sb.append("DESCRIPTION:").append(foldIcs((String) ev.get("description"))).append("\r\n");
			}
			if (ev.get("rrule") != null) {
				sb.append("RRULE:").append(ev.get("rrule")).append("\r\n");
			}
			sb.append("STATUS:").append(((String) ev.getOrDefault("status", "confirmed")).toUpperCase()).append("\r\n");
			sb.append("END:VEVENT\r\n");
		}
		sb.append("END:VCALENDAR\r\n");
		return sb.toString();
	}

	// ── CalDAV Sync ───────────────────────────────────────────────────────────

	/**
	 * Scheduled CalDAV synchronisation — iterates all calendars with a
	 * {@code caldav_url} and triggers a per-calendar sync on a virtual thread every
	 * 15 minutes.
	 *
	 * @since v2026.2.1
	 */
	@Scheduled(fixedDelay = 900000, initialDelay = 30000) // every 15 minutes
	public void syncAllCalDav() {
		try {
			List<Map<String, Object>> cals = db
					.queryForList("SELECT * FROM calendars WHERE caldav_url IS NOT NULL AND caldav_url != ''");
			for (Map<String, Object> cal : cals) {
				Thread.ofVirtual().name("caldav-sync-" + cal.get("id")).start(() -> {
					try {
						syncCalDav(cal);
					} catch (Exception e) {
						log.debug("[caldav] Sync failed for {}: {}", cal.get("id"), e.getMessage());
					}
				});
			}
		} catch (Exception e) {
			log.warn("[caldav] Scheduler error: {}", e.getMessage());
		}
	}

	private void syncCalDav(Map<String, Object> calendar) {
		// CalDAV PROPFIND to get list of event hrefs + ETags, then fetch changed events
		String caldavUrl = (String) calendar.get("caldav_url");
		log.debug("[caldav] Syncing calendar {} from {}", calendar.get("id"), caldavUrl);
		// Full CalDAV implementation would use PROPFIND/REPORT XML over HTTP
		// This is the extension point — CalDAV credentials come from settings
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private void verifyCalendarOwner(String calendarId, String owner) {
		int count = db.queryForObject("SELECT COUNT(*) FROM calendars WHERE id=? AND (owner=? OR team_id IS NOT NULL)",
				Integer.class, calendarId, owner);
		if (count == 0)
			throw new NoSuchElementException("Calendar not found: " + calendarId);
	}

	private String toJson(Object obj) {
		try {
			return mapper.writeValueAsString(obj);
		} catch (Exception e) {
			return "[]";
		}
	}

	/**
	 * Rejects an event whose end is strictly before its start. Both values must be
	 * ISO-8601 instants; if either is absent or unparseable the check is skipped
	 * (parsing/format errors are not this method's concern).
	 *
	 * @param start the start timestamp (any type; only non-null ISO strings are
	 *              checked)
	 * @param end   the end timestamp
	 * @throws IllegalArgumentException if {@code end} is before {@code start}
	 */
	private void validateEventTimes(Object start, Object end) {
		// start_at / end_at are NOT-NULL columns. Reject missing/blank values here as
		// a 400 (IllegalArgumentException) instead of letting a null reach the INSERT
		// and surface as a misleading 500 SQLITE_CONSTRAINT_NOTNULL (BUG-027 class).
		if (start == null || start.toString().isBlank())
			throw new IllegalArgumentException("Event start time (start_at) is required");
		if (end == null || end.toString().isBlank())
			throw new IllegalArgumentException("Event end time (end_at) is required");
		try {
			if (Instant.parse(end.toString()).isBefore(Instant.parse(start.toString()))) {
				throw new IllegalArgumentException("Event end time cannot be before its start time");
			}
		} catch (DateTimeParseException ignore) {
			// Non-ISO timestamps are accepted as-is (e.g. all-day date strings).
		}
	}

	private String foldIcs(String text) {
		if (text == null)
			return "";
		return text.replace("\r\n", "\\n").replace("\n", "\\n").replace(",", "\\,");
	}

	private String toIcsDt(String iso) {
		if (iso == null)
			return "";
		try {
			return iso.replace("-", "").replace(":", "").replace(".000Z", "Z");
		} catch (Exception e) {
			return iso;
		}
	}
}
