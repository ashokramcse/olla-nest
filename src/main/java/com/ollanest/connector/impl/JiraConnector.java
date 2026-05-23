package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

/**
 * Jira connector — syncs issues, projects, and comments.
 * Credentials JSON: { "email": "you@co.com", "apiToken": "...", "baseUrl": "https://co.atlassian.net" }
 */
@Component
public class JiraConnector extends BaseConnector {

    @Override public String getType() { return "jira"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String baseUrl  = credStr(creds, "baseUrl").replaceAll("/$", "");
        String auth     = basicAuth(credStr(creds, "email"), credStr(creds, "apiToken"));
        String connId   = (String) config.get("id");
        int synced = 0, skipped = 0;

        try {
            // Fetch recent issues via JQL
            JsonNode result = httpGet(baseUrl + "/rest/api/3/search?jql=ORDER+BY+updated+DESC&maxResults=100&fields=summary,description,status,assignee,reporter,comment", auth);
            for (JsonNode issue : result.path("issues")) {
                String key    = issue.path("key").asText();
                JsonNode f    = issue.path("fields");
                String title  = key + ": " + f.path("summary").asText();
                String url    = baseUrl + "/browse/" + key;
                StringBuilder sb = new StringBuilder("# ").append(title).append("\n\n");
                sb.append("**Status:** ").append(f.path("status").path("name").asText()).append("\n");
                // Description
                JsonNode desc = f.path("description").path("content");
                if (desc.isArray()) extractAtlassianDoc(desc, sb);
                // Comments
                for (JsonNode c : f.path("comment").path("comments")) {
                    sb.append("\n**Comment:** ");
                    extractAtlassianDoc(c.path("body").path("content"), sb);
                }
                if (ingestDocument(connId, key, title, url, sb.toString())) synced++;
                else skipped++;
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) {
            log.error("[jira] sync failed: {}", e.getMessage());
            return SyncResult.error(e.getMessage());
        }
    }

    @Override
    public boolean testConnection(Map<String, Object> config, String credentials) {
        try {
            Map<String, Object> creds = parseCredentials(credentials);
            String baseUrl = credStr(creds, "baseUrl").replaceAll("/$", "");
            httpGet(baseUrl + "/rest/api/3/myself", basicAuth(credStr(creds, "email"), credStr(creds, "apiToken")));
            return true;
        } catch (Exception e) { return false; }
    }

    private String basicAuth(String email, String token) {
        return "Basic " + Base64.getEncoder().encodeToString((email + ":" + token).getBytes());
    }

    private void extractAtlassianDoc(JsonNode nodes, StringBuilder sb) {
        if (nodes == null || !nodes.isArray()) return;
        for (JsonNode node : nodes) {
            String type = node.path("type").asText();
            if ("text".equals(type)) sb.append(node.path("text").asText());
            else if ("paragraph".equals(type) || "bulletList".equals(type) || "orderedList".equals(type))
                extractAtlassianDoc(node.path("content"), sb);
            else if ("listItem".equals(type)) { sb.append("• "); extractAtlassianDoc(node.path("content"), sb); sb.append("\n"); }
            else extractAtlassianDoc(node.path("content"), sb);
        }
    }
}
