package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Slack connector — syncs public channel messages and threads.
 * Credentials JSON: { "token": "xoxb-..." }
 * Config JSON:      { "channels": ["general","engineering"] }
 */
@Component
public class SlackConnector extends BaseConnector {

    private static final String BASE = "https://slack.com/api/";

    @Override public String getType() { return "slack"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String token  = credStr(creds, "token");
        String auth   = "Bearer " + token;
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;

        try {
            // List public channels
            JsonNode chanList = httpGet(BASE + "conversations.list?types=public_channel&limit=100", auth);
            if (!chanList.path("ok").asBoolean()) throw new RuntimeException(chanList.path("error").asText());

            for (JsonNode ch : chanList.path("channels")) {
                String chanId   = ch.path("id").asText();
                String chanName = ch.path("name").asText();

                // Fetch last 100 messages
                JsonNode hist = httpGet(BASE + "conversations.history?channel=" + chanId + "&limit=100", auth);
                if (!hist.path("ok").asBoolean()) continue;

                StringBuilder sb = new StringBuilder("# #" + chanName + "\n\n");
                for (JsonNode msg : hist.path("messages")) {
                    String text = msg.path("text").asText("").trim();
                    if (text.isEmpty()) continue;
                    sb.append("• ").append(text).append("\n");
                }
                String content = sb.toString();
                if (ingestDocument(connId, chanId, "#" + chanName, "https://slack.com/", content)) synced++;
                else skipped++;
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) {
            log.error("[slack] sync failed: {}", e.getMessage());
            return SyncResult.error(e.getMessage());
        }
    }

    @Override
    public boolean testConnection(Map<String, Object> config, String credentials) {
        try {
            Map<String, Object> creds = parseCredentials(credentials);
            JsonNode r = httpGet(BASE + "auth.test", "Bearer " + credStr(creds, "token"));
            return r.path("ok").asBoolean();
        } catch (Exception e) { return false; }
    }
}
