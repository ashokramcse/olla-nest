package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Linear — issues and projects via GraphQL. Credentials: { "apiKey": "lin_api_..." } */
@Component
public class LinearConnector extends BaseConnector {
    @Override public String getType() { return "linear"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String key    = credStr(creds, "apiKey");
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            String query = "{\"query\":\"{issues(first:100,orderBy:updatedAt){nodes{id title description url state{name}team{name}}}}\"}";
            JsonNode resp = httpPost("https://api.linear.app/graphql", "Bearer " + key,
                    mapper.readValue(query, Map.class));
            for (JsonNode issue : resp.path("data").path("issues").path("nodes")) {
                String id    = issue.path("id").asText();
                String title = issue.path("title").asText();
                String url   = issue.path("url").asText();
                String desc  = issue.path("description").asText("");
                String state = issue.path("state").path("name").asText();
                String team  = issue.path("team").path("name").asText();
                String text  = "# " + title + "\n**State:** " + state + " | **Team:** " + team + "\n\n" + desc;
                if (ingestDocument(connId, id, title, url, text)) synced++; else skipped++;
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[linear] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }

    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try { Map<String, Object> creds = parseCredentials(credentials); httpPost("https://api.linear.app/graphql", "Bearer " + credStr(creds, "apiKey"), Map.of("query", "{viewer{id}}")); return true; } catch (Exception e) { return false; }
    }
}
