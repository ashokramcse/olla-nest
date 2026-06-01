package com.ollanest.service;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Retrieval-Augmented Generation (RAG) pipeline for document ingestion and
 * semantic retrieval.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest allows users to upload documents (PDFs, Markdown, plain text, code)
 * that are then made available as grounded context for LLM conversations. This
 * service owns the entire lifecycle: extracting plain text from binary file
 * formats, splitting that text into overlapping chunks, computing vector
 * embeddings via {@link EmbeddingService}, persisting chunks to the
 * {@code rag_chunks} table, and — at query time — retrieving the most relevant
 * chunks using cosine similarity (or a keyword-overlap fallback when embeddings
 * are unavailable).
 *
 * <h3>Design notes</h3>
 * <p>
 * Chunks are paragraph-aware: the text is first split on double newlines and
 * chunks grow until they exceed {@value #CHUNK_SIZE} characters, at which point
 * the last {@value #CHUNK_OVERLAP} characters are carried over as overlap to
 * preserve context across boundaries.
 * <p>
 * Retrieval uses an in-process cosine similarity scan over all stored chunk
 * embeddings for the requested scope (global + user-scoped documents). This is
 * intentionally simple — no external vector database is required — and works
 * well for typical enterprise document libraries of up to a few thousand
 * chunks. The similarity threshold is {@value #SIMILARITY_THRESHOLD}; chunks
 * scoring below this value are discarded.
 * <p>
 * PDF extraction uses Apache PDFBox 3.x via the {@code Loader.loadPDF(byte[])}
 * API.
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.0</b> — initial Java Spring Boot migration</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.0
 */
@Service
public class RagService {

	/** SLF4J logger for this class. */
	private static final Logger log = LoggerFactory.getLogger(RagService.class);

	/** Maximum number of characters per chunk before a new chunk is started. */
	private static final int CHUNK_SIZE = 1800;

	/**
	 * Number of characters carried from the end of the previous chunk into the
	 * next.
	 */
	private static final int CHUNK_OVERLAP = 200;

	/** Default number of top-scoring chunks returned by {@link #retrieve}. */
	private static final int TOP_K = 5;

	/**
	 * Minimum cosine (or keyword) similarity score for a chunk to be included in
	 * results.
	 */
	private static final double SIMILARITY_THRESHOLD = 0.30;

	/** Spring JDBC template for all database access. */
	private final JdbcTemplate db;

	/** Service that computes and compares vector embeddings. */
	private final EmbeddingService embeddingService;

	/**
	 * Constructs the service with its required collaborators.
	 *
	 * @param db               Spring JDBC template bound to the application's data
	 *                         source
	 * @param embeddingService embedding computation and similarity service
	 * @since v2026.1.0
	 */
	public RagService(JdbcTemplate db, EmbeddingService embeddingService) {
		this.db = db;
		this.embeddingService = embeddingService;
	}

	/**
	 * Ingests a document: extracts text, splits into chunks, embeds each chunk, and
	 * persists both the document record and its chunks to the database.
	 *
	 * <p>
	 * Text extraction is type-aware: PDF files are processed with Apache PDFBox;
	 * all other types (plain text, Markdown, JSON, source code) are read as UTF-8.
	 * After extraction the text is chunked with paragraph-aware overlap, each chunk
	 * is embedded via {@link EmbeddingService#embed(String)}, and the results are
	 * persisted in {@code rag_documents} and {@code rag_chunks}.
	 *
	 * @param docId       unique identifier for the document; used as the primary
	 *                    key
	 * @param name        original filename or display name
	 * @param type        MIME type string (e.g. {@code "application/pdf"} or
	 *                    {@code "text/plain"})
	 * @param size        file size in bytes, stored for display purposes
	 * @param inputStream raw byte stream of the document; will be fully consumed
	 *                    and must not be {@code null}
	 * @param uploadedBy  user ID or identifier of the uploader
	 * @param scope       visibility scope; {@code "global"} makes the document
	 *                    available to all users, any other value restricts it to
	 *                    that specific user or connector
	 * @return a map with keys {@code id}, {@code name}, {@code chunks} (total
	 *         chunks), and {@code embedded} (number of chunks that received an
	 *         embedding)
	 * @throws RuntimeException if text extraction fails
	 * @since v2026.1.0
	 */
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

		db.update(
				"INSERT INTO rag_documents (id, name, type, size, chunk_count, uploaded_by, scope, created_at) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
				docId, name, type, size, chunks.size(), uploadedBy, scope, now);

		int embedded = 0;
		for (int i = 0; i < chunks.size(); i++) {
			String chunk = chunks.get(i);
			String chunkId = docId + "-c" + i;
			List<Double> vec = embeddingService.embed(chunk);
			String embJson = vec.isEmpty() ? null : embeddingService.vectorToJson(vec);
			if (!vec.isEmpty())
				embedded++;
			db.update("INSERT INTO rag_chunks (id, document_id, chunk_index, content, embedding_json, created_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?)", chunkId, docId, i, chunk, embJson, now);
		}

		log.info("[rag] Ingested '{}': {} chunks, {} embedded", name, chunks.size(), embedded);
		return Map.of("id", docId, "name", name, "chunks", chunks.size(), "embedded", embedded);
	}

	/**
	 * Retrieves the top-K most relevant document chunks for a query string.
	 *
	 * <p>
	 * First embeds {@code query} using {@link EmbeddingService#embed(String)}. Then
	 * loads all chunks belonging to global documents plus any documents scoped to
	 * {@code scope} (when {@code scope} is not {@code "global"}). Each chunk is
	 * scored: chunks with an embedding use cosine similarity; chunks without one
	 * use a keyword-overlap fallback. Results are filtered by
	 * {@link #SIMILARITY_THRESHOLD}, sorted descending by score, and limited to
	 * {@code topK} entries.
	 *
	 * @param query the search query; returns an empty list immediately if blank
	 * @param scope the user or connector scope to include in addition to global
	 *              documents; pass {@code "global"} or {@code null} to retrieve
	 *              only global documents
	 * @param topK  maximum number of chunks to return; values &le; 0 default to
	 *              {@value #TOP_K}
	 * @return ordered list of result maps, each containing {@code content},
	 *         {@code docName}, {@code docId}, and {@code score} (rounded to 2 d.p.)
	 * @since v2026.1.0
	 */
	public List<Map<String, Object>> retrieve(String query, String scope, int topK) {
		if (query == null || query.isBlank())
			return List.of();
		List<Double> queryVec = embeddingService.embed(query);

		// Load all chunks (with embeddings) for the given scope
		String sql = "SELECT rc.id, rc.content, rc.embedding_json, rd.name as doc_name, rd.id as doc_id "
				+ "FROM rag_chunks rc JOIN rag_documents rd ON rc.document_id = rd.id " + "WHERE rd.scope = 'global'"
				+ (scope != null && !scope.equals("global") ? " OR rd.scope = ?" : "");
		List<Map<String, Object>> rows = scope != null && !scope.equals("global") ? db.queryForList(sql, scope)
				: db.queryForList(sql.replace(" OR rd.scope = ?", ""));

		// Score each chunk
		List<double[]> scores = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			Map<String, Object> row = rows.get(i);
			String embJson = (String) row.get("embedding_json");
			double score;
			if (embJson != null && !embJson.isBlank()) {
				List<Double> chunkVec = embeddingService.jsonToVector(embJson);
				score = queryVec.isEmpty() ? embeddingService.keywordSimilarity(query, (String) row.get("content"))
						: embeddingService.cosineSimilarity(queryVec, chunkVec);
			} else {
				score = embeddingService.keywordSimilarity(query, (String) row.get("content"));
			}
			scores.add(new double[] { i, score });
		}

		int k = topK > 0 ? topK : TOP_K;
		return scores.stream().filter(s -> s[1] >= SIMILARITY_THRESHOLD).sorted((a, b) -> Double.compare(b[1], a[1]))
				.limit(k).map(s -> {
					Map<String, Object> row = rows.get((int) s[0]);
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("content", row.get("content"));
					result.put("docName", row.get("doc_name"));
					result.put("docId", row.get("doc_id"));
					result.put("score", Math.round(s[1] * 100.0) / 100.0);
					return result;
				}).collect(Collectors.toList());
	}

	/**
	 * Builds a formatted RAG context string suitable for injection into an LLM
	 * system prompt.
	 *
	 * <p>
	 * Calls {@link #retrieve(String, String, int)} with the user's ID as the scope,
	 * then formats the top chunks as numbered {@code [Source N]} blocks separated
	 * by horizontal rules. Returns {@code null} when no relevant chunks are found
	 * so callers can skip prompt injection entirely.
	 *
	 * @param query  the user's message or research sub-question used to retrieve
	 *               context
	 * @param userId the authenticated user's ID, used to scope retrieval to their
	 *               documents
	 * @return a formatted multi-line context string, or {@code null} if empty
	 * @since v2026.1.0
	 */
	public String buildRagContext(String query, String userId) {
		List<Map<String, Object>> chunks = retrieve(query, userId, TOP_K);
		if (chunks.isEmpty())
			return null;
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

	/**
	 * Returns all ingested documents ordered by creation date descending.
	 *
	 * <p>
	 * Each map in the returned list contains {@code id}, {@code name},
	 * {@code type}, {@code size}, {@code chunk_count}, {@code uploaded_by},
	 * {@code scope}, and {@code created_at}.
	 *
	 * @return list of document metadata maps; empty list when no documents exist
	 * @since v2026.1.0
	 */
	public List<Map<String, Object>> listDocuments() {
		return db.queryForList("SELECT id, name, type, size, chunk_count, uploaded_by, scope, created_at "
				+ "FROM rag_documents ORDER BY created_at DESC");
	}

	/**
	 * Ingests a plain-text string directly (used by connector integrations).
	 *
	 * <p>
	 * Creates a {@code rag_documents} record with {@code type = "text/plain"},
	 * {@code uploaded_by = "connector"}, and {@code scope = connectorId}, then
	 * splits, embeds, and persists the chunks exactly as {@link #ingestDocument}
	 * does.
	 *
	 * @param content the full text to ingest; must not be {@code null}
	 * @param name    display name for the created document record
	 * @param scope   connector ID or scope tag for access control
	 * @return the generated document ID (e.g. {@code doc-lx7k3a-p2q8r5})
	 * @since v2026.1.0
	 */
	/** SecureRandom for document ID generation — used to prevent ID collision and guessing. */
	private static final java.security.SecureRandom INGEST_RNG = new java.security.SecureRandom();

	public String ingestText(String content, String name, String scope) {
		// MED-5 / LOW-7 FIX: Use SecureRandom instead of Math.random() for document ID.
		String docId = "doc-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ Long.toString(INGEST_RNG.nextLong() & Long.MAX_VALUE, 36);
		List<String> chunks = chunkText(content);
		String now = java.time.Instant.now().toString();
		db.update(
				"INSERT INTO rag_documents (id, name, type, size, chunk_count, uploaded_by, scope, created_at) "
						+ "VALUES (?,?,?,?,?,?,?,?)",
				docId, name, "text/plain", (long) content.length(), chunks.size(), "connector", scope, now);
		for (int i = 0; i < chunks.size(); i++) {
			String chunk = chunks.get(i);
			String chunkId = docId + "-c" + i;
			List<Double> vec = embeddingService.embed(chunk);
			String embJson = vec.isEmpty() ? null : embeddingService.vectorToJson(vec);
			db.update("INSERT INTO rag_chunks (id, document_id, chunk_index, content, embedding_json, created_at) "
					+ "VALUES (?,?,?,?,?,?)", chunkId, docId, i, chunk, embJson, now);
		}
		return docId;
	}

	/**
	 * Permanently deletes a document and all of its associated chunks.
	 *
	 * <p>
	 * Chunks are deleted first to respect the foreign-key constraint between
	 * {@code rag_chunks} and {@code rag_documents}.
	 *
	 * @param docId the document ID to delete
	 * @since v2026.1.0
	 */
	public void deleteDocument(String docId) {
		db.update("DELETE FROM rag_chunks WHERE document_id = ?", docId);
		db.update("DELETE FROM rag_documents WHERE id = ?", docId);
	}

	// ── Private helpers ─────────────────────────────────────────────────────

	/**
	 * Extracts plain text from an uploaded file based on its name and MIME type.
	 *
	 * <p>
	 * PDF files (detected by {@code .pdf} extension or {@code application/pdf} MIME
	 * type) are processed with Apache PDFBox. All other types are decoded as UTF-8
	 * strings.
	 *
	 * @param name original filename, used for extension-based type detection
	 * @param type MIME type string; may be {@code null}
	 * @param is   byte stream of the file; fully consumed by this method
	 * @return extracted plain-text content
	 * @throws Exception if PDF parsing fails or the stream cannot be read
	 * @since v2026.1.0
	 */
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

	/**
	 * Splits text into overlapping chunks using paragraph boundaries.
	 *
	 * <p>
	 * Text is first split on sequences of two or more newlines (paragraph
	 * boundaries). Paragraphs are accumulated into the current chunk until adding
	 * the next paragraph would exceed {@link #CHUNK_SIZE} characters, at which
	 * point the chunk is finalised and the last {@link #CHUNK_OVERLAP} characters
	 * are carried over as the seed of the next chunk. This preserves context across
	 * chunk boundaries without duplicating large amounts of text.
	 *
	 * <p>
	 * If the entire algorithm produces no chunks (e.g. the text has no paragraph
	 * breaks and fits within the limit) the original text, truncated to
	 * {@link #CHUNK_SIZE}, is returned as a single-element list.
	 *
	 * @param text the full document text to chunk; returns an empty list for blank
	 *             input
	 * @return a non-empty list of chunk strings; each at most {@link #CHUNK_SIZE}
	 *         characters (plus overlap)
	 * @since v2026.1.0
	 */
	private List<String> chunkText(String text) {
		if (text == null || text.isBlank())
			return List.of();
		List<String> chunks = new ArrayList<>();
		// Split by double newline (paragraphs)
		String[] paragraphs = text.split("\n\n+");
		StringBuilder current = new StringBuilder();
		for (String para : paragraphs) {
			para = para.trim();
			if (para.isBlank())
				continue;
			if (current.length() + para.length() + 2 > CHUNK_SIZE && current.length() > 0) {
				chunks.add(current.toString().trim());
				// Overlap: keep last CHUNK_OVERLAP chars
				String tail = current.length() > CHUNK_OVERLAP ? current.substring(current.length() - CHUNK_OVERLAP)
						: current.toString();
				current = new StringBuilder(tail).append("\n\n");
			}
			current.append(para).append("\n\n");
		}
		if (!current.toString().trim().isEmpty())
			chunks.add(current.toString().trim());
		return chunks.isEmpty() ? List.of(text.substring(0, Math.min(text.length(), CHUNK_SIZE))) : chunks;
	}
}
