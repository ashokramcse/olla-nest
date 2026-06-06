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
 * Preset / user template management.
 *
 * System presets are read-only defaults. Custom presets and per-user templates
 * are user-editable. Presets can include inject_prefix and inject_suffix to
 * wrap prompts automatically.
 *
 * Enterprise: admin can publish team-shared presets visible to all team members.
 */
@Service
public class PresetService {

    private static final Logger log = LoggerFactory.getLogger(PresetService.class);

    private static final List<Map<String, Object>> SYSTEM_PRESETS = List.of(
        preset("default",    "Default",          "",                                 1.0, 0),
        preset("precise",    "Precise",          "You are precise and concise.",     0.3, 0),
        preset("creative",   "Creative",         "You are creative and imaginative.", 1.2, 0),
        preset("coding",     "Coding Assistant", "You are an expert programmer. Always write clean, well-documented code.", 0.2, 4096),
        preset("research",   "Researcher",       "You are a thorough research assistant. Always cite sources and provide balanced analysis.", 0.5, 0),
        preset("writer",     "Writer",           "You are a skilled writer. Focus on clarity, flow, and engagement.", 0.8, 0),
        preset("analyst",    "Data Analyst",     "You are a data analyst. Be structured, use bullet points, and quantify when possible.", 0.3, 0)
    );

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

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
     */
    public Map<String, Object> createTemplate(String owner, Map<String, Object> req) {
        String id = "tpl-" + Long.toString(System.currentTimeMillis(), 36);
        String now = Instant.now().toString();
        db.update("""
                INSERT INTO user_templates (id, owner, name, system_prompt, temperature, max_tokens,
                  inject_prefix, inject_suffix, sort_order, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                id, owner,
                req.getOrDefault("name", "My Preset"),
                req.getOrDefault("system_prompt", ""),
                req.getOrDefault("temperature", 1.0),
                req.getOrDefault("max_tokens", 0),
                req.getOrDefault("inject_prefix", ""),
                req.getOrDefault("inject_suffix", ""),
                req.getOrDefault("sort_order", 0),
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
