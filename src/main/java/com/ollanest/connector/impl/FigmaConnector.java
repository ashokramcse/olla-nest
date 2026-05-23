package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Figma — file names, descriptions, and component names. Credentials: { "token": "figd_..." } */
@Component
public class FigmaConnector extends BaseConnector {
    @Override public String getType() { return "figma"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String auth = "Bearer " + credStr(creds, "token");
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;
        try {
            JsonNode projects = httpGet("https://api.figma.com/v1/me/projects", auth);
            for (JsonNode proj : projects.path("projects")) {
                String projId = proj.path("id").asText();
                String projName = proj.path("name").asText();
                JsonNode files = httpGet("https://api.figma.com/v1/projects/" + projId + "/files", auth);
                for (JsonNode file : files.path("files")) {
                    String fileKey = file.path("key").asText();
                    String fileName = file.path("name").asText();
                    String fileUrl = "https://www.figma.com/file/" + fileKey;
                    // Get components
                    try {
                        JsonNode detail = httpGet("https://api.figma.com/v1/files/" + fileKey + "/components", auth);
                        StringBuilder sb = new StringBuilder("# Figma: " + fileName + " (" + projName + ")\n\n");
                        for (JsonNode c : detail.path("meta").path("components")) {
                            sb.append("- ").append(c.path("name").asText()).append(": ").append(c.path("description").asText("")).append("\n");
                        }
                        if (ingestDocument(connId, fileKey, fileName, fileUrl, sb.toString())) synced++; else skipped++;
                    } catch (Exception ex) {
                        // Fallback: just name + project
                        if (ingestDocument(connId, fileKey, fileName, fileUrl, "# " + fileName + "\nProject: " + projName)) synced++; else skipped++;
                    }
                }
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) { log.error("[figma] {}", e.getMessage()); return SyncResult.error(e.getMessage()); }
    }
    @Override public boolean testConnection(Map<String, Object> config, String credentials) {
        try { httpGet("https://api.figma.com/v1/me", "Bearer " + credStr(parseCredentials(credentials), "token")); return true; } catch (Exception e) { return false; }
    }
}
