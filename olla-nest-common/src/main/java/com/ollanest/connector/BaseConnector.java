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
 * Abstract foundation for every data-source connector in Olla Nest.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest supports 20+ external data sources (GitHub, Slack, Notion, Google
 * Drive, Jira, etc.). Each connector must: authenticate against its provider's
 * API, fetch documents, deduplicate against what was already ingested, and push
 * new or changed content into the RAG store. {@code BaseConnector} centralises
 * every piece of that pipeline that is identical across all connectors — shared
 * HTTP helpers, SHA-256 content-hash deduplication, and RAG ingestion wiring —
 * so that concrete subclasses only need to implement the provider-specific
 * fetching logic.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Dependencies ({@link JdbcTemplate}, {@link RagService},
 * {@link CryptoService}) are injected via
 * {@link #setDependencies(JdbcTemplate, RagService, CryptoService)} rather than
 * a constructor so that {@link ConnectorRegistry} can register subclass
 * instances without requiring Spring-managed constructor injection.</li>
 * <li>Content-hash deduplication: before ingesting a document,
 * {@link #ingestDocument} computes a SHA-256 hash of the content and checks
 * {@code connector_documents}. If the hash matches the stored hash the document
 * is skipped (no RAG update, no DB write), keeping sync costs proportional to
 * what actually changed.</li>
 * <li>Shared HTTP client uses a 15-second connection timeout; per-request
 * response timeouts are set at 30 seconds in {@link #httpGet} and
 * {@link #httpPost}.</li>
 * <li>The {@link SyncResult} record aggregates counts for the scheduler's audit
 * log.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.4</b> — initial creation; 20-connector framework; SHA-256
 * dedup; RAG ingestion wiring; {@link SyncResult} record with factory
 * methods.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.4
 * @version v2026.1.4
 * @see ConnectorRegistry
 * @see ConnectorSyncScheduler
 */
public abstract class BaseConnector {

	/**
	 * SLF4J logger — uses the concrete subclass name so log lines identify the
	 * connector type.
	 */
	protected final Logger log = LoggerFactory.getLogger(getClass());

	/**
	 * Shared Jackson mapper for serialising request bodies and deserialising API
	 * responses.
	 */
	protected final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Shared HTTP client with a 15-second connection timeout. Response timeouts are
	 * configured per-request in {@link #httpGet} and {@link #httpPost}.
	 */
	protected final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	/**
	 * JDBC template for reading/writing {@code connector_documents} and settings.
	 */
	protected JdbcTemplate db;

	/** RAG service used to ingest new documents and delete stale ones. */
	protected RagService ragService;

	/**
	 * Crypto service used by subclasses to decrypt stored connector credentials.
	 */
	protected CryptoService cryptoService;

	// -------------------------------------------------------------------------
	// Dependency injection
	// -------------------------------------------------------------------------

	/**
	 * Injects shared infrastructure dependencies into this connector instance.
	 *
	 * <p>
	 * Called by {@link ConnectorRegistry} immediately after instantiation so that
	 * concrete connectors can use the database, RAG service, and crypto service
	 * without requiring Spring constructor injection.
	 *
	 * @param db            JDBC template for {@code connector_documents} access
	 * @param ragService    service for ingesting and deleting RAG documents
	 * @param cryptoService service for decrypting stored API credentials
	 * @since v2026.1.4
	 */
	public void setDependencies(JdbcTemplate db, RagService ragService, CryptoService cryptoService) {
		this.db = db;
		this.ragService = ragService;
		this.cryptoService = cryptoService;
	}

	// -------------------------------------------------------------------------
	// Abstract contract
	// -------------------------------------------------------------------------

	/**
	 * Returns the stable type key that identifies this connector in the database.
	 *
	 * <p>
	 * The returned string must match the {@code type} column value stored in
	 * {@code connector_configs} (e.g. {@code "github"}, {@code "slack"},
	 * {@code "notion"}). Used by {@link ConnectorRegistry} to dispatch sync
	 * requests to the correct implementation.
	 *
	 * @return the connector type key; never null or blank
	 * @since v2026.1.4
	 */
	public abstract String getType();

	/**
	 * Performs a full incremental sync of the connector's data source.
	 *
	 * <p>
	 * Implementations must fetch all relevant documents from the remote API, call
	 * {@link #ingestDocument} for each, and return a {@link SyncResult} summarising
	 * how many documents were synced, skipped (unchanged), or deleted. SHA-256
	 * deduplication in {@link #ingestDocument} ensures only changed documents incur
	 * a RAG re-ingestion cost.
	 *
	 * @param config      the {@code connector_configs} database row (non-secret
	 *                    fields such as org name, workspace ID, etc.)
	 * @param credentials the decrypted credentials JSON string (API token, OAuth
	 *                    access token, username/password, etc.)
	 * @return a {@link SyncResult} with counts and an optional error message
	 * @since v2026.1.4
	 */
	public abstract SyncResult sync(Map<String, Object> config, String credentials);

	/**
	 * Performs a lightweight connectivity test without ingesting any data.
	 *
	 * <p>
	 * Implementations should make the cheapest possible API call (e.g. fetch the
	 * authenticated user profile) to verify that the supplied credentials are valid
	 * and the remote endpoint is reachable. Must not modify the RAG store or
	 * database.
	 *
	 * @param config      non-secret connector configuration (org, workspace, etc.)
	 * @param credentials decrypted credentials JSON string
	 * @return {@code true} if the credentials are valid and the remote is
	 *         reachable; {@code false} otherwise
	 * @since v2026.1.4
	 */
	public abstract boolean testConnection(Map<String, Object> config, String credentials);

	// -------------------------------------------------------------------------
	// Shared HTTP helpers
	// -------------------------------------------------------------------------

	/**
	 * Sends an authenticated HTTP GET request and returns the parsed JSON response
	 * body.
	 *
	 * <p>
	 * Sets a 30-second response timeout. Throws a {@link RuntimeException} if the
	 * HTTP status code is outside the 2xx range, including the URL and status code
	 * in the message for easy diagnostics.
	 *
	 * @param url        the fully-qualified request URL; must be a valid
	 *                   {@link URI}
	 * @param authHeader the value of the {@code Authorization} header (e.g.
	 *                   {@code "Bearer ghp_tokenValue"} or {@code "Basic base64"})
	 * @return the parsed JSON response body as a {@link JsonNode}
	 * @throws RuntimeException if the response status is not 2xx
	 * @throws Exception        on network failure or JSON parse error
	 * @since v2026.1.4
	 */
	protected JsonNode httpGet(String url, String authHeader) throws Exception {
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", authHeader)
				.header("Accept", "application/json").timeout(Duration.ofSeconds(30)).GET().build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() < 200 || resp.statusCode() >= 300)
			throw new RuntimeException("HTTP " + resp.statusCode() + " from " + url);
		return mapper.readTree(resp.body());
	}

	/**
	 * Sends an authenticated HTTP POST request with a JSON body and returns the
	 * parsed JSON response.
	 *
	 * <p>
	 * Serialises {@code body} to JSON using the shared {@link ObjectMapper}, sets
	 * {@code Content-Type: application/json}, and applies a 30-second response
	 * timeout. Throws a {@link RuntimeException} if the HTTP status code is outside
	 * the 2xx range.
	 *
	 * @param url        the fully-qualified request URL
	 * @param authHeader the value of the {@code Authorization} header
	 * @param body       the request body as a {@link Map}; serialised to JSON
	 *                   before sending
	 * @return the parsed JSON response body as a {@link JsonNode}
	 * @throws RuntimeException if the response status is not 2xx
	 * @throws Exception        on network failure, JSON serialisation, or parse
	 *                          error
	 * @since v2026.1.4
	 */
	protected JsonNode httpPost(String url, String authHeader, Map<String, Object> body) throws Exception {
		String json = mapper.writeValueAsString(body);
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", authHeader)
				.header("Content-Type", "application/json").header("Accept", "application/json")
				.timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(json)).build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() < 200 || resp.statusCode() >= 300)
			throw new RuntimeException("HTTP " + resp.statusCode() + " from " + url);
		return mapper.readTree(resp.body());
	}

	// -------------------------------------------------------------------------
	// RAG ingestion with SHA-256 deduplication
	// -------------------------------------------------------------------------

	/**
	 * Ingests or updates a single document from this connector into the RAG store,
	 * skipping the document if its content is unchanged since the last sync.
	 *
	 * <p>
	 * Deduplication algorithm:
	 * <ol>
	 * <li>Compute SHA-256 of {@code content}.</li>
	 * <li>Look up the existing {@code connector_documents} row for
	 * ({@code connectorId}, {@code externalId}).</li>
	 * <li>If found and the stored hash matches, return {@code false} (no
	 * change).</li>
	 * <li>If found with a different hash, delete the old RAG document and
	 * re-ingest.</li>
	 * <li>If not found, ingest as a new document.</li>
	 * <li>Upsert the {@code connector_documents} row with the new hash and RAG doc
	 * ID.</li>
	 * </ol>
	 *
	 * @param connectorId the {@code connector_configs.id} value; used as the RAG
	 *                    source tag
	 * @param externalId  the provider-assigned identifier (e.g. GitHub issue
	 *                    number, Notion page UUID); must be unique within the
	 *                    connector
	 * @param title       human-readable title for the document (used in RAG
	 *                    retrieval)
	 * @param url         the canonical URL of the document at the provider; may be
	 *                    null
	 * @param content     the full plain-text content to embed; must not be null
	 * @return {@code true} if the document was newly ingested or updated;
	 *         {@code false} if it was identical to the stored version
	 * @since v2026.1.4
	 */
	protected boolean ingestDocument(String connectorId, String externalId, String title, String url, String content) {
		String hash = sha256(content);
		List<Map<String, Object>> existing = db
				.queryForList("SELECT id, content_hash, rag_doc_id FROM connector_documents "
						+ "WHERE connector_id = ? AND external_id = ?", connectorId, externalId);

		if (!existing.isEmpty() && hash.equals(existing.get(0).get("content_hash"))) {
			return false; // unchanged — skip
		}

		// Delete stale RAG document if present
		if (!existing.isEmpty() && existing.get(0).get("rag_doc_id") != null) {
			try {
				ragService.deleteDocument((String) existing.get(0).get("rag_doc_id"));
			} catch (Exception ignore) {
			}
		}

		// Ingest updated or new document into RAG
		String docId = ragService.ingestText(content, title, connectorId);

		String now = Instant.now().toString();
		if (existing.isEmpty()) {
			String cdId = "cd-" + Long.toString(System.currentTimeMillis(), 36);
			db.update("INSERT INTO connector_documents "
					+ "(id, connector_id, external_id, title, url, content_hash, rag_doc_id, synced_at) "
					+ "VALUES (?,?,?,?,?,?,?,?)", cdId, connectorId, externalId, title, url, hash, docId, now);
		} else {
			db.update(
					"UPDATE connector_documents " + "SET title=?, url=?, content_hash=?, rag_doc_id=?, synced_at=? "
							+ "WHERE connector_id=? AND external_id=?",
					title, url, hash, docId, now, connectorId, externalId);
		}
		return true;
	}

	// -------------------------------------------------------------------------
	// Credential helpers
	// -------------------------------------------------------------------------

	/**
	 * Parses a JSON credentials string into a {@link Map}.
	 *
	 * <p>
	 * Used by subclasses to extract individual credential fields (e.g.
	 * {@code token}, {@code clientId}, {@code clientSecret}) from the decrypted
	 * credentials JSON stored in the database. Returns an empty map on parse
	 * failure so callers can safely call {@link #credStr} without a null check.
	 *
	 * @param credsJson the decrypted credentials JSON string; may be null or
	 *                  malformed
	 * @return a {@link Map} of credential key-value pairs; empty map on error
	 * @since v2026.1.4
	 */
	protected Map<String, Object> parseCredentials(String credsJson) {
		try {
			return mapper.readValue(credsJson,
					mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
		} catch (Exception e) {
			return Map.of();
		}
	}

	/**
	 * Safely extracts a string credential value from a parsed credentials map.
	 *
	 * <p>
	 * Returns an empty string rather than null when the key is absent, simplifying
	 * null checks in subclass implementations.
	 *
	 * @param creds the credentials map returned by {@link #parseCredentials}
	 * @param key   the credential field name (e.g. {@code "token"},
	 *              {@code "apiKey"})
	 * @return the credential value as a string; {@code ""} if the key is absent
	 * @since v2026.1.4
	 */
	protected String credStr(Map<String, Object> creds, String key) {
		Object v = creds.get(key);
		return v != null ? v.toString() : "";
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	/**
	 * Computes the SHA-256 hex digest of a UTF-8 string.
	 *
	 * <p>
	 * Used by {@link #ingestDocument} to detect content changes. Returns an empty
	 * string on the (extremely unlikely) event that the SHA-256 algorithm is
	 * unavailable on the JVM.
	 *
	 * @param text the input string; must not be null
	 * @return the lowercase hex SHA-256 digest, or {@code ""} on algorithm failure
	 * @since v2026.1.4
	 */
	private static String sha256(String text) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(bytes);
		} catch (Exception e) {
			return "";
		}
	}

	// -------------------------------------------------------------------------
	// SyncResult record
	// -------------------------------------------------------------------------

	/**
	 * Immutable summary of a connector sync operation.
	 *
	 * <p>
	 * Returned by every {@link #sync} implementation and stored in
	 * {@code connector_sync_log} by the {@link ConnectorSyncScheduler}. The
	 * {@link #isOk()} method allows callers to distinguish success from partial or
	 * total failure without inspecting the error string.
	 *
	 * @param synced  number of documents that were newly ingested or updated
	 * @param skipped number of documents skipped because their content was
	 *                unchanged
	 * @param deleted number of documents removed from the RAG store (currently
	 *                unused; reserved for future hard-delete support)
	 * @param error   human-readable error message if the sync failed; {@code null}
	 *                on success
	 * @since v2026.1.4
	 */
	public record SyncResult(int synced, int skipped, int deleted, String error) {

		/**
		 * Creates a successful {@link SyncResult} with no errors and zero deleted
		 * count.
		 *
		 * @param synced  number of documents ingested or updated
		 * @param skipped number of documents skipped (unchanged)
		 * @return a successful {@link SyncResult}
		 * @since v2026.1.4
		 */
		public static SyncResult ok(int synced, int skipped) {
			return new SyncResult(synced, skipped, 0, null);
		}

		/**
		 * Creates a failed {@link SyncResult} with all counts set to zero.
		 *
		 * @param msg the error message describing why the sync failed; must not be null
		 * @return a failed {@link SyncResult} with {@code error} populated
		 * @since v2026.1.4
		 */
		public static SyncResult error(String msg) {
			return new SyncResult(0, 0, 0, msg);
		}

		/**
		 * Returns {@code true} if the sync completed without an error.
		 *
		 * @return {@code true} when {@code error} is {@code null}; {@code false}
		 *         otherwise
		 * @since v2026.1.4
		 */
		public boolean isOk() {
			return error == null;
		}
	}
}
