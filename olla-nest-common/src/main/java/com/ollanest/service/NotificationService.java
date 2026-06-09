package com.ollanest.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends user-facing alerts through a configurable notification channel (ntfy,
 * email, or none).
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Task reminders and note due-date alerts need to reach the user outside the
 * browser tab. Rather than hard-coding a single delivery mechanism, this
 * service dispatches to whichever channel is configured in settings. ntfy is
 * the default because it is self-hosted-friendly and requires no third-party
 * accounts; email is wired as a logging stub pending SMTP configuration.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The delivery channel is resolved at call time via
 * {@link DatabaseService#getSetting} so changes take effect without a
 * restart.</li>
 * <li>ntfy requests are fired asynchronously via {@code sendAsync} to avoid
 * blocking the scheduler thread.</li>
 * <li>Authentication for protected ntfy topics is passed as a Basic
 * {@code Authorization} header when {@code ntfyAuth} is set.</li>
 * <li>A channel value of {@code "none"} is explicitly handled as a no-op so
 * users can silence notifications entirely without removing the setting.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced as part of the personal productivity
 * expansion</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class NotificationService {

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	/** Shared HTTP client for ntfy push requests. */
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	/** Provides runtime-configurable settings such as ntfy URL and topic. */
	private final DatabaseService databaseService;

	/**
	 * Constructor-injects the settings dependency.
	 *
	 * @param databaseService the settings service used to look up the notification
	 *                        channel and credentials
	 * @since v2026.2.1
	 */
	public NotificationService(DatabaseService databaseService) {
		this.databaseService = databaseService;
	}

	/**
	 * Sends a notification to the user via their configured channel.
	 *
	 * <p>
	 * Reads the {@code notificationChannel} setting at call time and dispatches to
	 * the appropriate delivery mechanism. Unknown channel values are logged at
	 * debug level and silently ignored.
	 *
	 * @param owner    the user ID of the notification recipient
	 * @param title    the notification title / subject line
	 * @param message  the notification body text
	 * @param priority delivery urgency: {@code 1}=min, {@code 3}=default,
	 *                 {@code 5}=urgent
	 * @since v2026.2.1
	 */
	public void notify(String owner, String title, String message, int priority) {
		String channel = databaseService.getSetting("notificationChannel", "none");
		switch (channel) {
		case "ntfy" -> sendNtfy(owner, title, message, priority);
		case "email" -> log.info("[notify] Email notification: {} — {}", title, message);
		case "none" -> {
		} // No-op
		default -> log.debug("[notify] Unknown channel: {}", channel);
		}
	}

	private void sendNtfy(String owner, String title, String message, int priority) {
		try {
			String ntfyUrl = databaseService.getSetting("ntfyUrl", "https://ntfy.sh");
			String topic = databaseService.getSetting("ntfyTopic",
					"ollanest-" + owner.substring(0, Math.min(8, owner.length())));
			String url = ntfyUrl.replaceAll("/+$", "") + "/" + topic;

			String auth = databaseService.getSetting("ntfyAuth", "");
			HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).header("Title", title)
					.header("Priority", String.valueOf(Math.max(1, Math.min(5, priority))))
					.header("Content-Type", "text/plain").timeout(Duration.ofSeconds(10))
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
