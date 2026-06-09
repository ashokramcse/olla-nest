package com.ollanest.connector.impl;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.ollanest.connector.BaseConnector;

/**
 * Connector implementation that synchronises content from Google Drive into the
 * Olla knowledge base.
 *
 * <h3>Why this class exists</h3> Google Drive is one of the most widely used
 * cloud document repositories. This connector bridges Drive content — both
 * native Google Docs and binary uploads (PDF, plain text) — into the Olla
 * vector store so that the AI assistant can answer questions grounded in those
 * documents.
 *
 * <h3>Credential format</h3>
 * 
 * <pre>{@code { "accessToken": "ya29..." // OAuth
 * 2.0 bearer token; must have drive.readonly scope. // Token refresh is handled
 * externally (SSO or a separate refresh flow). } }</pre>
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Google Docs are never stored natively on Drive; they are exported
 * on-the-fly via the {@code
 * /export?mimeType=text/plain} endpoint.</li>
 * <li>PDF files are downloaded as raw bytes and converted to plain text using
 * Apache PDFBox.</li>
 * <li>Plain-text files are downloaded as raw bytes and decoded as UTF-8.</li>
 * <li>Failures on individual files are swallowed and logged as warnings so that
 * a single corrupt or permission-denied file does not abort the entire
 * sync.</li>
 * <li>The list query is capped at 50 files ordered by {@code
 * modifiedTime desc}; pagination is not yet implemented.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.4 — initial creation</li>
 * </ul>
 *
 * @author Ashok Ram @since v2026.1.4 @version v2026.1.4 @see
 *         com.ollanest.connector.BaseConnector
 */
@Component
public class GoogleDriveConnector extends BaseConnector {

	/** Base URL for the Google Drive v3 REST API. */
	private static final String DRIVE_BASE = "https://www.googleapis.com/drive/v3";

	/** Base URL used when constructing per-file export / download URLs. */
	private static final String EXPORT_BASE = "https://www.googleapis.com/drive/v3/files";

	/**
	 * Returns the connector type identifier used to look up this bean at runtime.
	 *
	 * @return {@code "gdrive"}
	 * @since v2026.1.4
	 */
	@Override
	public String getType() {
		return "gdrive";
	}

	/**
	 * Synchronises Google Drive files into the Olla knowledge base.
	 *
	 * <p>
	 * The method lists up to 50 recently modified files whose MIME type is one of:
	 * <ul>
	 * <li>{@code application/vnd.google-apps.document} — exported as plain text via
	 * the Drive export endpoint.</li>
	 * <li>{@code application/pdf} — downloaded as bytes and converted via
	 * PDFBox.</li>
	 * <li>{@code text/plain} — downloaded as bytes and decoded as UTF-8.</li>
	 * </ul>
	 * Each file is passed to {@link BaseConnector#ingestDocument} which handles
	 * deduplication, chunking, and vector-store upsert. Failures on individual
	 * files are logged and skipped.
	 *
	 * @param config      connector configuration map; must contain {@code "id"}
	 *                    (connector ID).
	 * @param credentials JSON string of the form
	 *                    {@code {"accessToken":"ya29..."} }.
	 * @return a {@link SyncResult} that is either {@link SyncResult#ok(int, int)}
	 *         with the counts of synced and skipped files, or
	 *         {@link SyncResult#error(String)} if the file listing itself fails.
	 * @since v2026.1.4
	 */
	@Override
	public SyncResult sync(Map<String, Object> config, String credentials) {
		Map<String, Object> creds = parseCredentials(credentials);
		String token = credStr(creds, "accessToken");
		String auth = "Bearer " + token;
		String connId = (String) config.get("id");
		int synced = 0, skipped = 0;

		try {
			// List recent files — Docs, PDFs, plain text
			String query = "mimeType='application/vnd.google-apps.document' or mimeType='application/pdf' or mimeType='text/plain'";
			JsonNode files = httpGet(
					DRIVE_BASE + "/files?q=" + URLEncoder.encode(query, "UTF-8")
							+ "&fields=files(id,name,webViewLink,mimeType)&pageSize=50&orderBy=modifiedTime+desc",
					auth);

			for (JsonNode file : files.path("files")) {
				String fileId = file.path("id").asText();
				String name = file.path("name").asText();
				String fileUrl = file.path("webViewLink").asText();
				String mime = file.path("mimeType").asText();
				String content;

				try {
					if ("application/vnd.google-apps.document".equals(mime)) {
						// Export Google Doc as plain text
						URI uri = URI.create(EXPORT_BASE + "/" + fileId + "/export?mimeType=text/plain");
						HttpRequest req = HttpRequest.newBuilder().uri(uri).header("Authorization", auth)
								.timeout(Duration.ofSeconds(30)).GET().build();
						HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
						content = resp.body();
					} else {
						// Download as-is (PDF/TXT)
						URI uri = URI.create(EXPORT_BASE + "/" + fileId + "?alt=media");
						HttpRequest req = HttpRequest.newBuilder().uri(uri).header("Authorization", auth)
								.timeout(Duration.ofSeconds(30)).GET().build();
						HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
						content = "application/pdf".equals(mime) ? extractPdf(resp.body()) : new String(resp.body());
					}
					if (ingestDocument(connId, fileId, name, fileUrl, content))
						synced++;
					else
						skipped++;
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

	/**
	 * Validates that the supplied credentials can successfully call the Drive API.
	 *
	 * <p>
	 * The test calls {@code GET /drive/v3/about?fields=user}, which requires a
	 * valid OAuth token with at least the {@code drive.readonly} scope and returns
	 * a minimal user object.
	 *
	 * @param config      connector configuration map (not used by this
	 *                    implementation).
	 * @param credentials JSON string containing {@code "accessToken"}.
	 * @return {@code true} if the API call succeeds without throwing; {@code false}
	 *         otherwise.
	 * @since v2026.1.4
	 */
	@Override
	public boolean testConnection(Map<String, Object> config, String credentials) {
		try {
			Map<String, Object> creds = parseCredentials(credentials);
			httpGet("https://www.googleapis.com/drive/v3/about?fields=user", "Bearer " + credStr(creds, "accessToken"));
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Extracts plain text from a PDF byte array using Apache PDFBox.
	 *
	 * <p>
	 * If extraction fails for any reason (corrupt PDF, encrypted document, etc.) an
	 * empty string is returned rather than propagating the exception, ensuring that
	 * one bad PDF does not abort the entire sync.
	 *
	 * @param bytes raw bytes of the PDF file.
	 * @return extracted plain text, or an empty string if extraction fails.
	 * @since v2026.1.4
	 */
	private String extractPdf(byte[] bytes) {
		try (PDDocument doc = Loader.loadPDF(bytes)) {
			return new PDFTextStripper().getText(doc);
		} catch (Exception e) {
			return "";
		}
	}
}
