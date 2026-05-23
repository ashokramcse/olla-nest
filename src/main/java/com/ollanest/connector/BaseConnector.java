package com.ollanest.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.service.CryptoService;
import com.ollanest.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Abstract base for all Olla Nest connectors.
 *
 * <p>Subclasses implement {@link #getType()}, {@link #sync(Map, String)}, and
 * {@link #testConnection(Map, String)}.  The base provides shared HTTP helpers,
 * content-hash deduplication, and RAG ingestion wiring.
 */
public abstract class BaseConnector {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ObjectMapper mapper = new ObjectMapper();
    protected final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    protected JdbcTemplate db;
    protected RagService ragService;
    protected CryptoService cryptoService;

    public void setDependencies(JdbcTemplate db, RagService ragService, CryptoService cryptoService) {
        this.db = db;
        this.ragService = ragService;
        this.cryptoService = cryptoService;
    }

    /** Connector type key, e.g. "github", "slack", "notion". */
    public abstract String getType();

    /**
     * Perform a full sync of the connector.
     *
     * @param config     the {@code connector_configs} row
     * @param credentials decrypted credentials JSON string
     * @return {@link SyncResult}
     */
    public abstract SyncResult sync(Map<String, Object> config, String credentials);

    /**
     * Quick connectivity test — do not ingest anything.
     * @return true if credentials are valid and the remote is reachable.
     */
    public abstract boolean testConnection(Map<String, Object> config, String credentials);

    // ── Shared HTTP helpers ────────────────────────────────────────────────

    protected JsonNode httpGet(String url, String authHeader) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
            throw new RuntimeException("HTTP " + resp.statusCode() + " from " + url);
        return mapper.readTree(resp.body());
    }

    protected JsonNode httpPost(String url, String authHeader, Map<String, Object> body) throws Exception {
        String json = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
            throw new RuntimeException("HTTP " + resp.statusCode() + " from " + url);
        return mapper.readTree(resp.body());
    }

    // ── RAG ingestion with deduplication ──────────────────────────────────

    /**
     * Ingest or update a document from this connector into the RAG store.
     * Uses content-hash to skip unchanged documents.
     *
     * @return true if the document was newly ingested or updated.
     */
    protected boolean ingestDocument(String connectorId, String externalId,
                                     String title, String url, String content) {
        String hash = sha256(content);
        List<Map<String, Object>> existing = db.queryForList(
                "SELECT id, content_hash, rag_doc_id FROM connector_documents " +
                "WHERE connector_id = ? AND external_id = ?", connectorId, externalId);

        if (!existing.isEmpty() && hash.equals(existing.get(0).get("content_hash"))) {
            return false; // unchanged
        }

        // Delete old RAG doc if present
        if (!existing.isEmpty() && existing.get(0).get("rag_doc_id") != null) {
            try { ragService.deleteDocument((String) existing.get(0).get("rag_doc_id")); }
            catch (Exception ignore) {}
        }

        // Ingest into RAG
        String docId = ragService.ingestText(content, title, connectorId);

        String now = Instant.now().toString();
        if (existing.isEmpty()) {
            String cdId = "cd-" + Long.toString(System.currentTimeMillis(), 36);
            db.update("INSERT INTO connector_documents (id, connector_id, external_id, title, url, content_hash, rag_doc_id, synced_at) VALUES (?,?,?,?,?,?,?,?)",
                    cdId, connectorId, externalId, title, url, hash, docId, now);
        } else {
            db.update("UPDATE connector_documents SET title=?, url=?, content_hash=?, rag_doc_id=?, synced_at=? WHERE connector_id=? AND external_id=?",
                    title, url, hash, docId, now, connectorId, externalId);
        }
        return true;
    }

    protected Map<String, Object> parseCredentials(String credsJson) {
        try {
            return mapper.readValue(credsJson, mapper.getTypeFactory()
                    .constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    protected String credStr(Map<String, Object> creds, String key) {
        Object v = creds.get(key);
        return v != null ? v.toString() : "";
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) { return ""; }
    }

    // ── SyncResult ─────────────────────────────────────────────────────────

    public record SyncResult(int synced, int skipped, int deleted, String error) {
        public static SyncResult ok(int synced, int skipped) {
            return new SyncResult(synced, skipped, 0, null);
        }
        public static SyncResult error(String msg) {
            return new SyncResult(0, 0, 0, msg);
        }
        public boolean isOk() { return error == null; }
    }
}
