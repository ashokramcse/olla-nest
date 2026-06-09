package com.ollanest.connector.impl;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;

/**
 * Connector implementation that synchronises Microsoft Teams channel messages
 * into the Olla knowledge base via the Microsoft Graph API.
 *
 * <h3>Why this class exists</h3> Microsoft Teams is the primary communication
 * and collaboration platform for organisations in the Microsoft 365 ecosystem.
 * Indexing Teams channel messages allows the AI assistant to surface team
 * conversations, decisions, and announcements alongside structured documents
 * from other connectors.
 *
 * <h3>Credential format</h3>
 * 
 * <pre>{@code { "accessToken": "..." // Microsoft
 * identity platform OAuth 2.0 Bearer token. // Requires Team.ReadBasic.All and
 * ChannelMessage.Read.All scopes. } }</pre>
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The connector iterates over all teams the authenticated user has joined
 * ({@code GET /me/joinedTeams}), then all channels in each team, then up to 50
 * messages per channel.</li>
 * <li>Teams message bodies are HTML; the HTML tags are stripped via a simple
 * regex ({@code replaceAll("<[^>]+>", "")}) to produce plain text.</li>
 * <li>Per-channel message fetch failures are caught and logged as warnings so
 * that a single restricted channel does not abort the entire sync.</li>
 * <li>Each channel is stored as a single document with a composite ID ({@code
 * <teamId>/<channelId>}) that is stable across renames.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.4 — initial creation</li>
 * </ul>
 *
 * @author Ashok Ram @since v2026.1.4 @version v2026.1.4 @see
 *         com.ollanest.connector.BaseConnector
 */
@Component
public class TeamsConnector extends BaseConnector {

	/** Base URL for the Microsoft Graph API v1.0. */
	private static final String BASE = "https://graph.microsoft.com/v1.0";

	/**
	 * Returns the connector type identifier used to look up this bean at runtime.
	 *
	 * @return {@code "teams"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "teams";
	}

	/**
	 * Synchronises Microsoft Teams channel messages into the Olla knowledge base.
	 *
	 * <p>
	 * The method performs a three-level traversal:
	 * <ol>
	 * <li>Lists all teams the user has joined via {@code GET /me/joinedTeams}.</li>
	 * <li>For each team, lists all channels via
	 * {@code GET /teams/{teamId}/channels}.</li>
	 * <li>For each channel, fetches the last 50 messages via
	 * {@code GET /teams/{teamId}/channels/{channelId}/messages?$top=50}.</li>
	 * </ol>
	 * Message bodies are stripped of HTML tags before being assembled into a
	 * Markdown document and passed to {@link BaseConnector#ingestDocument}.
	 * Failures on individual channel fetches are swallowed and logged as warnings.
	 *
	 * @param config      connector configuration map; must contain {@code "id"}
	 *                    (connector ID).
	 * @param credentials JSON string of the form {@code {"accessToken":"..."} }.
	 * @return a {@link SyncResult} with synced/skipped counts, or an error result
	 *         if the top-level teams listing fails.
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String auth = "Bearer " + credStr(creds, "accessToken");
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;
		try {
			JsonNode teams = httpGet(BASE + "/me/joinedTeams", auth);
			for (JsonNode team : teams.path("value")) {
				String teamId = team.path("id").asText();
				String teamName = team.path("displayName").asText();
				JsonNode channels = httpGet(BASE + "/teams/" + teamId + "/channels", auth);
				for (JsonNode ch : channels.path("value")) {
					String chId = ch.path("id").asText();
					String chName = ch.path("displayName").asText();
					try {
						JsonNode msgs = httpGet(BASE + "/teams/" + teamId + "/channels/" + chId + "/messages?$top=50",
								auth);
						StringBuilder sb = new StringBuilder("# " + teamName + " > " + chName + "\n\n");
						for (JsonNode m : msgs.path("value")) {
							String body = m.path("body").path("content").asText("").replaceAll("<[^>]+>", "").trim();
							if (!body.isEmpty())
								sb.append("• ").append(body).append("\n");
						}
						if (ingestDocument(connId, teamId + "/" + chId, teamName + " > " + chName,
								"https://teams.microsoft.com", sb.toString())) {
							synced++;
						} else {
							skipped++;
						}
					} catch (Exception ex) {
						log.warn("[teams] skip channel: {}", ex.getMessage());
					}
				}
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[teams] {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Validates that the supplied credentials can successfully call the Microsoft
	 * Graph API.
	 *
	 * <p>
	 * The test calls {@code GET /me}, which returns the authenticated user's
	 * profile and is the standard liveness check for Microsoft Graph integrations.
	 *
	 * @param config      connector configuration map (not used by this
	 *                    implementation).
	 * @param credentials JSON string containing {@code "accessToken"}.
	 * @return {@code true} if the API call succeeds without throwing; {@code false}
	 *         otherwise.
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			httpGet(BASE + "/me", "Bearer " + credStr(parseCredentials(credentials), "accessToken"));
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
