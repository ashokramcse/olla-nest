package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * API token management — mint / validate / revoke {@code oly_} prefixed tokens.
 *
 * Tokens are stored bcrypt-hashed in the DB. The full token is shown once on
 * creation and never again. Each token has:
 *   - owner: the user it belongs to
 *   - scopes: ["chat"] | ["admin"] | ["chat","admin"]
 *   - last_used_at: updated on each validation
 *
 * Bearer token auth is handled in SessionAuthFilter.
 */
@Service
public class ApiTokenService {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenService.class);
    private static final String TOKEN_PREFIX = "oly_";
    private static final int TOKEN_BYTES = 32;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public ApiTokenService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    // ── Mint ──────────────────────────────────────────────────────────────────

    /**
     * Mint a new API token. Returns a map containing the full token (shown once)
     * and the token record.
     */
    public Map<String, Object> mint(String owner, String name, List<String> scopes) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String rawToken = TOKEN_PREFIX + HexFormat.of().formatHex(bytes);
        String hash = ENCODER.encode(rawToken);
        String prefix = rawToken.substring(0, Math.min(12, rawToken.length()));

        String id = "tok-" + Long.toString(System.currentTimeMillis(), 36);
        String now = Instant.now().toString();
        List<String> effectiveScopes = (scopes != null && !scopes.isEmpty()) ? scopes : List.of("chat");

        db.update("""
                INSERT INTO api_tokens (id, owner, name, token_hash, token_prefix, scopes_json, is_active, created_at)
                VALUES (?,?,?,?,?,?,?,?)""",
                id, owner, name != null ? name : "API Token", hash, prefix,
                toJson(effectiveScopes), 1, now);

        return Map.of(
                "id", id,
                "owner", owner,
                "name", name != null ? name : "API Token",
                "token", rawToken,  // full token — shown ONCE
                "token_prefix", prefix,
                "scopes", effectiveScopes,
                "created_at", now
        );
    }

    // ── Validate ──────────────────────────────────────────────────────────────

    /**
     * Validate a raw token. Returns the token record (with owner and scopes) or null.
     * Updates last_used_at on success.
     */
    public Map<String, Object> validate(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) return null;

        // Look up by prefix to narrow candidates (prefix is first 12 chars, stored plaintext)
        String prefix = rawToken.substring(0, Math.min(12, rawToken.length()));
        List<Map<String, Object>> candidates = db.queryForList(
                "SELECT * FROM api_tokens WHERE token_prefix=? AND is_active=1", prefix);

        for (Map<String, Object> candidate : candidates) {
            String hash = (String) candidate.get("token_hash");
            if (ENCODER.matches(rawToken, hash)) {
                // Update last_used_at asynchronously
                String id = (String) candidate.get("id");
                Thread.ofVirtual().start(() ->
                        db.update("UPDATE api_tokens SET last_used_at=? WHERE id=?",
                                Instant.now().toString(), id));
                return mapRow(candidate);
            }
        }
        return null;
    }

    // ── List / Revoke ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> list(String owner) {
        return db.queryForList(
                "SELECT * FROM api_tokens WHERE owner=? ORDER BY created_at DESC", owner)
                .stream().map(this::mapRow).toList();
    }

    public void revoke(String id, String owner) {
        db.update("UPDATE api_tokens SET is_active=0 WHERE id=? AND owner=?", id, owner);
    }

    public void revokeAll(String owner) {
        db.update("UPDATE api_tokens SET is_active=0 WHERE owner=?", owner);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapRow(Map<String, Object> row) {
        Map<String, Object> r = new LinkedHashMap<>(row);
        r.remove("token_hash"); // never expose the hash
        try {
            String scopesJson = (String) row.get("scopes_json");
            r.put("scopes", scopesJson != null ? mapper.readValue(scopesJson, List.class) : List.of("chat"));
            r.remove("scopes_json");
        } catch (Exception e) {
            r.put("scopes", List.of("chat"));
        }
        return r;
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return "[]"; }
    }
}
