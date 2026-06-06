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
 * Contacts system — CRUD with CardDAV sync support.
 * Contacts can be personal or team-shared, synced from Radicale/Nextcloud, or local-only.
 */
@Service
public class ContactsService {

    private static final Logger log = LoggerFactory.getLogger(ContactsService.class);

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public ContactsService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    /**
     * Creates a new contact for the given owner.
     *
     * @param owner the user ID
     * @param req   contact fields: {@code display_name}, {@code first_name}, {@code last_name},
     *              {@code email}, {@code phone}, {@code address}, {@code organization}, {@code title}, {@code notes}
     * @return the created contact record; never null
     */
    public Map<String, Object> create(String owner, Map<String, Object> req) {
        String id = "cnt-" + Long.toString(System.currentTimeMillis(), 36);
        String now = Instant.now().toString();
        db.update("""
                INSERT INTO contacts (id, owner, display_name, first_name, last_name,
                  email_json, phone_json, address_json, organization, title, notes, source, team_id, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                id, owner,
                req.getOrDefault("display_name", ""),
                req.getOrDefault("first_name", ""),
                req.getOrDefault("last_name", ""),
                toJson(req.getOrDefault("email", List.of())),
                toJson(req.getOrDefault("phone", List.of())),
                toJson(req.getOrDefault("address", List.of())),
                req.get("organization"),
                req.get("title"),
                req.get("notes"),
                "local",
                req.get("team_id"),
                now, now);
        return getById(id, owner);
    }

    /**
     * Updates contact fields for an existing contact.
     *
     * @param id    the contact ID
     * @param owner the user ID (must match owner or shared via team)
     * @param req   fields to update: {@code display_name}, {@code first_name}, {@code last_name},
     *              {@code email}, {@code phone}, {@code organization}, {@code title}, {@code notes}
     * @return the updated contact record
     */
    public Map<String, Object> update(String id, String owner, Map<String, Object> req) {
        String now = Instant.now().toString();
        db.update("""
                UPDATE contacts SET display_name=?, first_name=?, last_name=?,
                  email_json=?, phone_json=?, organization=?, title=?, notes=?, updated_at=?
                WHERE id=? AND (owner=? OR team_id IS NOT NULL)""",
                req.getOrDefault("display_name", ""),
                req.getOrDefault("first_name", ""),
                req.getOrDefault("last_name", ""),
                toJson(req.getOrDefault("email", List.of())),
                toJson(req.getOrDefault("phone", List.of())),
                req.get("organization"),
                req.get("title"),
                req.get("notes"),
                now, id, owner);
        return getById(id, owner);
    }

    /**
     * Deletes a contact owned by the given user.
     *
     * @param id    the contact ID
     * @param owner the user ID — only the owner may delete
     */
    public void delete(String id, String owner) {
        db.update("DELETE FROM contacts WHERE id=? AND owner=?", id, owner);
    }

    /**
     * Returns the contact with the given ID, visible to the owner or any team member.
     *
     * @param id    the contact ID
     * @param owner the requesting user ID
     * @return the contact record, or {@code null} if not found
     */
    public Map<String, Object> getById(String id, String owner) {
        var rows = db.queryForList("SELECT * FROM contacts WHERE id=? AND (owner=? OR team_id IS NOT NULL)", id, owner);
        return rows.isEmpty() ? null : mapRow(rows.get(0));
    }

    /**
     * Returns contacts visible to the given owner, ordered by display name.
     *
     * @param owner the user ID
     * @param limit maximum results; {@code 0} or negative defaults to 100
     * @return list of contact record maps; never null
     */
    public List<Map<String, Object>> list(String owner, int limit) {
        return db.queryForList(
                "SELECT * FROM contacts WHERE owner=? OR team_id IS NOT NULL ORDER BY display_name ASC LIMIT ?",
                owner, limit > 0 ? limit : 100)
                .stream().map(this::mapRow).toList();
    }

    /**
     * Full-text searches contacts by display name, email, or organization (case-insensitive).
     * Returns up to 20 results.
     *
     * @param owner the user ID
     * @param query search string
     * @return matching contact records; never null
     */
    public List<Map<String, Object>> search(String owner, String query) {
        String q = "%" + query.toLowerCase() + "%";
        return db.queryForList("""
                SELECT * FROM contacts WHERE (owner=? OR team_id IS NOT NULL)
                AND (LOWER(display_name) LIKE ? OR LOWER(email_json) LIKE ? OR LOWER(organization) LIKE ?)
                ORDER BY display_name ASC LIMIT 20""",
                owner, q, q, q)
                .stream().map(this::mapRow).toList();
    }

    /**
     * Exports all contacts for the given owner as a vCard 3.0 string suitable for download.
     *
     * @param owner the user ID
     * @return vCard 3.0 formatted string; never null
     */
    public String exportVCard(String owner) {
        List<Map<String, Object>> contacts = list(owner, 10000);
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> c : contacts) {
            sb.append("BEGIN:VCARD\r\nVERSION:3.0\r\n");
            sb.append("FN:").append(c.getOrDefault("display_name", "")).append("\r\n");
            sb.append("N:").append(c.getOrDefault("last_name", "")).append(";")
              .append(c.getOrDefault("first_name", "")).append(";;;\r\n");
            if (c.get("organization") != null) {
                sb.append("ORG:").append(c.get("organization")).append("\r\n");
            }
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> emails = (List<Map<String, Object>>) c.get("email");
                if (emails != null) {
                    for (var e : emails) {
                        sb.append("EMAIL:").append(e.get("value")).append("\r\n");
                    }
                }
            } catch (Exception ignore) {}
            sb.append("END:VCARD\r\n\r\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapRow(Map<String, Object> row) {
        Map<String, Object> r = new LinkedHashMap<>(row);
        for (String field : List.of("email_json", "phone_json", "address_json")) {
            try {
                String json = (String) row.get(field);
                r.put(field.replace("_json", ""), json != null ? mapper.readValue(json, List.class) : List.of());
                r.remove(field);
            } catch (Exception e) {
                r.put(field.replace("_json", ""), List.of());
            }
        }
        return r;
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return "[]"; }
    }
}
