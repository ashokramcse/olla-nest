package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Outgoing webhook service — fires signed HTTP POSTs on events.
 *
 * Security:
 * - HMAC-SHA256 signature in X-Olla-Signature header
 * - SSRF protection: private/internal network targets are rejected
 * - Retry with exponential backoff (max 3 attempts)
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private static final Set<String> ALLOWED_EVENTS = Set.of(
            "session.created", "chat.completed", "chat.message",
            "email.received", "email.sent", "note.reminder",
            "task.triggered", "connector.synced", "webhook.test"
    );

    // Private/internal network ranges to block (SSRF protection)
    private static final List<String> PRIVATE_PREFIXES = List.of(
            "10.", "172.16.", "172.17.", "172.18.", "172.19.", "172.20.",
            "172.21.", "172.22.", "172.23.", "172.24.", "172.25.", "172.26.",
            "172.27.", "172.28.", "172.29.", "172.30.", "172.31.",
            "192.168.", "127.", "169.254.", "0.", "::1", "fc", "fd", "fe80"
    );

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final EventBusService eventBus;

    public WebhookService(JdbcTemplate db, ObjectMapper mapper, EventBusService eventBus) {
        this.db = db;
        this.mapper = mapper;
        this.eventBus = eventBus;

        // Subscribe to all events and dispatch to webhooks
        eventBus.subscribe("*", this::dispatch);
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /**
     * Creates a new outgoing webhook configuration.
     *
     * @param owner the user ID who owns this webhook
     * @param req   webhook fields: {@code url} (required), {@code name}, {@code secret},
     *              {@code events}, {@code team_id}
     * @return the created webhook record
     * @throws IllegalArgumentException if the URL is invalid or targets a private network
     */
    public Map<String, Object> create(String owner, Map<String, Object> req) {
        validateUrl((String) req.get("url"));
        String id = "wh-" + Long.toString(System.currentTimeMillis(), 36);
        String now = Instant.now().toString();

        db.update("""
                INSERT INTO webhooks (id, owner, name, url, secret, events_json, enabled, team_id, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)""",
                id, owner,
                req.getOrDefault("name", "Webhook"),
                req.get("url"),
                req.get("secret"),
                toJson(req.getOrDefault("events", List.of("chat.completed"))),
                1, req.get("team_id"), now, now);

        return getById(id, owner);
    }

    /**
     * Returns the webhook with the given ID owned by the given user.
     *
     * @param id    the webhook ID
     * @param owner the requesting user ID
     * @return the webhook record, or {@code null} if not found
     */
    public Map<String, Object> getById(String id, String owner) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM webhooks WHERE id=? AND owner=?", id, owner);
        return rows.isEmpty() ? null : mapRow(rows.get(0));
    }

    /**
     * Returns all webhooks owned by the given user, ordered newest first.
     *
     * @param owner the user ID
     * @return list of webhook record maps; never null
     */
    public List<Map<String, Object>> list(String owner) {
        return db.queryForList("SELECT * FROM webhooks WHERE owner=? ORDER BY created_at DESC", owner)
                .stream().map(this::mapRow).toList();
    }

    /**
     * Deletes the webhook with the given ID, restricted to the owning user.
     *
     * @param id    the webhook ID to delete
     * @param owner the user ID — only the owner may delete
     */
    public void delete(String id, String owner) {
        db.update("DELETE FROM webhooks WHERE id=? AND owner=?", id, owner);
    }

    /**
     * Enables or disables the given webhook.
     *
     * @param id      the webhook ID
     * @param owner   the user ID
     * @param enabled {@code true} to enable, {@code false} to disable
     */
    public void setEnabled(String id, String owner, boolean enabled) {
        db.update("UPDATE webhooks SET enabled=? WHERE id=? AND owner=?", enabled ? 1 : 0, id, owner);
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    private void dispatch(String owner, Map<String, Object> payload) {
        String eventName = (String) payload.getOrDefault("event_name", "");
        if (eventName.isBlank()) return;

        try {
            List<Map<String, Object>> webhooks = db.queryForList(
                    "SELECT * FROM webhooks WHERE enabled=1 AND (owner=? OR owner IS NULL)", owner);

            for (Map<String, Object> webhook : webhooks) {
                List<String> events = getEventsList(webhook);
                if (!events.contains(eventName) && !events.contains("*")) continue;

                String url = (String) webhook.get("url");
                String secret = (String) webhook.get("secret");
                Map<String, Object> body = Map.of(
                        "event", eventName,
                        "owner", owner != null ? owner : "",
                        "timestamp", Instant.now().toString(),
                        "data", payload
                );

                Thread.ofVirtual().name("webhook-dispatch-" + webhook.get("id")).start(() -> {
                    fireWithRetry(url, secret, body, (String) webhook.get("id"), 3);
                });
            }
        } catch (Exception e) {
            log.debug("[webhook] Dispatch error: {}", e.getMessage());
        }
    }

    private void fireWithRetry(String url, String secret, Map<String, Object> body, String webhookId, int maxAttempts) {
        int attempt = 0;
        while (attempt < maxAttempts) {
            try {
                String bodyJson = mapper.writeValueAsString(body);
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("X-Olla-Event", (String) body.getOrDefault("event", ""))
                        .header("X-Olla-Timestamp", Instant.now().toString())
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(bodyJson));

                if (secret != null && !secret.isBlank()) {
                    String sig = hmacSha256(secret, bodyJson);
                    builder.header("X-Olla-Signature", "sha256=" + sig);
                }

                HttpResponse<String> resp = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();

                db.update("UPDATE webhooks SET last_fired_at=?, last_status=? WHERE id=?",
                        Instant.now().toString(), status, webhookId);

                if (status >= 200 && status < 300) return;
                log.debug("[webhook] {} returned {}, attempt {}/{}", url, status, attempt + 1, maxAttempts);

            } catch (Exception e) {
                log.debug("[webhook] Fire failed (attempt {}/{}): {}", attempt + 1, maxAttempts, e.getMessage());
            }

            attempt++;
            if (attempt < maxAttempts) {
                try { Thread.sleep(1000L * (1 << attempt)); } catch (InterruptedException ignore) {}
            }
        }
    }

    /** Test a webhook by sending a test payload. */
    public void test(String id, String owner) {
        Map<String, Object> webhook = getById(id, owner);
        if (webhook == null) throw new NoSuchElementException("Webhook not found: " + id);

        Map<String, Object> testBody = Map.of(
                "event", "webhook.test",
                "owner", owner,
                "timestamp", Instant.now().toString(),
                "data", Map.of("message", "This is a test from Olla Nest")
        );
        fireWithRetry((String) webhook.get("url"), (String) webhook.get("secret"), testBody, id, 1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Webhook URL is required");
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new IllegalArgumentException("Webhook URL must use http or https");
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) throw new IllegalArgumentException("Invalid webhook URL");

            // Block private networks
            for (String prefix : PRIVATE_PREFIXES) {
                if (host.startsWith(prefix) || host.equals("localhost")) {
                    throw new IllegalArgumentException("Webhook URL targets a private/internal network");
                }
            }

            // Resolve and check the IP
            InetAddress addr = InetAddress.getByName(host);
            String ip = addr.getHostAddress();
            for (String prefix : PRIVATE_PREFIXES) {
                if (ip.startsWith(prefix)) {
                    throw new IllegalArgumentException("Webhook URL resolves to a private IP");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot validate webhook URL: " + e.getMessage());
        }
    }

    private String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> getEventsList(Map<String, Object> webhook) {
        try {
            String json = (String) webhook.get("events_json");
            return json != null ? mapper.readValue(json, List.class) : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> mapRow(Map<String, Object> row) {
        Map<String, Object> r = new LinkedHashMap<>(row);
        r.put("events", getEventsList(row));
        r.remove("events_json");
        return r;
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return "[]"; }
    }
}
