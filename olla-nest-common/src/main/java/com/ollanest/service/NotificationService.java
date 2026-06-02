package com.ollanest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Notification service — sends alerts via ntfy, browser push, or email.
 *
 * Used by the task scheduler for task reminders and note due-date alerts.
 * Channel is configured per-user in settings. ntfy is the default (self-hosted friendly).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final JdbcTemplate db;
    private final DatabaseService databaseService;

    public NotificationService(JdbcTemplate db, DatabaseService databaseService) {
        this.db = db;
        this.databaseService = databaseService;
    }

    /**
     * Send a notification to the user via their configured channel.
     *
     * @param owner   user ID
     * @param title   notification title
     * @param message notification body
     * @param priority 1=min, 3=default, 5=urgent
     */
    public void notify(String owner, String title, String message, int priority) {
        String channel = databaseService.getSetting("notificationChannel", "none");
        switch (channel) {
            case "ntfy" -> sendNtfy(owner, title, message, priority);
            case "email" -> log.info("[notify] Email notification: {} — {}", title, message);
            case "none" -> {} // No-op
            default -> log.debug("[notify] Unknown channel: {}", channel);
        }
    }

    private void sendNtfy(String owner, String title, String message, int priority) {
        try {
            String ntfyUrl = databaseService.getSetting("ntfyUrl", "https://ntfy.sh");
            String topic = databaseService.getSetting("ntfyTopic", "ollanest-" + owner.substring(0, Math.min(8, owner.length())));
            String url = ntfyUrl.replaceAll("/+$", "") + "/" + topic;

            String auth = databaseService.getSetting("ntfyAuth", "");
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Title", title)
                    .header("Priority", String.valueOf(Math.max(1, Math.min(5, priority))))
                    .header("Content-Type", "text/plain")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8));

            if (!auth.isBlank()) {
                builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.getBytes()));
            }

            HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("[notify] ntfy send failed: {}", e.getMessage());
        }
    }
}
