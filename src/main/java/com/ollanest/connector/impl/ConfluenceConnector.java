package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

/**
 * Confluence connector — syncs spaces and pages.
 * Credentials JSON: { "email": "you@co.com", "apiToken": "...", "baseUrl": "https://co.atlassian.net/wiki" }
 */
@Component
public class ConfluenceConnector extends BaseConnector {

    @Override public String getType() { return "confluence"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String baseUrl  = credStr(creds, "baseUrl").replaceAll("/$", "");
        String auth     = "Basic " + Base64.getEncoder().encodeToString(
                (credStr(creds, "email") + ":" + credStr(creds, "apiToken")).getBytes());
        String connId   = (String) config.get("id");
        int synced = 0, skipped = 0;

        try {
            JsonNode pages = httpGet(baseUrl + "/rest/api/content?type=page&limit=50&expand=body.view,space,version&orderby=modified+desc", auth);
            for (JsonNode page : pages.path("results")) {
                String pageId  = page.path("id").asText();
                String title   = page.path("title").asText();
                String space   = page.path("space").path("name").asText();
                String pageUrl = baseUrl + page.path("_links").path("webui").asText();
                String html    = page.path("body").path("view").path("value").asText();
                // Strip HTML tags for plain text
                String text    = "# " + title + " [" + space + "]\n\n" + html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
                if (ingestDocument(connId, pageId, title, pageUrl, text)) synced++;
                else skipped++;
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) {
            log.error("[confluence] sync failed: {}", e.getMessage());
            return SyncResult.error(e.getMessage());
        }
    }

    @Override
    public boolean testConnection(Map<String, Object> config, String credentials) {
        try {
            Map<String, Object> creds = parseCredentials(credentials);
            String baseUrl = credStr(creds, "baseUrl").replaceAll("/$", "");
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    (credStr(creds, "email") + ":" + credStr(creds, "apiToken")).getBytes());
            httpGet(baseUrl + "/rest/api/space?limit=1", auth);
            return true;
        } catch (Exception e) { return false; }
    }
}
