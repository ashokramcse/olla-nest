package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Airtable — bases and tables. Credentials: { "apiKey": "..." } Config: { "baseId": "appXXX" } */
@Component
public class AirtableConnector extends BaseConnector {
    private static final String BASE = "https://api.airtable.com/v0";
    @Override public String getType() { return "airtable"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        Map<String, Object> cfg = parseConfig(config);
        String auth = "Bearer " + credStr(creds, "apiKey");
        String baseId = credStr(cfg, "baseId");
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            JsonNode meta = httpGet("https://api.airtable.com/v0/meta/bases/" + baseId + "/tables", auth);
            for (JsonNode table : meta.path("tables")) {
                String tableId = table.path("id").asText();
                String tableName = table.path("name").asText();
                JsonNode records = httpGet(BASE + "/" + baseId + "/" + java.net.URLEncoder.encode(tableName, "UTF-8") + "?maxRecords=100", auth);
                StringBuilder sb = new StringBuilder("# Table: " + tableName + "\n\n");
                for (JsonNode r : records.path("records")) {
                    sb.append("---\n");
                    r.path("fields").fields().forEachRemaining(e -> sb.append("**").append(e.getKey()).append(":** ").append(e.getValue().asText()).append("\n"));
                }
                if (ingestDocument(connId, baseId + "/" + tableId, tableName, "https://airtable.com/" + baseId, sb.toString())) synced++; else skipped++;
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[airtable] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }
    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try { httpGet("https://api.airtable.com/v0/meta/whoami", "Bearer " + credStr(parseCredentials(credentials), "apiKey")); return true; } catch (Exception e) { return false; }
    }
    private Map<String, Object> parseConfig(Map<String, Object> config) {
        String json = (String) config.getOrDefault("config_json", "{}");
        try { return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); } catch (Exception e) { return Map.of(); }
    }
}
