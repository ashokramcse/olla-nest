package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import com.ollanest.util.UrlValidator;
import java.util.UUID;
import java.util.*;

/**
 * Manages outgoing webhook configurations and dispatches signed HTTP POST
 * payloads to registered endpoints when application events are fired.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Users need a way to integrate Olla Nest events (completed chats, task runs,
 * email arrivals) with external systems such as automation platforms, Slack, or
 * custom pipelines. This service provides a classic webhook model: users register
 * an HTTPS endpoint and a list of event filters; the service signs each delivery
 * with HMAC-SHA256 and retries up to three times with exponential back-off.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>SSRF protection blocks deliveries to private/RFC-1918 IP ranges at both
 * the hostname and DNS-resolved-IP level to prevent internal network pivoting.</li>
 * <li>The HMAC-SHA256 signature is sent in the {@code X-Olla-Signature} header as
 * {@code sha256=hex} so receivers can verify authenticity using the same pattern
 * as GitHub webhooks.</li>
 * <li>Dispatch runs in a virtual thread per webhook so a slow or unreachable
 * target does not block the event bus or other webhooks.</li>
 * <li>A wildcard event-bus subscription is registered in the constructor; the
 * {@code dispatch} method then filters by each webhook's event list at call
 * time.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced as part of the integration and automation expansion</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    /** Recognised event names; webhooks configured with unknown events are silently skipped. */
    private static final Set<String> ALLOWED_EVENTS = Set.of(
            "session.created", "chat.completed", "chat.message",
            "email.received", "email.sent", "note.reminder",
            "task.triggered", "connector.synced", "webhook.test"
    );

    /** Shared HTTP client for all webhook delivery attempts. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** JDBC template for webhook CRUD and delivery status updates. */
    private final JdbcTemplate db;

    /** Shared Jackson mapper for serializing webhook payloads. */
    private final ObjectMapper mapper;

    /** Application event bus; a wildcard subscription is registered at construction. */
    private final EventBusService eventBus;

    /**
     * Constructs a {@code WebhookService} and wires the wildcard event-bus subscription
     * that dispatches all events to registered webhooks.
     *
     * @param db       JDBC template for webhook persistence
     * @param mapper   shared Jackson mapper for payload serialisation
     * @param eventBus the application event bus; a wildcard subscription is registered
     *                 immediately on construction
     * @since v2026.2.1
     */
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
     * @since v2026.2.1
     */
    public Map<String, Object> create(String owner, Map<String, Object> req) {
        validateUrl((String) req.get("url"));
        String id = "wh-" + Long.toString(System.currentTimeMillis(), 36) + "-" + UUID.randomUUID().toString().substring(0, 6);
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
     * @since v2026.2.1
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
     * @since v2026.2.1
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
     * @since v2026.2.1
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
     * @since v2026.2.1
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

    /**
     * Sends a test payload to the webhook to verify delivery and connectivity.
     *
     * @param id    the webhook ID to test
     * @param owner the user ID — only the owner may trigger a test
     * @throws NoSuchElementException if the webhook is not found
     * @since v2026.2.1
     */
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
        // BUG-020: delegate to the central UrlValidator. The previous home-grown
        // string-prefix matching missed IPv6 addresses (e.g. http://[::1]/ — the
        // resolved "0:0:0:0:0:0:0:1" matched no IPv4 prefix), allowing SSRF to
        // loopback/internal services. UrlValidator resolves every A/AAAA record and
        // rejects loopback/link-local/site-local via InetAddress (IPv4 + IPv6).
        if (!UrlValidator.isSafeUrl(url)) {
            throw new IllegalArgumentException("Webhook URL targets a private, internal, or unresolvable address");
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
