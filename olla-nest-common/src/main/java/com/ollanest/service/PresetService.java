package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import static com.ollanest.util.MapDefaults.orDefault;

import java.time.Instant;
import java.util.UUID;
import java.util.*;

/**
 * Manages system-defined presets and user-editable prompt templates.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Users need a quick way to switch the LLM into a specific mode — precise,
 * creative, coding, research — without retyping a system prompt each time. This
 * service provides a two-tier preset model: a hardcoded set of built-in system
 * presets (always available, read-only) and a per-user {@code user_templates}
 * table for custom presets. Both tiers are returned together by {@link #listAll}
 * so the frontend can render a unified preset picker.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>System presets are defined as a static {@link List} constant at class
 * initialisation and never hit the database, keeping the common read path
 * allocation-free for the built-in presets.</li>
 * <li>{@code inject_prefix} and {@code inject_suffix} fields allow wrapping
 * every user message automatically without the user needing to remember to add
 * boilerplate text.</li>
 * <li>All CRUD operations on user templates are owner-scoped so users cannot
 * modify each other's templates.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced as part of the personal productivity expansion</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class PresetService {

    private static final Logger log = LoggerFactory.getLogger(PresetService.class);

    /** Built-in read-only presets available to all users regardless of database state. */
    private static final List<Map<String, Object>> SYSTEM_PRESETS = List.of(
        preset("default",    "Default",          "",                                 1.0, 0),
        preset("precise",    "Precise",          "You are precise and concise.",     0.3, 0),
        preset("creative",   "Creative",         "You are creative and imaginative.", 1.2, 0),
        preset("coding",     "Coding Assistant", "You are an expert programmer. Always write clean, well-documented code.", 0.2, 4096),
        preset("research",   "Researcher",       "You are a thorough research assistant. Always cite sources and provide balanced analysis.", 0.5, 0),
        preset("writer",     "Writer",           "You are a skilled writer. Focus on clarity, flow, and engagement.", 0.8, 0),
        preset("analyst",    "Data Analyst",     "You are a data analyst. Be structured, use bullet points, and quantify when possible.", 0.3, 0)
    );

    /** JDBC template for user template CRUD. */
    private final JdbcTemplate db;

    /** Shared Jackson mapper (reserved for future JSON field support). */
    private final ObjectMapper mapper;

    /**
     * Constructor-injects persistence and serialization dependencies.
     *
     * @param db     the JDBC template for user template operations
     * @param mapper the shared Jackson object mapper
     * @since v2026.2.1
     */
    public PresetService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    /**
     * Returns all presets visible to the given owner: built-in system presets
     * followed by the owner's custom user templates, ordered by sort order.
     *
     * @param owner the user ID
     * @return combined list of preset/template maps; never null
     * @since v2026.2.1
     */
    public List<Map<String, Object>> listAll(String owner) {
        List<Map<String, Object>> all = new ArrayList<>(SYSTEM_PRESETS);
        // Add user templates
        List<Map<String, Object>> userTemplates = db.queryForList(
                "SELECT * FROM user_templates WHERE owner=? ORDER BY sort_order ASC, created_at DESC",
                owner);
        for (var t : userTemplates) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", t.get("id"));
            p.put("name", t.get("name"));
            p.put("system_prompt", t.get("system_prompt"));
            p.put("temperature", t.get("temperature"));
            p.put("max_tokens", t.get("max_tokens"));
            p.put("inject_prefix", t.getOrDefault("inject_prefix", ""));
            p.put("inject_suffix", t.getOrDefault("inject_suffix", ""));
            p.put("source", "user");
            all.add(p);
        }
        return all;
    }

    /**
     * Creates a new user-defined template/preset.
     *
     * @param owner the user ID
     * @param req   template fields: {@code name}, {@code system_prompt}, {@code temperature},
     *              {@code max_tokens}, {@code inject_prefix}, {@code inject_suffix}, {@code sort_order}
     * @return the created template record
     * @since v2026.2.1
     */
    public Map<String, Object> createTemplate(String owner, Map<String, Object> req) {
        String id = "tpl-" + Long.toString(System.currentTimeMillis(), 36) + "-" + UUID.randomUUID().toString().substring(0, 6);
        String now = Instant.now().toString();
        db.update("""
                INSERT INTO user_templates (id, owner, name, system_prompt, temperature, max_tokens,
                  inject_prefix, inject_suffix, sort_order, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                id, owner,
                // BUG-019: coerce explicit JSON nulls for NOT-NULL columns.
                orDefault(req.get("name"), "My Preset"),
                orDefault(req.get("system_prompt"), ""),
                orDefault(req.get("temperature"), 1.0),
                orDefault(req.get("max_tokens"), 0),
                orDefault(req.get("inject_prefix"), ""),
                orDefault(req.get("inject_suffix"), ""),
                orDefault(req.get("sort_order"), 0),
                now, now);
        return getTemplate(id, owner);
    }

    /**
     * Updates an existing user template.
     *
     * @param id    the template ID
     * @param owner the user ID — only the owner may update
     * @param req   updated fields
     * @return the updated template record
     * @since v2026.2.1
     */
    public Map<String, Object> updateTemplate(String id, String owner, Map<String, Object> req) {
        db.update("""
                UPDATE user_templates SET name=?, system_prompt=?, temperature=?, max_tokens=?,
                  inject_prefix=?, inject_suffix=?, updated_at=?
                WHERE id=? AND owner=?""",
                req.getOrDefault("name", "My Preset"),
                req.getOrDefault("system_prompt", ""),
                req.getOrDefault("temperature", 1.0),
                req.getOrDefault("max_tokens", 0),
                req.getOrDefault("inject_prefix", ""),
                req.getOrDefault("inject_suffix", ""),
                Instant.now().toString(), id, owner);
        return getTemplate(id, owner);
    }

    /**
     * Deletes a user-defined template.
     *
     * @param id    the template ID
     * @param owner the user ID — only the owner may delete
     * @since v2026.2.1
     */
    public void deleteTemplate(String id, String owner) {
        db.update("DELETE FROM user_templates WHERE id=? AND owner=?", id, owner);
    }

    /**
     * Returns a user template by ID, restricted to the given owner.
     *
     * @param id    the template ID
     * @param owner the user ID
     * @return the template record, or {@code null} if not found
     * @since v2026.2.1
     */
    public Map<String, Object> getTemplate(String id, String owner) {
        var rows = db.queryForList("SELECT * FROM user_templates WHERE id=? AND owner=?", id, owner);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> preset(String id, String name, String systemPrompt,
            double temperature, int maxTokens) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("name", name);
        p.put("system_prompt", systemPrompt);
        p.put("temperature", temperature);
        p.put("max_tokens", maxTokens);
        p.put("inject_prefix", "");
        p.put("inject_suffix", "");
        p.put("source", "system");
        return p;
    }
}
