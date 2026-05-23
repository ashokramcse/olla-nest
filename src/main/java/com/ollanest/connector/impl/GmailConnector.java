package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Base64;
import java.util.Map;

/** Gmail — emails from inbox/configured label. Credentials: { "accessToken": "..." } Config: { "label": "INBOX", "maxMessages": 100 } */
@Component
public class GmailConnector extends BaseConnector {
    private static final String BASE = "https://gmail.googleapis.com/gmail/v1/users/me";
    @Override public String getType() { return "gmail"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        Map<String, Object> cfg = parseConfig(config);
        String auth = "Bearer " + credStr(creds, "accessToken");
        String label = credStr(cfg, "label");
        if (label.isBlank()) label = "INBOX";
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            JsonNode list = httpGet(BASE + "/messages?labelIds=" + label + "&maxResults=50", auth);
            for (JsonNode msg : list.path("messages")) {
                String msgId = msg.path("id").asText();
                try {
                    JsonNode full = httpGet(BASE + "/messages/" + msgId + "?format=metadata&metadataHeaders=Subject,From,Date", auth);
                    String subject = ""; String from = ""; String date = "";
                    for (JsonNode h : full.path("payload").path("headers")) {
                        switch (h.path("name").asText()) {
                            case "Subject" -> subject = h.path("value").asText();
                            case "From"    -> from    = h.path("value").asText();
                            case "Date"    -> date    = h.path("value").asText();
                        }
                    }
                    String snippet = full.path("snippet").asText("");
                    String text = "**Subject:** " + subject + "\n**From:** " + from + "\n**Date:** " + date + "\n\n" + snippet;
                    if (ingestDocument(connId, msgId, subject.isBlank() ? "Email " + msgId : subject, "https://mail.google.com/mail/u/0/#inbox/" + msgId, text)) synced++; else skipped++;
                } catch (Exception ex) { log.warn("[gmail] skip {}: {}", msgId, ex.getMessage()); }
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[gmail] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }
    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try { httpGet(BASE + "/profile", "Bearer " + credStr(parseCredentials(credentials), "accessToken")); return true; } catch (Exception e) { return false; }
    }
    private Map<String, Object> parseConfig(Map<String, Object> config) {
        String json = (String) config.getOrDefault("config_json", "{}");
        try { return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); } catch (Exception e) { return Map.of(); }
    }
}
