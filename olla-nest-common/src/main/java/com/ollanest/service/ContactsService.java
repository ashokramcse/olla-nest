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

    public void delete(String id, String owner) {
        db.update("DELETE FROM contacts WHERE id=? AND owner=?", id, owner);
    }

    public Map<String, Object> getById(String id, String owner) {
        var rows = db.queryForList("SELECT * FROM contacts WHERE id=? AND (owner=? OR team_id IS NOT NULL)", id, owner);
        return rows.isEmpty() ? null : mapRow(rows.get(0));
    }

    public List<Map<String, Object>> list(String owner, int limit) {
        return db.queryForList(
                "SELECT * FROM contacts WHERE owner=? OR team_id IS NOT NULL ORDER BY display_name ASC LIMIT ?",
                owner, limit > 0 ? limit : 100)
                .stream().map(this::mapRow).toList();
    }

    public List<Map<String, Object>> search(String owner, String query) {
        String q = "%" + query.toLowerCase() + "%";
        return db.queryForList("""
                SELECT * FROM contacts WHERE (owner=? OR team_id IS NOT NULL)
                AND (LOWER(display_name) LIKE ? OR LOWER(email_json) LIKE ? OR LOWER(organization) LIKE ?)
                ORDER BY display_name ASC LIMIT 20""",
                owner, q, q, q)
                .stream().map(this::mapRow).toList();
    }

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
