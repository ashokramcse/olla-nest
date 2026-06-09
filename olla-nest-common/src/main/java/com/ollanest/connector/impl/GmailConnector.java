package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Spring {@code @Component} that pulls Gmail message metadata into the
 * Olla-Nest knowledge index.
 *
 * <h3>Why this class exists</h3> Critical business communications — vendor
 * confirmations, customer escalations, team announcements — arrive via email
 * and are otherwise invisible to the AI search layer. This connector fetches
 * metadata (subject, sender, date) and the Gmail snippet for up to 50 messages
 * from a configurable label so that the AI layer can answer questions like "did
 * we receive a confirmation from Acme Corp?" without requiring users to
 * manually forward emails.
 *
 * <h3>Credential format</h3>
 * 
 * <pre>
 * {
 *   "accessToken": "ya29.XXXXXXXXXXXXXXXXXXXXXXXXXXXX"  // Google OAuth2 access token with
 *                                                        // gmail.readonly scope
 * }
 * </pre>
 *
 * <h3>Configuration format</h3>
 * 
 * <pre>
 * {
 *   "label": "INBOX"   // Gmail label ID or system label name; defaults to "INBOX" if blank
 * }
 * </pre>
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Only metadata and the pre-computed snippet are fetched
 * ({@code format=metadata}); full email bodies are not downloaded, which keeps
 * request volume and storage footprint low while still providing enough context
 * for most AI search use-cases.</li>
 * <li>Headers {@code Subject}, {@code From}, and {@code Date} are extracted
 * individually from the payload headers array using a switch expression.</li>
 * <li>Each message is keyed by its Gmail message ID, making re-syncs
 * idempotent.</li>
 * <li>If a specific message cannot be fetched (e.g., it was deleted between the
 * list call and the detail call), that message is logged at WARN level and
 * skipped; the rest of the sync continues normally.</li>
 * <li>Messages whose subject is blank are stored with a fallback title of
 * {@code "Email <messageId>"}.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.4 — initial creation</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.4
 * @version v2026.1.4
 * @see com.ollanest.connector.BaseConnector
 */
@Component
public class GmailConnector extends BaseConnector {

	/** Root URL for the Gmail REST API v1 scoped to the authenticated user. */
	private static final String BASE = "https://gmail.googleapis.com/gmail/v1/users/me";

	/**
	 * Returns the connector-type discriminator used to match this implementation to
	 * a persisted connector row whose {@code type} column equals {@code "gmail"}.
	 *
	 * @return the string {@code "gmail"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "gmail";
	}

	/**
	 * Synchronises metadata and snippets for up to 50 Gmail messages from the
	 * configured label with the Olla-Nest document store.
	 *
	 * <p>
	 * The method performs the following steps:
	 * <ol>
	 * <li>Lists up to 50 message IDs from the configured label (defaulting to
	 * {@code "INBOX"}) via
	 * {@code GET /messages?labelIds=:label&maxResults=50}.</li>
	 * <li>For each message, fetches metadata headers ({@code Subject},
	 * {@code From}, {@code Date}) and the pre-computed snippet via
	 * {@code GET /messages/:id?format=metadata}.</li>
	 * <li>Formats the extracted fields as a Markdown document and ingests it keyed
	 * by the Gmail message ID.</li>
	 * </ol>
	 *
	 * @param config      connector row from the database; must contain {@code "id"}
	 *                    (connector UUID); {@code "config_json"} may optionally
	 *                    specify {@code "label"}
	 * @param credentials JSON string containing {@code "accessToken"}
	 * @return {@link SyncResult} with counts of synced and skipped documents, or an
	 *         error message if the message listing fails
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		Map<String, Object> cfg = parseConfig(config);
		String auth = "Bearer " + credStr(creds, "accessToken");
		String label = credStr(cfg, "label");
		if (label.isBlank())
			label = "INBOX";
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;
		try {
			JsonNode list = httpGet(BASE + "/messages?labelIds=" + label + "&maxResults=50", auth);
			for (JsonNode msg : list.path("messages")) {
				String msgId = msg.path("id").asText();
				try {
					JsonNode full = httpGet(
							BASE + "/messages/" + msgId + "?format=metadata&metadataHeaders=Subject,From,Date", auth);
					String subject = "";
					String from = "";
					String date = "";
					for (JsonNode h : full.path("payload").path("headers")) {
						switch (h.path("name").asText()) {
						case "Subject" -> subject = h.path("value").asText();
						case "From" -> from = h.path("value").asText();
						case "Date" -> date = h.path("value").asText();
						}
					}
					String snippet = full.path("snippet").asText("");
					String text = "**Subject:** " + subject + "\n**From:** " + from + "\n**Date:** " + date + "\n\n"
							+ snippet;
					if (ingestDocument(connId, msgId, subject.isBlank() ? "Email " + msgId : subject,
							"https://mail.google.com/mail/u/0/#inbox/" + msgId, text))
						synced++;
					else
						skipped++;
				} catch (Exception ex) {
					log.warn("[gmail] skip {}: {}", msgId, ex.getMessage());
				}
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[gmail] {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Verifies that the supplied OAuth2 access token can authenticate against the
	 * Gmail {@code /profile} endpoint.
	 *
	 * @param config      connector configuration map (not used for this check)
	 * @param credentials JSON string containing {@code "accessToken"}
	 * @return {@code true} if the HTTP call succeeds without an exception;
	 *         {@code false} otherwise
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			httpGet(BASE + "/profile", "Bearer " + credStr(parseCredentials(credentials), "accessToken"));
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Parses the {@code config_json} field from the connector row into a typed map.
	 *
	 * <p>
	 * Returns an empty map if the field is absent or contains malformed JSON,
	 * allowing callers to degrade gracefully rather than throw.
	 *
	 * @param config connector row map; must contain {@code "config_json"} as a JSON
	 *               string
	 * @return a {@code Map<String, Object>} representing the parsed configuration,
	 *         never {@code null}; empty map on parse failure
	 * @since v2026.1.4
	 */
	private Map<String, Object> parseConfig(Map<String, Object> config) {
		String json = (String) config.getOrDefault("config_json", "{}");
		try {
			return mapper.readValue(json,
					mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
		} catch (Exception e) {
			return Map.of();
		}
	}
}
