package com.ollanest.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manages the personal and team-shared contacts system with CardDAV sync
 * support.
 *
 * <p>
 * Contacts can be personal (owned by a single user), team-shared (linked to a
 * team ID), or synced from an external CardDAV server such as Radicale or
 * Nextcloud. Multi-value fields (emails, phones, addresses) are stored as JSON
 * arrays.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The integrated productivity suite needs a contact store that is
 * private-by-default, supports team sharing, and can be exported as a standard
 * vCard 3.0 file for use in other apps without vendor lock-in.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Multi-value fields ({@code email}, {@code phone}, {@code address}) are
 * stored as JSON arrays in dedicated {@code *_json} columns and deserialised
 * transparently in {@code mapRow}.</li>
 * <li>Full-text search queries the JSON text column directly with {@code LIKE}
 * patterns, which is adequate for personal address books; a future FTS5 index
 * can replace this.</li>
 * <li>Team-shared contacts are visible to any user but only the owner may
 * delete them.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced with personal contacts, team sharing, vCard
 * export, and full-text search</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class ContactsService {

	/** JDBC template for contact persistence. */
	private final JdbcTemplate db;

	/** Shared Jackson mapper for multi-value field JSON serialisation. */
	private final ObjectMapper mapper;

	/**
	 * Constructor-injects persistence and serialisation dependencies.
	 *
	 * @param db     JDBC template for the {@code contacts} table
	 * @param mapper shared Jackson mapper
	 * @since v2026.2.1
	 */
	public ContactsService(JdbcTemplate db, ObjectMapper mapper) {
		this.db = db;
		this.mapper = mapper;
	}

	/**
	 * Creates a new contact for the given owner.
	 *
	 * @param owner the user ID
	 * @param req   contact fields: {@code display_name}, {@code first_name},
	 *              {@code last_name}, {@code email}, {@code phone},
	 *              {@code address}, {@code organization}, {@code title},
	 *              {@code notes}
	 * @return the created contact record; never null
	 * @since v2026.2.1
	 */
	public Map<String, Object> create(String owner, Map<String, Object> req) {
		String id = "cnt-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ UUID.randomUUID().toString().substring(0, 6);
		String now = Instant.now().toString();
		db.update(
				"""
						INSERT INTO contacts (id, owner, display_name, first_name, last_name,
						  email_json, phone_json, address_json, organization, title, notes, source, team_id, created_at, updated_at)
						VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
				id, owner, req.getOrDefault("display_name", ""), req.getOrDefault("first_name", ""),
				req.getOrDefault("last_name", ""), toJson(req.getOrDefault("email", List.of())),
				toJson(req.getOrDefault("phone", List.of())), toJson(req.getOrDefault("address", List.of())),
				req.get("organization"), req.get("title"), req.get("notes"), "local", req.get("team_id"), now, now);
		return getById(id, owner);
	}

	/**
	 * Updates contact fields for an existing contact.
	 *
	 * @param id    the contact ID
	 * @param owner the user ID (must match owner or shared via team)
	 * @param req   fields to update: {@code display_name}, {@code first_name},
	 *              {@code last_name}, {@code email}, {@code phone},
	 *              {@code organization}, {@code title}, {@code notes}
	 * @return the updated contact record
	 * @since v2026.2.1
	 */
	public Map<String, Object> update(String id, String owner, Map<String, Object> req) {
		String now = Instant.now().toString();
		db.update("""
				UPDATE contacts SET display_name=?, first_name=?, last_name=?,
				  email_json=?, phone_json=?, organization=?, title=?, notes=?, updated_at=?
				WHERE id=? AND (owner=? OR team_id IS NOT NULL)""", req.getOrDefault("display_name", ""),
				req.getOrDefault("first_name", ""), req.getOrDefault("last_name", ""),
				toJson(req.getOrDefault("email", List.of())), toJson(req.getOrDefault("phone", List.of())),
				req.get("organization"), req.get("title"), req.get("notes"), now, id, owner);
		return getById(id, owner);
	}

	/**
	 * Deletes a contact owned by the given user.
	 *
	 * @param id    the contact ID
	 * @param owner the user ID — only the owner may delete
	 * @since v2026.2.1
	 */
	public void delete(String id, String owner) {
		db.update("DELETE FROM contacts WHERE id=? AND owner=?", id, owner);
	}

	/**
	 * Returns the contact with the given ID, visible to the owner or any team
	 * member.
	 *
	 * @param id    the contact ID
	 * @param owner the requesting user ID
	 * @return the contact record, or {@code null} if not found
	 * @since v2026.2.1
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
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> list(String owner, int limit) {
		return db.queryForList(
				"SELECT * FROM contacts WHERE owner=? OR team_id IS NOT NULL ORDER BY display_name ASC LIMIT ?", owner,
				limit > 0 ? limit : 100).stream().map(this::mapRow).toList();
	}

	/**
	 * Full-text searches contacts by display name, email, or organization
	 * (case-insensitive). Returns up to 20 results.
	 *
	 * @param owner the user ID
	 * @param query search string
	 * @return matching contact records; never null
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> search(String owner, String query) {
		String q = "%" + query.toLowerCase() + "%";
		return db.queryForList("""
				SELECT * FROM contacts WHERE (owner=? OR team_id IS NOT NULL)
				AND (LOWER(display_name) LIKE ? OR LOWER(email_json) LIKE ? OR LOWER(organization) LIKE ?)
				ORDER BY display_name ASC LIMIT 20""", owner, q, q, q).stream().map(this::mapRow).toList();
	}

	/**
	 * Exports all contacts for the given owner as a vCard 3.0 string suitable for
	 * download.
	 *
	 * @param owner the user ID
	 * @return vCard 3.0 formatted string; never null
	 * @since v2026.2.1
	 */
	public String exportVCard(String owner) {
		List<Map<String, Object>> contacts = list(owner, 10000);
		StringBuilder sb = new StringBuilder();
		for (Map<String, Object> c : contacts) {
			sb.append("BEGIN:VCARD\r\nVERSION:3.0\r\n");
			sb.append("FN:").append(c.getOrDefault("display_name", "")).append("\r\n");
			sb.append("N:").append(c.getOrDefault("last_name", "")).append(";").append(c.getOrDefault("first_name", ""))
					.append(";;;\r\n");
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
			} catch (Exception ignore) {
			}
			sb.append("END:VCARD\r\n\r\n");
		}
		return sb.toString();
	}

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
		try {
			return mapper.writeValueAsString(obj);
		} catch (Exception e) {
			return "[]";
		}
	}
}
