package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Connector implementation that synchronises text files from Microsoft OneDrive
 * into the Olla knowledge base via the Microsoft Graph API.
 *
 * <h3>Why this class exists</h3> OneDrive is the primary cloud storage solution
 * for organisations in the Microsoft 365 ecosystem. This connector indexes
 * text-based files from a user's OneDrive root so that the AI assistant can
 * surface relevant documents stored there alongside other enterprise content.
 *
 * <h3>Credential format</h3> <pre>{@code { "accessToken": "..." // Microsoft
 * identity platform OAuth 2.0 Bearer token. // Requires Files.Read (or
 * Files.Read.All) scope. } }</pre>
 *
 * <h3>Design notes</h3> <ul> <li>Only the immediate children of the OneDrive
 * root folder are listed; recursive traversal into subfolders is not yet
 * implemented.</li> <li>Folder items (identified by the presence of a {@code
 * folder} property) are skipped entirely.</li> <li>Files are filtered by MIME
 * type: only those whose type contains {@code "text"}, {@code "word"}, or
 * {@code "plain"} are downloaded and ingested.</li> <li>File content is
 * downloaded via the Graph {@code /content} endpoint which follows redirects
 * automatically via the JDK {@link java.net.http.HttpClient}.</li> <li>Per-file
 * download failures are logged as warnings and do not abort the sync.</li>
 * </ul>
 *
 * <h3>Version history</h3> <ul> <li>v2026.1.4 — initial creation</li> </ul>
 *
 * @author Ashok Ram @since v2026.1.4 @version v2026.1.4 @see
 * com.ollanest.connector.BaseConnector
 */
@Component
public class OneDriveConnector extends BaseConnector {

	/** Base URL for the Microsoft Graph API v1.0. */
	private static final String BASE = "https://graph.microsoft.com/v1.0";

	/**
	 * Returns the connector type identifier used to look up this bean at runtime.
	 *
	 * @return {@code "onedrive"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "onedrive";
	}

	/**
	 * Synchronises text files from the user's OneDrive root into the Olla knowledge
	 * base.
	 *
	 * <p>
	 * The method lists up to 100 items from the OneDrive root folder. For each item
	 * that is a file (not a folder) whose MIME type contains {@code "text"},
	 * {@code "word"}, or {@code "plain"}, the file content is downloaded via the
	 * Graph {@code /content} endpoint and ingested via
	 * {@link BaseConnector#ingestDocument}. Per-file failures are swallowed and
	 * logged as warnings.
	 *
	 * @param config      connector configuration map; must contain {@code "id"}
	 *                    (connector ID).
	 * @param credentials JSON string of the form {@code {"accessToken":"..."} }.
	 * @return a {@link SyncResult} with synced/skipped counts, or an error result
	 *         if the root listing call fails.
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String auth = "Bearer " + credStr(creds, "accessToken");
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;
		try {
			JsonNode items = httpGet(BASE + "/me/drive/root/children?$top=100&$select=id,name,webUrl,file,folder",
					auth);
			for (JsonNode item : items.path("value")) {
				if (!item.has("file"))
					continue;
				String id = item.path("id").asText();
				String name = item.path("name").asText();
				String url = item.path("webUrl").asText();
				String mime = item.path("file").path("mimeType").asText();
				if (!mime.contains("text") && !mime.contains("word") && !mime.contains("plain"))
					continue;
				try {
					java.net.URI uri = java.net.URI.create(BASE + "/me/drive/items/" + id + "/content");
					java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder().uri(uri)
							.header("Authorization", auth).timeout(java.time.Duration.ofSeconds(30)).GET().build();
					java.net.http.HttpResponse<String> resp = http.send(req,
							java.net.http.HttpResponse.BodyHandlers.ofString());
					if (ingestDocument(connId, id, name, url, resp.body()))
						synced++;
					else
						skipped++;
				} catch (Exception ex) {
					log.warn("[onedrive] skip {}: {}", name, ex.getMessage());
				}
			}
			return SyncResult.ok(synced, skipped);
		} catch (Exception e) {
			log.error("[onedrive] {}", e.getMessage());
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
