package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Microsoft Teams — team channels and messages via Graph API. Credentials: { "accessToken": "..." } */
@Component
public class TeamsConnector extends BaseConnector {
    private static final String BASE = "https://graph.microsoft.com/v1.0";
    @Override public String getType() { return "teams"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String auth = "Bearer " + credStr(creds, "accessToken");
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            JsonNode teams = httpGet(BASE + "/me/joinedTeams", auth);
            for (JsonNode team : teams.path("value")) {
                String teamId = team.path("id").asText();
                String teamName = team.path("displayName").asText();
                JsonNode channels = httpGet(BASE + "/teams/" + teamId + "/channels", auth);
                for (JsonNode ch : channels.path("value")) {
                    String chId = ch.path("id").asText();
                    String chName = ch.path("displayName").asText();
                    try {
                        JsonNode msgs = httpGet(BASE + "/teams/" + teamId + "/channels/" + chId + "/messages?$top=50", auth);
                        StringBuilder sb = new StringBuilder("# " + teamName + " > " + chName + "\n\n");
                        for (JsonNode m : msgs.path("value")) {
                            String body = m.path("body").path("content").asText("").replaceAll("<[^>]+>", "").trim();
                            if (!body.isEmpty()) sb.append("• ").append(body).append("\n");
                        }
                        if (ingestDocument(connId, teamId + "/" + chId, teamName + " > " + chName, "https://teams.microsoft.com", sb.toString())) synced++; else skipped++;
                    } catch (Exception ex) { log.warn("[teams] skip channel: {}", ex.getMessage()); }
                }
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[teams] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }
    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try { httpGet(BASE + "/me", "Bearer " + credStr(parseCredentials(credentials), "accessToken")); return true; } catch (Exception e) { return false; }
    }
}
