package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final int CHUNK_SIZE = 1800;   // chars
    private static final int CHUNK_OVERLAP = 200; // chars
    private static final int TOP_K = 5;
    private static final double SIMILARITY_THRESHOLD = 0.30;

    private final JdbcTemplate db;
    private final EmbeddingService embeddingService;
    private final ObjectMapper mapper;

    public RagService(JdbcTemplate db, EmbeddingService embeddingService, ObjectMapper mapper) {
        this.db = db;
        this.embeddingService = embeddingService;
        this.mapper = mapper;
    }

    /** Ingest a document: extract text, chunk, embed, store */
    public Map<String, Object> ingestDocument(String docId, String name, String type, long size,
                                               InputStream inputStream, String uploadedBy, String scope) {
        String text;
        try {
            text = extractText(name, type, inputStream);
        } catch (Exception e) {
            log.error("[rag] Text extraction failed for {}: {}", name, e.getMessage());
            throw new RuntimeException("Text extraction failed: " + e.getMessage());
        }

        List<String> chunks = chunkText(text);
        String now = Instant.now().toString();

        db.update("INSERT INTO rag_documents (id, name, type, size, chunk_count, uploaded_by, scope, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            docId, name, type, size, chunks.size(), uploadedBy, scope, now);

        int embedded = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String chunkId = docId + "-c" + i;
            List<Double> vec = embeddingService.embed(chunk);
            String embJson = vec.isEmpty() ? null : embeddingService.vectorToJson(vec);
            if (!vec.isEmpty()) embedded++;
            db.update("INSERT INTO rag_chunks (id, document_id, chunk_index, content, embedding_json, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                chunkId, docId, i, chunk, embJson, now);
        }

        log.info("[rag] Ingested '{}': {} chunks, {} embedded", name, chunks.size(), embedded);
        return Map.of("id", docId, "name", name, "chunks", chunks.size(), "embedded", embedded);
    }

    /** Retrieve top-K relevant chunks for a query */
    public List<Map<String, Object>> retrieve(String query, String scope, int topK) {
        if (query == null || query.isBlank()) return List.of();
        List<Double> queryVec = embeddingService.embed(query);

        // Load all chunks (with embeddings) for the given scope
        String sql = "SELECT rc.id, rc.content, rc.embedding_json, rd.name as doc_name, rd.id as doc_id " +
                     "FROM rag_chunks rc JOIN rag_documents rd ON rc.document_id = rd.id " +
                     "WHERE rd.scope = 'global'" +
                     (scope != null && !scope.equals("global") ? " OR rd.scope = ?" : "");
        List<Map<String, Object>> rows = scope != null && !scope.equals("global")
            ? db.queryForList(sql, scope) : db.queryForList(sql.replace(" OR rd.scope = ?", ""));

        // Score each chunk
        List<double[]> scores = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            String embJson = (String) row.get("embedding_json");
            double score;
            if (embJson != null && !embJson.isBlank()) {
                List<Double> chunkVec = embeddingService.jsonToVector(embJson);
                score = queryVec.isEmpty()
                    ? embeddingService.keywordSimilarity(query, (String) row.get("content"))
                    : embeddingService.cosineSimilarity(queryVec, chunkVec);
            } else {
                score = embeddingService.keywordSimilarity(query, (String) row.get("content"));
            }
            scores.add(new double[]{i, score});
        }

        int k = topK > 0 ? topK : TOP_K;
        return scores.stream()
            .filter(s -> s[1] >= SIMILARITY_THRESHOLD)
            .sorted((a, b) -> Double.compare(b[1], a[1]))
            .limit(k)
            .map(s -> {
                Map<String, Object> row = rows.get((int) s[0]);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("content", row.get("content"));
                result.put("docName", row.get("doc_name"));
                result.put("docId", row.get("doc_id"));
                result.put("score", Math.round(s[1] * 100.0) / 100.0);
                return result;
            })
            .collect(Collectors.toList());
    }

    /** Build RAG context string to inject into the system prompt */
    public String buildRagContext(String query, String userId) {
        List<Map<String, Object>> chunks = retrieve(query, userId, TOP_K);
        if (chunks.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("---\nKnowledge Base Context (use this to answer if relevant):\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> c = chunks.get(i);
            sb.append("[Source ").append(i + 1).append(": ").append(c.get("docName")).append("]\n");
            sb.append(c.get("content")).append("\n\n");
        }
        sb.append("---");
        return sb.toString();
    }

    public List<Map<String, Object>> listDocuments() {
        return db.queryForList("SELECT id, name, type, size, chunk_count, uploaded_by, scope, created_at FROM rag_documents ORDER BY created_at DESC");
    }

    /**
     * Convenience: ingest plain text directly (used by connectors).
     * Creates a rag_documents row with scope = connectorId and returns the doc ID.
     */
    public String ingestText(String content, String name, String scope) {
        String docId = "doc-" + Long.toString(System.currentTimeMillis(), 36)
                + "-" + Long.toString((long)(Math.random() * 1_000_000), 36);
        List<String> chunks = chunkText(content);
        String now = java.time.Instant.now().toString();
        db.update("INSERT INTO rag_documents (id, name, type, size, chunk_count, uploaded_by, scope, created_at) VALUES (?,?,?,?,?,?,?,?)",
                docId, name, "text/plain", (long) content.length(), chunks.size(), "connector", scope, now);
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String chunkId = docId + "-c" + i;
            List<Double> vec = embeddingService.embed(chunk);
            String embJson = vec.isEmpty() ? null : embeddingService.vectorToJson(vec);
            db.update("INSERT INTO rag_chunks (id, document_id, chunk_index, content, embedding_json, created_at) VALUES (?,?,?,?,?,?)",
                    chunkId, docId, i, chunk, embJson, now);
        }
        return docId;
    }

    public void deleteDocument(String docId) {
        db.update("DELETE FROM rag_chunks WHERE document_id = ?", docId);
        db.update("DELETE FROM rag_documents WHERE id = ?", docId);
    }

    // --- Text extraction ---
    private String extractText(String name, String type, InputStream is) throws Exception {
        String lname = name.toLowerCase();
        if (lname.endsWith(".pdf") || "application/pdf".equals(type)) {
            byte[] bytes = is.readAllBytes();
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                return new PDFTextStripper().getText(doc);
            }
        }
        // TXT, MD, JSON, code files — read as UTF-8
        return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // --- Chunking: paragraph-aware with overlap ---
    private List<String> chunkText(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> chunks = new ArrayList<>();
        // Split by double newline (paragraphs)
        String[] paragraphs = text.split("\n\n+");
        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            para = para.trim();
            if (para.isBlank()) continue;
            if (current.length() + para.length() + 2 > CHUNK_SIZE && current.length() > 0) {
                chunks.add(current.toString().trim());
                // Overlap: keep last CHUNK_OVERLAP chars
                String tail = current.length() > CHUNK_OVERLAP
                    ? current.substring(current.length() - CHUNK_OVERLAP) : current.toString();
                current = new StringBuilder(tail).append("\n\n");
            }
            current.append(para).append("\n\n");
        }
        if (!current.toString().trim().isEmpty()) chunks.add(current.toString().trim());
        return chunks.isEmpty() ? List.of(text.substring(0, Math.min(text.length(), CHUNK_SIZE))) : chunks;
    }
}
