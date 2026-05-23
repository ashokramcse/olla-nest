package com.ollanest.connector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Google Drive connector — syncs Google Docs and uploaded files (PDF, TXT).
 * Credentials JSON: { "accessToken": "ya29..." }   (OAuth access token, refreshed externally or by SSO)
 */
@Component
public class GoogleDriveConnector extends BaseConnector {

    private static final String DRIVE_BASE = "https://www.googleapis.com/drive/v3";
    private static final String EXPORT_BASE = "https://www.googleapis.com/drive/v3/files";

    @Override public String getType() { return "gdrive"; }

    @Override
    public SyncResult sync(Map<String, Object> config, String credentials) {
        Map<String, Object> creds = parseCredentials(credentials);
        String token  = credStr(creds, "accessToken");
        String auth   = "Bearer " + token;
        String connId = (String) config.get("id");
        int synced = 0, skipped = 0;

        try {
            // List recent files — Docs, PDFs, plain text
            String query = "mimeType='application/vnd.google-apps.document' or mimeType='application/pdf' or mimeType='text/plain'";
            JsonNode files = httpGet(DRIVE_BASE + "/files?q=" + java.net.URLEncoder.encode(query, "UTF-8") +
                    "&fields=files(id,name,webViewLink,mimeType)&pageSize=50&orderBy=modifiedTime+desc", auth);

            for (JsonNode file : files.path("files")) {
                String fileId   = file.path("id").asText();
                String name     = file.path("name").asText();
                String fileUrl  = file.path("webViewLink").asText();
                String mime     = file.path("mimeType").asText();
                String content;

                try {
                    if ("application/vnd.google-apps.document".equals(mime)) {
                        // Export Google Doc as plain text
                        java.net.URI uri = java.net.URI.create(EXPORT_BASE + "/" + fileId + "/export?mimeType=text/plain");
                        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                                .uri(uri).header("Authorization", auth)
                                .timeout(java.time.Duration.ofSeconds(30)).GET().build();
                        java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                        content = resp.body();
                    } else {
                        // Download as-is (PDF/TXT)
                        java.net.URI uri = java.net.URI.create(EXPORT_BASE + "/" + fileId + "?alt=media");
                        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                                .uri(uri).header("Authorization", auth)
                                .timeout(java.time.Duration.ofSeconds(30)).GET().build();
                        java.net.http.HttpResponse<byte[]> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                        content = "application/pdf".equals(mime) ? extractPdf(resp.body()) : new String(resp.body());
                    }
                    if (ingestDocument(connId, fileId, name, fileUrl, content)) synced++;
                    else skipped++;
                } catch (Exception ex) {
                    log.warn("[gdrive] skipping file {}: {}", name, ex.getMessage());
                }
            }
            return SyncResult.ok(synced, skipped);
        } catch (Exception e) {
            log.error("[gdrive] sync failed: {}", e.getMessage());
            return SyncResult.error(e.getMessage());
        }
    }

    @Override
    public boolean testConnection(Map<String, Object> config, String credentials) {
        try {
            Map<String, Object> creds = parseCredentials(credentials);
            httpGet("https://www.googleapis.com/drive/v3/about?fields=user", "Bearer " + credStr(creds, "accessToken"));
            return true;
        } catch (Exception e) { return false; }
    }

    private String extractPdf(byte[] bytes) {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        } catch (Exception e) { return ""; }
    }
}
