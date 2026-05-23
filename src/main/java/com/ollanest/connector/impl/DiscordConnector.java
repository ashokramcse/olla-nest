package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Discord — channel messages. Credentials: { "botToken": "Bot ..." } Config: { "guildId": "..." } */
@Component
public class DiscordConnector extends BaseConnector {
    private static final String BASE = "https://discord.com/api/v10";
    @Override public String getType() { return "discord"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        Map<String, Object> cfg = parseConfig(config);
        String token  = credStr(creds, "botToken");
        String auth   = token.startsWith("Bot ") ? token : "Bot " + token;
        String guildId= credStr(cfg, "guildId");
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            JsonNode channels = httpGet(BASE + "/guilds/" + guildId + "/channels", auth);
            for (JsonNode ch : channels) {
                if (ch.path("type").asInt() != 0) continue; // text channels only
                String chId   = ch.path("id").asText();
                String chName = ch.path("name").asText();
                try {
                    JsonNode messages = httpGet(BASE + "/channels/" + chId + "/messages?limit=100", auth);
                    StringBuilder sb = new StringBuilder("# #" + chName + "\n\n");
                    for (JsonNode m : messages) { String txt = m.path("content").asText("").trim(); if (!txt.isEmpty()) sb.append("• ").append(txt).append("\n"); }
                    if (ingestDocument(connId, chId, "#" + chName, "https://discord.com/channels/" + guildId + "/" + chId, sb.toString())) synced++; else skipped++;
                } catch (Exception ex) { log.warn("[discord] skip #{}: {}", chName, ex.getMessage()); }
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[discord] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }
    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try { Map<String, Object> creds = parseCredentials(credentials); String t = credStr(creds, "botToken"); httpGet(BASE + "/users/@me", t.startsWith("Bot ") ? t : "Bot " + t); return true; } catch (Exception e) { return false; }
    }
    private Map<String, Object> parseConfig(Map<String, Object> config) {
        String json = (String) config.getOrDefault("config_json", "{}");
        try { return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); } catch (Exception e) { return Map.of(); }
    }
}
