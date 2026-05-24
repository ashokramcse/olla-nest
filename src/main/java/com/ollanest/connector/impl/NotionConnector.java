package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Connector implementation that synchronises Notion pages into the Olla
 * knowledge base.
 *
 * <h3>Why this class exists</h3> Notion is widely used as a team wiki and
 * knowledge management platform. This connector indexes all pages accessible to
 * a Notion integration token — including their block-level content — so that
 * the AI assistant can answer questions grounded in the organisation's Notion
 * workspace without requiring users to leave the Olla interface.
 *
 * <h3>Credential format</h3> <pre>{@code { "token": "secret_..." // Notion
 * integration token from notion.so/my-integrations. } }</pre>
 *
 * <h3>Design notes</h3> <ul> <li>The Notion API requires a custom {@code
 * Notion-Version} header on every request. This is handled by overriding {@link
 * #httpGet} to inject the header, and by the private {@link #notionPost} helper
 * for POST requests.</li> <li>Pages are discovered via the {@code POST /search}
 * endpoint filtered to objects of type {@code page}. Up to 50 pages are
 * returned per call; cursor-based pagination is not yet implemented.</li>
 * <li>For each page, block children are fetched via {@code GET
 * /blocks/{pageId}/children} (up to 100 blocks). Only blocks with a {@code
 * rich_text} array are rendered; nested blocks (e.g. children of toggle or
 * column blocks) are not recursed into.</li> <li>The title is extracted by
 * probing common Notion property key names ({@code title}, {@code Title},
 * {@code Name}, {@code name}).</li> </ul>
 *
 * <h3>Version history</h3> <ul> <li>v2026.1.4 — initial creation</li> </ul>
 *
 * @author Ashok Ram @since v2026.1.4 @version v2026.1.4 @see
 * com.ollanest.connector.BaseConnector
 */
@Component
public class NotionConnector extends BaseConnector {

	/** Base URL for the Notion REST API v1. */
	private static final String BASE = "https://api.notion.com/v1/";

	/**
	 * Returns the connector type identifier used to look up this bean at runtime.
	 *
	 * @return {@code "notion"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "notion";
	}

	/**
	 * Synchronises Notion pages into the Olla knowledge base.
	 *
	 * <p>
	 * The method searches the workspace for all pages accessible to the integration
	 * token (up to 50), then fetches the block children for each page (up to 100
	 * blocks). Page content is converted to plain Markdown via
	 * {@link #blocksToText} and ingested via {@link BaseConnector#ingestDocument}.
	 *
	 * @param config      connector configuration map; must contain {@code "id"}
	 *                    (connector ID).
	 * @param credentials JSON string of the form {@code {"token":"secret_..."} }.
	 * @return a {@link SyncResult} with synced/skipped counts, or an error result
	 *         if the search call itself fails.
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String token = credStr(creds, "token");
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;

		try {
			// Search all pages accessible to integration
			JsonNode results = notionPost("search", token,
					Map.of("filter", Map.of("value", "page", "property", "object"), "page_size", 50));

			for (JsonNode page : results.path("results")) {
				String pageId = page.path("id").asText();
				String pageUrl = page.path("url").asText();
				String title = extractNotionTitle(page);

				// Fetch page blocks for content
				JsonNode blocks = httpGet(BASE + "blocks/" + pageId + "/children?page_size=100", "Bearer " + token);
				String content = blocksToText(title, blocks);
				if (ingestDocument(connId, pageId, title, pageUrl, content))
					synced++;
				else
					skipped++;
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[notion] sync failed: {}", e.getMessage());
			return SyncResult.error(e.getMessage());
		}
	}

	/**
	 * Validates that the supplied credentials can successfully call the Notion API.
	 *
	 * <p>
	 * The test sends a {@code POST /search} with {@code page_size=1}, which is the
	 * minimal authenticated request and confirms both token validity and
	 * integration access.
	 *
	 * @param config      connector configuration map (not used by this
	 *                    implementation).
	 * @param credentials JSON string containing {@code "token"}.
	 * @return {@code true} if the API call succeeds without throwing; {@code false}
	 *         otherwise.
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			Map<String, Object> creds = parseCredentials(credentials);
			notionPost("search", credStr(creds, "token"), Map.of("page_size", 1));
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Sends an authenticated POST request to a Notion API endpoint, injecting the
	 * required {@code Notion-Version} header.
	 *
	 * @param endpoint the Notion API path segment appended to {@link #BASE} (e.g.
	 *                 {@code "search"}).
	 * @param token    the Notion integration token used as a Bearer token.
	 * @param body     the request body object, which is serialised to JSON.
	 * @return the parsed JSON response body.
	 * @throws Exception if the HTTP request fails or the response cannot be parsed.
	 * @since v2026.1.4
	 */
	private JsonNode notionPost(String endpoint, String token, Map<String, Object> body) throws Exception {
		String json = mapper.writeValueAsString(body);
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create(BASE + endpoint))
				.header("Authorization", "Bearer " + token).header("Notion-Version", "2022-06-28")
				.header("Content-Type", "application/json").timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(json)).build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		return mapper.readTree(resp.body());
	}

	/**
	 * Overrides the base {@code httpGet} to inject the {@code Notion-Version}
	 * header required by every Notion API request.
	 *
	 * <p>
	 * All GET calls to the Notion API — including block-children fetches — go
	 * through this override so that callers do not need to manage the version
	 * header themselves.
	 *
	 * @param url        the full URL to request.
	 * @param authHeader the value of the {@code Authorization} header (e.g.
	 *                   {@code "Bearer secret_..."}).
	 * @return the parsed JSON response body.
	 * @throws Exception if the HTTP request fails or the response cannot be parsed.
	 * @since v2026.1.4
	 */
	@Override
	protected JsonNode httpGet(String url, String authHeader) throws Exception {
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", authHeader)
				.header("Notion-Version", "2022-06-28").header("Accept", "application/json")
				.timeout(Duration.ofSeconds(30)).GET().build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		return mapper.readTree(resp.body());
	}

	/**
	 * Extracts the human-readable title from a Notion page object.
	 *
	 * <p>
	 * Notion pages can store their title under different property keys depending on
	 * how the database or page was created. This method probes the four most common
	 * key names in order: {@code title}, {@code Title}, {@code Name}, {@code name}.
	 * Returns {@code "Untitled"} if no matching property is found or an exception
	 * occurs.
	 *
	 * @param page the Notion page JSON object as returned by the search endpoint.
	 * @return the plain-text title of the page, or {@code "Untitled"} if not found.
	 * @since v2026.1.4
	 */
	private String extractNotionTitle(JsonNode page) {
		try {
			JsonNode props = page.path("properties");
			// Try common title property names
			for (String key : new String[] { "title", "Title", "Name", "name" }) {
				JsonNode t = props.path(key).path("title");
				if (t.isArray() && !t.isEmpty())
					return t.get(0).path("plain_text").asText("Untitled");
			}
		} catch (Exception ignore) {
		}
		return "Untitled";
	}

	/**
	 * Converts a Notion block-children response into a plain Markdown string.
	 *
	 * <p>
	 * Only blocks that contain a {@code rich_text} array (e.g. paragraph, heading,
	 * to-do, bulleted list item) are rendered; block types without rich text (e.g.
	 * divider, image) are silently skipped. Each rendered block is separated by a
	 * newline.
	 *
	 * @param title  the page title, used as the top-level Markdown heading.
	 * @param blocks the JSON object returned by
	 *               {@code GET /blocks/{pageId}/children}.
	 * @return a Markdown string starting with {@code "# <title>\n\n"} followed by
	 *         block text.
	 * @since v2026.1.4
	 */
	private String blocksToText(String title, JsonNode blocks) {
		StringBuilder sb = new StringBuilder("# ").append(title).append("\n\n");
		for (JsonNode block : blocks.path("results")) {
			String type = block.path("type").asText();
			JsonNode content = block.path(type);
			JsonNode richText = content.path("rich_text");
			if (richText.isArray()) {
				for (JsonNode rt : richText)
					sb.append(rt.path("plain_text").asText());
				sb.append("\n");
			}
		}
		return sb.toString();
	}
}
