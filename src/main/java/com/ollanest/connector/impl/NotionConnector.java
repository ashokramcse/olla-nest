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
 * Notion connector — syncs pages and database entries.
 * Credentials JSON: { "token": "secret_..." }
 */
@Component
public class NotionConnector extends BaseConnector {

    private static final String BASE = "https://api.notion.com/v1/";

    @Override public String getType() { return "notion"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String token  = credStr(creds, "token");
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;

        try {
            // Search all pages accessible to integration
            JsonNode results = notionPost("search", token, Map.of(
                    "filter", Map.of("value", "page", "property", "object"),
                    "page_size", 50));

            for (JsonNode page : results.path("results")) {
                String pageId  = page.path("id").asText();
                String pageUrl = page.path("url").asText();
                String title   = extractNotionTitle(page);

                // Fetch page blocks for content
                JsonNode blocks = httpGet(BASE + "blocks/" + pageId + "/children?page_size=100",
                        "Bearer " + token);
                String content = blocksToText(title, blocks);
                if (ingestDocument(connId, pageId, title, pageUrl, content)) synced++;
                else skipped++;
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) {
            log.error("[notion] sync failed: {}", e.getMessage());
            return SyncResult.error(e.getMessage());
        }
    }

    @Override
    public boolean testConnection(Map<String, Object> config, String credentials) {
        try {
            Map<String, Object> creds = parseCredentials(credentials);
            notionPost("search", credStr(creds, "token"), Map.of("page_size", 1));
            return true;
        } catch (Exception e) { return false; }
    }

    private JsonNode notionPost(String endpoint, String token, Map<String, Object> body) throws Exception {
        String json = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + endpoint))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", "2022-06-28")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }

    @Override
    protected JsonNode httpGet(String url, String authHeader) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Notion-Version", "2022-06-28")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }

    private String extractNotionTitle(JsonNode page) {
        try {
            JsonNode props = page.path("properties");
            // Try common title property names
            for (String key : new String[]{"title", "Title", "Name", "name"}) {
                JsonNode t = props.path(key).path("title");
                if (t.isArray() && !t.isEmpty()) return t.get(0).path("plain_text").asText("Untitled");
            }
        } catch (Exception ignore) {}
        return "Untitled";
    }

    private String blocksToText(String title, JsonNode blocks) {
        StringBuilder sb = new StringBuilder("# ").append(title).append("\n\n");
        for (JsonNode block : blocks.path("results")) {
            String type = block.path("type").asText();
            JsonNode content = block.path(type);
            JsonNode richText = content.path("rich_text");
            if (richText.isArray()) {
                for (JsonNode rt : richText) sb.append(rt.path("plain_text").asText());
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
