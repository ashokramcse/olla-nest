package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Google Keep-style notes with checklists, colors, labels, pins, and reminders.
 * Notes are owner-scoped. Agent can create/read/update notes via the tool system.
 */
@Service
public class NotesService {

    private static final Logger log = LoggerFactory.getLogger(NotesService.class);

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public NotesService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    /**
     * Create a new note for the given owner. Supports checklists ({@code items}),
     * colors, labels, pins, due dates, and repeat cadences.
     *
     * @param owner  the user ID that owns this note
     * @param req    note fields (title, content, items, note_type, color, label, pinned, due_date, repeat, etc.)
     * @return the persisted note record
     */
    public Map<String, Object> create(String owner, Map<String, Object> req) {
        String id = "note-" + Long.toString(System.currentTimeMillis(), 36);
        String now = Instant.now().toString();

        db.update("""
                INSERT INTO notes (id, owner, title, content, items_json, note_type, color, label,
                  pinned, archived, due_date, repeat, source, session_id, image_url, sort_order, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                id, owner,
                req.getOrDefault("title", ""),
                req.get("content"),
                toJson(req.get("items")),
                req.getOrDefault("note_type", "note"),
                req.getOrDefault("color", "default"),
                req.get("label"),
                Boolean.TRUE.equals(req.get("pinned")) ? 1 : 0,
                0, // not archived on create
                req.get("due_date"),
                req.getOrDefault("repeat", "none"),
                req.getOrDefault("source", "user"),
                req.get("session_id"),
                req.get("image_url"),
                req.getOrDefault("sort_order", 0),
                now, now);

        return getById(id, owner);
    }

    /**
     * Partially update a note. Only fields present in {@code req} are changed;
     * all others retain their existing values. Throws {@link NoSuchElementException} if
     * the note does not exist or belongs to a different owner.
     *
     * @param id     the note ID
     * @param owner  the user ID that owns this note
     * @param req    fields to update
     * @return the updated note record
     */
    public Map<String, Object> update(String id, String owner, Map<String, Object> req) {
        Map<String, Object> existing = getById(id, owner);
        if (existing == null) throw new NoSuchElementException("Note not found: " + id);
        String now = Instant.now().toString();

        db.update("""
                UPDATE notes SET title=?, content=?, items_json=?, note_type=?, color=?, label=?,
                  pinned=?, archived=?, due_date=?, repeat=?, image_url=?, sort_order=?, updated_at=?
                WHERE id=? AND owner=?""",
                req.getOrDefault("title", existing.get("title")),
                req.getOrDefault("content", existing.get("content")),
                toJson(req.getOrDefault("items", existing.get("items"))),
                req.getOrDefault("note_type", existing.get("note_type")),
                req.getOrDefault("color", existing.get("color")),
                req.getOrDefault("label", existing.get("label")),
                Boolean.TRUE.equals(req.get("pinned")) ? 1 : (existing.get("pinned") != null ? existing.get("pinned") : 0),
                Boolean.TRUE.equals(req.get("archived")) ? 1 : (existing.get("archived") != null ? existing.get("archived") : 0),
                req.getOrDefault("due_date", existing.get("due_date")),
                req.getOrDefault("repeat", existing.get("repeat")),
                req.getOrDefault("image_url", existing.get("image_url")),
                req.getOrDefault("sort_order", existing.get("sort_order")),
                now, id, owner);

        return getById(id, owner);
    }

    /**
     * Permanently delete a note. Throws {@link NoSuchElementException} if the note
     * does not exist or belongs to a different owner.
     *
     * @param id    the note ID
     * @param owner the user ID that must own this note
     */
    public void delete(String id, String owner) {
        int rows = db.update("DELETE FROM notes WHERE id=? AND owner=?", id, owner);
        if (rows == 0) throw new NoSuchElementException("Note not found: " + id);
    }

    /**
     * Fetch a single note by ID, scoped to the given owner.
     *
     * @param id    the note ID
     * @param owner the user ID that must own this note
     * @return the note record, or {@code null} if not found
     */
    public Map<String, Object> getById(String id, String owner) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM notes WHERE id=? AND owner=?", id, owner);
        return rows.isEmpty() ? null : mapRow(rows.get(0));
    }

    /**
     * List notes for an owner, ordered by pinned first, then sort_order, then most recently updated.
     *
     * @param owner           the user ID
     * @param includeArchived when {@code false}, archived notes are excluded
     * @param label           optional label filter; pass {@code null} to include all labels
     * @return matching notes
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
     * Return all non-archived notes whose {@code due_date} is at or before the current instant.
     * Used by the reminder scheduler to fire notifications across all users.
     *
     * @return notes with past or present due dates
     */
    public List<Map<String, Object>> getDueReminders() {
        String now = Instant.now().toString();
        return db.queryForList(
                "SELECT * FROM notes WHERE due_date <= ? AND due_date IS NOT NULL AND archived=0",
                now).stream().map(this::mapRow).toList();
    }

    @SuppressWarnings("unchecked")
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
        try { return obj != null ? mapper.writeValueAsString(obj) : null; }
        catch (Exception e) { return null; }
    }
}
