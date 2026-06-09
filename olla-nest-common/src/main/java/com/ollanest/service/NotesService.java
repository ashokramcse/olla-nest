package com.ollanest.service;

import static com.ollanest.util.MapDefaults.orDefault;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Google Keep-style note management with support for checklists, colors,
 * labels, pins, and due-date reminders.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Users and the agent loop need a lightweight, structured way to capture and
 * retrieve notes without leaving the Olla Nest interface. This service provides
 * a familiar Keep-inspired data model — notes with optional checklist items,
 * color coding, labels, pins, and scheduled reminders — persisted in the
 * {@code notes} table. The agent can call {@code create}, {@code update}, and
 * {@code getById} via the tool dispatch system to maintain notes on behalf of
 * the user.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Checklist items are stored as a JSON array in the {@code items_json}
 * column and deserialized on every read so the caller always receives a typed
 * {@code List}.</li>
 * <li>All operations are owner-scoped; there is no cross-user access.</li>
 * <li>{@link #list} supports an optional {@code label} filter and an
 * {@code includeArchived} flag so the UI can render separate "Notes" and
 * "Archive" views without separate tables.</li>
 * <li>{@link #getDueReminders} is designed to be polled by the reminder
 * scheduler and is therefore not owner-scoped — it spans all users.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced as part of the personal productivity
 * expansion</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class NotesService {


	/** JDBC template for all note persistence operations. */
	private final JdbcTemplate db;

	/**
	 * Shared Jackson mapper for serializing and deserializing checklist item
	 * arrays.
	 */
	private final ObjectMapper mapper;

	/**
	 * Constructor-injects persistence and serialization dependencies.
	 *
	 * @param db     the JDBC template for note CRUD operations
	 * @param mapper the shared Jackson object mapper
	 * @since v2026.2.1
	 */
	public NotesService(JdbcTemplate db, ObjectMapper mapper) {
		this.db = db;
		this.mapper = mapper;
	}

	/**
	 * Creates a new note for the given owner. Supports checklists ({@code items}),
	 * colors, labels, pins, due dates, and repeat cadences.
	 *
	 * @param owner the user ID that owns this note
	 * @param req   note fields: {@code title}, {@code content}, {@code items},
	 *              {@code note_type}, {@code color}, {@code label}, {@code pinned},
	 *              {@code due_date}, {@code repeat}, {@code source},
	 *              {@code session_id}, {@code image_url}, {@code sort_order}
	 * @return the persisted note record
	 * @since v2026.2.1
	 */
	public Map<String, Object> create(String owner, Map<String, Object> req) {
		String id = "note-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ UUID.randomUUID().toString().substring(0, 6);
		String now = Instant.now().toString();

		db.update("""
				INSERT INTO notes (id, owner, title, content, items_json, note_type, color, label,
				  pinned, archived, due_date, repeat, source, session_id, image_url, sort_order, created_at, updated_at)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", id, owner,
				// NOT-NULL columns: coerce nulls to defaults. getOrDefault only
				// substitutes for ABSENT keys; an explicit JSON null would otherwise
				// reach the prepared statement and violate NOT NULL → 500 (BUG-019).
				orDefault(req.get("title"), ""), req.get("content"), toJson(req.get("items")),
				orDefault(req.get("note_type"), "note"), orDefault(req.get("color"), "default"), req.get("label"),
				Boolean.TRUE.equals(req.get("pinned")) ? 1 : 0, 0, // not archived on create
				req.get("due_date"), orDefault(req.get("repeat"), "none"), orDefault(req.get("source"), "user"),
				req.get("session_id"), req.get("image_url"), orDefault(req.get("sort_order"), 0), now, now);

		return getById(id, owner);
	}

	/**
	 * Partially updates a note. Only fields present in {@code req} are changed; all
	 * others retain their existing values.
	 *
	 * @param id    the note ID
	 * @param owner the user ID that owns this note
	 * @param req   fields to update
	 * @return the updated note record
	 * @throws NoSuchElementException if the note does not exist or belongs to a
	 *                                different owner
	 * @since v2026.2.1
	 */
	public Map<String, Object> update(String id, String owner, Map<String, Object> req) {
		Map<String, Object> existing = getById(id, owner);
		if (existing == null)
			throw new NoSuchElementException("Note not found: " + id);
		String now = Instant.now().toString();

		db.update("""
				UPDATE notes SET title=?, content=?, items_json=?, note_type=?, color=?, label=?,
				  pinned=?, archived=?, due_date=?, repeat=?, image_url=?, sort_order=?, updated_at=?
				WHERE id=? AND owner=?""",
				// NOT-NULL columns use orDefault (null → keep existing) so an explicit
				// JSON null can't violate the constraint (BUG-019); nullable columns
				// keep getOrDefault so they can be intentionally cleared.
				orDefault(req.get("title"), existing.get("title")),
				req.getOrDefault("content", existing.get("content")),
				toJson(req.getOrDefault("items", existing.get("items"))),
				orDefault(req.get("note_type"), existing.get("note_type")),
				orDefault(req.get("color"), existing.get("color")), req.getOrDefault("label", existing.get("label")),
				Boolean.TRUE.equals(req.get("pinned")) ? 1
						: (existing.get("pinned") != null ? existing.get("pinned") : 0),
				Boolean.TRUE.equals(req.get("archived")) ? 1
						: (existing.get("archived") != null ? existing.get("archived") : 0),
				req.getOrDefault("due_date", existing.get("due_date")),
				orDefault(req.get("repeat"), existing.get("repeat")),
				req.getOrDefault("image_url", existing.get("image_url")),
				orDefault(req.get("sort_order"), existing.get("sort_order")), now, id, owner);

		return getById(id, owner);
	}

	/**
	 * Permanently deletes a note.
	 *
	 * @param id    the note ID
	 * @param owner the user ID that must own this note
	 * @throws NoSuchElementException if the note does not exist or belongs to a
	 *                                different owner
	 * @since v2026.2.1
	 */
	public void delete(String id, String owner) {
		int rows = db.update("DELETE FROM notes WHERE id=? AND owner=?", id, owner);
		if (rows == 0)
			throw new NoSuchElementException("Note not found: " + id);
	}

	/**
	 * Fetches a single note by ID, scoped to the given owner.
	 *
	 * @param id    the note ID
	 * @param owner the user ID that must own this note
	 * @return the note record, or {@code null} if not found
	 * @since v2026.2.1
	 */
	public Map<String, Object> getById(String id, String owner) {
		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM notes WHERE id=? AND owner=?", id, owner);
		return rows.isEmpty() ? null : mapRow(rows.get(0));
	}

	/**
	 * Lists notes for an owner, ordered by pinned first, then {@code sort_order},
	 * then most recently updated.
	 *
	 * @param owner           the user ID
	 * @param includeArchived when {@code false}, archived notes are excluded
	 * @param label           optional label filter; pass {@code null} to include
	 *                        all labels
	 * @return matching notes; never null
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> list(String owner, boolean includeArchived, String label) {
		StringBuilder sql = new StringBuilder("SELECT * FROM notes WHERE owner=?");
		List<Object> args = new ArrayList<>();
		args.add(owner);

		if (!includeArchived) {
			sql.append(" AND archived=0");
		}
		if (label != null && !label.isBlank()) {
			sql.append(" AND label=?");
			args.add(label);
		}

		sql.append(" ORDER BY pinned DESC, sort_order ASC, updated_at DESC");
		return db.queryForList(sql.toString(), args.toArray()).stream().map(this::mapRow).toList();
	}

	/**
	 * Returns all non-archived notes whose {@code due_date} is at or before the
	 * current instant. Used by the reminder scheduler to fire notifications across
	 * all users.
	 *
	 * @return notes with past or present due dates; never null
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> getDueReminders() {
		String now = Instant.now().toString();
		return db.queryForList("SELECT * FROM notes WHERE due_date <= ? AND due_date IS NOT NULL AND archived=0", now)
				.stream().map(this::mapRow).toList();
	}

	private Map<String, Object> mapRow(Map<String, Object> row) {
		Map<String, Object> r = new LinkedHashMap<>(row);
		try {
			String itemsJson = (String) row.get("items_json");
			r.put("items", itemsJson != null ? mapper.readValue(itemsJson, List.class) : null);
			r.remove("items_json");
		} catch (Exception e) {
			r.put("items", null);
		}
		return r;
	}

	private String toJson(Object obj) {
		try {
			return obj != null ? mapper.writeValueAsString(obj) : null;
		} catch (Exception e) {
			return null;
		}
	}
}
