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
 * Manages the lifecycle of {@code oly_}-prefixed API tokens: minting, validation,
 * listing, and revocation.
 *
 * <p>Tokens are stored bcrypt-hashed in the database. The full plaintext token is
 * returned exactly once at mint time and is never recoverable afterwards. Each token
 * carries owner, scopes, and a {@code last_used_at} timestamp updated on every
 * successful validation.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Session cookies are browser-only; programmatic API clients (scripts, CI pipelines,
 * integrations) need a long-lived credential. Bearer-token authentication is handled
 * in {@code SessionAuthFilter} which calls {@link #validate} to resolve the incoming
 * token to an owner and scope set.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Tokens are 32 random bytes hex-encoded with an {@code oly_} prefix — easy to
 *     identify and grep in logs/configs.</li>
 * <li>The first 12 characters are stored as a plaintext prefix so the DB can narrow
 *     BCrypt comparison candidates with a simple equality filter instead of scanning
 *     every active token.</li>
 * <li>Revocation is always soft (sets {@code is_active=0}) to preserve audit history.</li>
 * <li>The {@code last_used_at} update is fire-and-forget on a virtual thread to avoid
 *     adding write latency to the hot validation path.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced as part of the programmatic API access feature</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class ApiTokenService {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenService.class);
    private static final String TOKEN_PREFIX = "oly_";
    private static final int TOKEN_BYTES = 32;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** JDBC template for token persistence. */
    private final JdbcTemplate db;

    /** Shared Jackson mapper for scopes JSON serialisation. */
    private final ObjectMapper mapper;

    /**
     * Constructor-injects persistence and serialisation dependencies.
     *
     * @param db     JDBC template for the {@code api_tokens} table
     * @param mapper shared Jackson mapper
     * @since v2026.2.1
     */
    public ApiTokenService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    // ── Mint ──────────────────────────────────────────────────────────────────

    /**
     * Mints a new API token for the given owner.
     *
     * <p>The returned map contains the full plaintext {@code token} field — this
     * is the only time it is exposed. Subsequent calls will not return the raw token.
     *
     * @param owner  the user ID who will own this token
     * @param name   human-readable label for the token; defaults to {@code "API Token"} if null
     * @param scopes list of granted scopes ({@code "chat"}, {@code "admin"}); defaults to
     *               {@code ["chat"]} if null or empty
     * @return map containing {@code id}, {@code token} (full, shown once), {@code token_prefix},
     *         {@code scopes}, {@code owner}, {@code name}, and {@code created_at}
     * @since v2026.2.1
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
     * Validates a raw bearer token against the database.
     *
     * <p>Updates {@code last_used_at} asynchronously on success to avoid adding
     * write latency to the authentication hot-path.
     *
     * @param rawToken the full plaintext token (including the {@code oly_} prefix)
     * @return the token record (with {@code owner} and {@code scopes}) if valid and active;
     *         {@code null} if the token is invalid, inactive, or does not exist
     * @since v2026.2.1
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

    /**
     * Lists all API tokens for an owner, ordered newest first.
     * Token hashes are never included in the returned records.
     *
     * @param owner the user ID
     * @return token records ({@code id}, {@code name}, {@code token_prefix}, {@code scopes},
     *         {@code is_active}, {@code created_at}, {@code last_used_at}); never null
     * @since v2026.2.1
     */
    public List<Map<String, Object>> list(String owner) {
        return db.queryForList(
                "SELECT * FROM api_tokens WHERE owner=? ORDER BY created_at DESC", owner)
                .stream().map(this::mapRow).toList();
    }

    /**
     * Soft-revokes a single token by setting {@code is_active=0}. The token record is
     * retained for audit purposes. No-op if the token does not exist or is already inactive.
     *
     * @param id    the token ID
     * @param owner the user ID that must own this token
     * @since v2026.2.1
     */
    public void revoke(String id, String owner) {
        db.update("UPDATE api_tokens SET is_active=0 WHERE id=? AND owner=?", id, owner);
    }

    /**
     * Revokes all active tokens for an owner in one statement. Used on account
     * compromise or when an admin resets a user's access.
     *
     * @param owner the user ID whose tokens should all be deactivated
     * @since v2026.2.1
     */
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
