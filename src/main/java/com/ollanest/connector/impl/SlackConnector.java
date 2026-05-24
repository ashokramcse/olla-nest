package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Connector implementation that synchronises Slack public channel messages into
 * the Olla knowledge base.
 *
 * <h3>Why this class exists</h3> Slack is the primary asynchronous
 * communication tool for many engineering and operations teams. Indexing
 * channel history allows the AI assistant to answer questions about decisions,
 * announcements, and discussions that live in Slack rather than in structured
 * documentation.
 *
 * <h3>Credential format</h3> <pre>{@code { "token": "xoxb-..." // Slack bot
 * token with channels:read and channels:history scopes. } }</pre>
 *
 * <h3>Configuration format</h3> <pre>{@code { "channels": ["general",
 * "engineering"] // Optional channel filter (not yet used; all // public
 * channels are listed via the API). } }</pre>
 *
 * <h3>Design notes</h3> <ul> <li>All Slack Web API responses include an {@code
 * ok} boolean field. This connector checks {@code ok} on the channel list
 * response and skips individual channel history fetches that return {@code ok:
 * false} rather than throwing.</li> <li>Up to 100 public channels are listed,
 * and up to 100 messages are fetched per channel. Cursor-based pagination is
 * not yet implemented.</li> <li>Empty messages (e.g. bot events with no text)
 * are silently skipped during content assembly.</li> <li>Each channel is stored
 * as a single document whose ID equals the Slack channel ID; re-syncing
 * replaces the previous snapshot via the deduplication logic in {@link
 * BaseConnector#ingestDocument}.</li> </ul>
 *
 * <h3>Version history</h3> <ul> <li>v2026.1.4 — initial creation</li> </ul>
 *
 * @author Ashok Ram @since v2026.1.4 @version v2026.1.4 @see
 * com.ollanest.connector.BaseConnector
 */
@Component
public class SlackConnector extends BaseConnector {

	/** Base URL for the Slack Web API. */
	private static final String BASE = "https://slack.com/api/";

	/**
	 * Returns the connector type identifier used to look up this bean at runtime.
	 *
	 * @return {@code "slack"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "slack";
	}

	/**
	 * Synchronises public Slack channel messages into the Olla knowledge base.
	 *
	 * <p>
	 * The method first lists up to 100 public channels using
	 * {@code conversations.list}. For each channel, it fetches the last 100
	 * messages via {@code conversations.history}. Non-empty message texts are
	 * aggregated into a single Markdown document per channel and passed to
	 * {@link BaseConnector#ingestDocument}. Channels whose history endpoint returns
	 * {@code ok: false} are silently skipped.
	 *
	 * @param config      connector configuration map; must contain {@code "id"}
	 *                    (connector ID).
	 * @param credentials JSON string of the form {@code {"token":"xoxb-..."} }.
	 * @return a {@link SyncResult} with synced/skipped counts, or an error result
	 *         if the channel listing fails or the Slack API returns
	 *         {@code ok: false}.
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String token = credStr(creds, "token");
		String auth = "Bearer " + token;
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;

		try {
			// List public channels
			JsonNode chanList = httpGet(BASE + "conversations.list?types=public_channel&limit=100", auth);
			if (!chanList.path("ok").asBoolean()) {
				throw new RuntimeException(chanList.path("error").asText());
			}

			for (JsonNode ch : chanList.path("channels")) {
				String chanId = ch.path("id").asText();
				String chanName = ch.path("name").asText();

				// Fetch last 100 messages
				JsonNode hist = httpGet(BASE + "conversations.history?channel=" + chanId + "&limit=100", auth);
				if (!hist.path("ok").asBoolean())
					continue;

				StringBuilder sb = new StringBuilder("# #" + chanName + "\n\n");
				for (JsonNode msg : hist.path("messages")) {
					String text = msg.path("text").asText("").trim();
					if (text.isEmpty())
						continue;
					sb.append("• ").append(text).append("\n");
				}
				String content = sb.toString();
				if (ingestDocument(connId, chanId, "#" + chanName, "https://slack.com/", content))
					synced++;
				else
					skipped++;
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[slack] sync failed: {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Validates that the supplied token can successfully authenticate with the
	 * Slack API.
	 *
	 * <p>
	 * The test calls {@code auth.test}, which is the canonical Slack API endpoint
	 * for verifying a token. It returns {@code ok: true} on success and identifies
	 * the bot or user the token belongs to.
	 *
	 * @param config      connector configuration map (not used by this
	 *                    implementation).
	 * @param credentials JSON string containing {@code "token"}.
	 * @return {@code true} if the API call returns {@code ok: true}; {@code false}
	 *         otherwise.
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			Map<String, Object> creds = parseCredentials(credentials);
			JsonNode r = httpGet(BASE + "auth.test", "Bearer " + credStr(creds, "token"));
			return r.path("ok").asBoolean();
		} catch (Exception e) {
			return false;
		}
	}
}
