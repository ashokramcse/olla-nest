package com.ollanest.connector.impl;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;

/**
 * Connector implementation that synchronises Linear issues into the Olla
 * knowledge base.
 *
 * <h3>Why this class exists</h3> Linear is a modern issue-tracking tool popular
 * with product and engineering teams. This connector indexes issues — including
 * their state and team context — via the Linear GraphQL API so that the AI
 * assistant can surface relevant engineering work alongside other
 * organisational knowledge.
 *
 * <h3>Credential format</h3>
 * 
 * <pre>{@code { "apiKey": "lin_api_..." // Linear
 * personal API key; sent as a Bearer token. } }</pre>
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Linear exposes a single GraphQL endpoint
 * ({@code https://api.linear.app/graphql}). All data fetching goes through
 * {@link BaseConnector#httpPost} with a JSON body containing the {@code query}
 * field.</li>
 * <li>Issues are fetched in a single query: first 100, ordered by
 * {@code updatedAt}, including {@code state} and {@code team} nested
 * objects.</li>
 * <li>The document ID stored in Olla is the Linear issue UUID, which is
 * stable.</li>
 * <li>The connection test sends a minimal {@code
 * viewer{id}} query which is the lightest authenticated GraphQL call
 * available.</li>
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
public class LinearConnector extends BaseConnector {

	/**
	 * Returns the connector type identifier used to look up this bean at runtime.
	 *
	 * @return {@code "linear"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "linear";
	}

	/**
	 * Synchronises Linear issues into the Olla knowledge base.
	 *
	 * <p>
	 * A single GraphQL query fetches the first 100 issues ordered by
	 * {@code updatedAt}, selecting {@code id}, {@code title}, {@code description},
	 * {@code url}, {@code state{name}}, and {@code team{name}}. Each issue is
	 * formatted as a Markdown snippet with state and team metadata and passed to
	 * {@link BaseConnector#ingestDocument}.
	 *
	 * @param config      connector configuration map; must contain {@code "id"}
	 *                    (connector ID).
	 * @param credentials JSON string of the form {@code {"apiKey":"lin_api_..."} }.
	 * @return a {@link SyncResult} with synced/skipped counts, or an error result
	 *         on failure.
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String key = credStr(creds, "apiKey");
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;
		try {
			String query = "{\"query\":\"{issues(first:100,orderBy:updatedAt){nodes{id title description url state{name}team{name}}}}\"}";
			JsonNode resp = httpPost("https://api.linear.app/graphql", "Bearer " + key,
					mapper.readValue(query, new TypeReference<Map<String, Object>>() {
					}));
			for (JsonNode issue : resp.path("data").path("issues").path("nodes")) {
				String id = issue.path("id").asText();
				String title = issue.path("title").asText();
				String url = issue.path("url").asText();
				String desc = issue.path("description").asText("");
				String state = issue.path("state").path("name").asText();
				String team = issue.path("team").path("name").asText();
				String text = "# " + title + "\n**State:** " + state + " | **Team:** " + team + "\n\n" + desc;
				if (ingestDocument(connId, id, title, url, text))
					synced++;
				else
					skipped++;
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[linear] {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Validates that the supplied credentials can successfully call the Linear
	 * GraphQL API.
	 *
	 * <p>
	 * The test sends a minimal {@code {viewer{id}}} GraphQL query, which is the
	 * standard authentication check for the Linear API.
	 *
	 * @param config      connector configuration map (not used by this
	 *                    implementation).
	 * @param credentials JSON string containing {@code "apiKey"}.
	 * @return {@code true} if the GraphQL call succeeds without throwing;
	 *         {@code false} otherwise.
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			Map<String, Object> creds = parseCredentials(credentials);
			httpPost("https://api.linear.app/graphql", "Bearer " + credStr(creds, "apiKey"),
					Map.of("query", "{viewer{id}}"));
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
