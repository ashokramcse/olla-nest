package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Asana — tasks and projects. Credentials: { "token": "..." } */
@Component
public class AsanaConnector extends BaseConnector {
    private static final String BASE = "https://app.asana.com/api/1.0";
    @Override public String getType() { return "asana"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String auth = "Bearer " + credStr(creds, "token");
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            JsonNode ws = httpGet(BASE + "/workspaces", auth);
            for (JsonNode w : ws.path("data")) {
                String wsId = w.path("gid").asText();
                JsonNode tasks = httpGet(BASE + "/tasks?workspace=" + wsId + "&opt_fields=gid,name,notes,permalink_url&limit=100", auth);
                for (JsonNode t : tasks.path("data")) {
                    String gid  = t.path("gid").asText();
                    String name = t.path("name").asText();
                    String url  = t.path("permalink_url").asText();
                    String notes= t.path("notes").asText("");
                    if (ingestDocument(connId, gid, name, url, "# " + name + "\n\n" + notes)) synced++; else skipped++;
                }
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[asana] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }
    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try { httpGet(BASE + "/users/me", "Bearer " + credStr(parseCredentials(credentials), "token")); return true; } catch (Exception e) { return false; }
    }
}
