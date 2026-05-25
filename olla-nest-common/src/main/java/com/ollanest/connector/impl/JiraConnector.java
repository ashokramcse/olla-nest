package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

/**
 * Connector implementation that synchronises Jira issues into the Olla
 * knowledge base.
 *
 * <h3>Why this class exists</h3> Jira is the de-facto issue tracker used by
 * software engineering teams. This connector indexes issues — including their
 * Atlassian Document Format (ADF) descriptions and comments — so that the AI
 * assistant can answer questions about project status, bug details, and team
 * discussions without requiring users to switch context into Jira.
 *
 * <h3>Credential format</h3> <pre>{@code { "email": "you@company.com",
 * "apiToken": "...", // Atlassian API token (not your password) "baseUrl":
 * "https://co.atlassian.net" // trailing slash is stripped automatically }
 * }</pre>
 *
 * <h3>Design notes</h3> <ul> <li>Authentication uses HTTP Basic with the {@code
 * email:apiToken} pair Base64-encoded.</li> <li>Issues are fetched via the Jira
 * REST API v3 search endpoint with JQL {@code ORDER BY updated DESC}, limited
 * to 100 results.</li> <li>Descriptions and comment bodies use Atlassian
 * Document Format (ADF), a nested JSON tree. {@link #extractAtlassianDoc}
 * recursively traverses the tree to produce plain text with bullet-point
 * markers for list items.</li> <li>The document ID stored in Olla equals the
 * Jira issue key (e.g. {@code PROJ-42}), which is stable across renames.</li>
 * </ul>
 *
 * <h3>Version history</h3> <ul> <li>v2026.1.4 — initial creation</li> </ul>
 *
 * @author Ashok Ram @since v2026.1.4 @version v2026.1.4 @see
 * com.ollanest.connector.BaseConnector
 */
@Component
public class JiraConnector extends BaseConnector {

	/**
	 * Returns the connector type identifier used to look up this bean at runtime.
	 *
	 * @return {@code "jira"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "jira";
	}

	/**
	 * Synchronises Jira issues into the Olla knowledge base.
	 *
	 * <p>
	 * The method performs a JQL search ordered by {@code updated DESC} with a
	 * maximum of 100 results. For each issue the following fields are fetched:
	 * {@code summary}, {@code description}, {@code status}, {@code assignee},
	 * {@code reporter}, and {@code comment}. Description and comment bodies are in
	 * Atlassian Document Format (ADF) and are converted to plain text by
	 * {@link #extractAtlassianDoc}.
	 *
	 * @param config      connector configuration map; must contain {@code "id"}
	 *                    (connector ID).
	 * @param credentials JSON string containing {@code "email"},
	 *                    {@code "apiToken"}, and {@code "baseUrl"}.
	 * @return a {@link SyncResult} with synced/skipped counts, or an error result
	 *         if the JQL search call itself fails.
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String baseUrl = credStr(creds, "baseUrl").replaceAll("/$", "");
		String auth = basicAuth(credStr(creds, "email"), credStr(creds, "apiToken"));
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;

		try {
			// Fetch recent issues via JQL
			JsonNode result = httpGet(baseUrl + "/rest/api/3/search?jql=ORDER+BY+updated+DESC&maxResults=100"
					+ "&fields=summary,description,status,assignee,reporter,comment", auth);
			for (JsonNode issue : result.path("issues")) {
				String key = issue.path("key").asText();
				JsonNode f = issue.path("fields");
				String title = key + ": " + f.path("summary").asText();
				String url = baseUrl + "/browse/" + key;
				StringBuilder sb = new StringBuilder("# ").append(title).append("\n\n");
				sb.append("**Status:** ").append(f.path("status").path("name").asText()).append("\n");
				// Description
				JsonNode desc = f.path("description").path("content");
				if (desc.isArray())
					extractAtlassianDoc(desc, sb);
				// Comments
				for (JsonNode c : f.path("comment").path("comments")) {
					sb.append("\n**Comment:** ");
					extractAtlassianDoc(c.path("body").path("content"), sb);
				}
				if (ingestDocument(connId, key, title, url, sb.toString()))
					synced++;
				else
					skipped++;
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[jira] sync failed: {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Validates that the supplied credentials can successfully authenticate against
	 * the Jira API.
	 *
	 * <p>
	 * The test calls {@code GET /rest/api/3/myself}, which returns the
	 * authenticated user's profile and is the canonical liveness check recommended
	 * by Atlassian.
	 *
	 * @param config      connector configuration map (not used by this
	 *                    implementation).
	 * @param credentials JSON string containing {@code "email"},
	 *                    {@code "apiToken"}, and {@code "baseUrl"}.
	 * @return {@code true} if the API call succeeds without throwing; {@code false}
	 *         otherwise.
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			Map<String, Object> creds = parseCredentials(credentials);
			String baseUrl = credStr(creds, "baseUrl").replaceAll("/$", "");
			httpGet(baseUrl + "/rest/api/3/myself", basicAuth(credStr(creds, "email"), credStr(creds, "apiToken")));
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Builds an HTTP Basic Authorization header value from an Atlassian email and
	 * API token.
	 *
	 * <p>
	 * Atlassian Cloud APIs expect the format {@code email:apiToken} Base64-encoded,
	 * not a username/password pair.
	 *
	 * @param email the Atlassian account email address.
	 * @param token the Atlassian API token (obtained from id.atlassian.com).
	 * @return a string of the form {@code "Basic <base64>"} suitable for use as an
	 *         HTTP header value.
	 * @since v2026.1.4
	 */
	private String basicAuth(String email, String token) {
		return "Basic " + Base64.getEncoder().encodeToString((email + ":" + token).getBytes());
	}

	/**
	 * Recursively extracts plain text from an Atlassian Document Format (ADF) node
	 * array.
	 *
	 * <p>
	 * ADF is a tree structure where leaf nodes of type {@code "text"} carry the
	 * actual text content. Container nodes such as {@code "paragraph"},
	 * {@code "bulletList"}, and {@code "orderedList"} wrap child nodes in a
	 * {@code "content"} array. List items of type {@code "listItem"} are prefixed
	 * with a bullet character and terminated with a newline. All other node types
	 * are traversed recursively so that unknown future ADF node types still have
	 * their text children extracted.
	 *
	 * @param nodes the {@code "content"} array from an ADF node; may be
	 *              {@code null} or non-array, in which case this method returns
	 *              immediately.
	 * @param sb    the {@link StringBuilder} to which extracted text is appended.
	 * @since v2026.1.4
	 */
	private void extractAtlassianDoc(JsonNode nodes, StringBuilder sb) {
		if (nodes == null || !nodes.isArray())
			return;
		for (JsonNode node : nodes) {
			String type = node.path("type").asText();
			if ("text".equals(type)) {
				sb.append(node.path("text").asText());
			} else if ("paragraph".equals(type) || "bulletList".equals(type) || "orderedList".equals(type)) {
				extractAtlassianDoc(node.path("content"), sb);
			} else if ("listItem".equals(type)) {
				sb.append("• ");
				extractAtlassianDoc(node.path("content"), sb);
				sb.append("\n");
			} else {
				extractAtlassianDoc(node.path("content"), sb);
			}
		}
	}
}
