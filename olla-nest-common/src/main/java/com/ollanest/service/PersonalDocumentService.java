package com.ollanest.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Handles per-user personal document uploads with automatic RAG ingestion.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Users need to supply their own documents — contracts, research papers, slide
 * decks, spreadsheets — and have the agent answer questions about them. This
 * service accepts an upload, validates the file type and size, stores the raw
 * bytes in an owner-scoped directory under {@code data/personal_uploads/}, and
 * then extracts plain text from the document so it can be ingested into the RAG
 * vector store for retrieval during agent conversations.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>All Office formats (DOCX, PPTX, XLSX) are parsed with Apache POI; PDFs
 * with PDFBox; everything else is treated as UTF-8 plain text. This mirrors the
 * MarkItDown approach used by Microsoft's research team.</li>
 * <li>The 25 MB upload cap is enforced before any file I/O so the JVM heap is
 * never filled by a malicious upload.</li>
 * <li>Only a hard-coded allow-list of file extensions is accepted; unknown
 * types are rejected at the extension check.</li>
 * <li>The stored filename is prefixed with a random 8-char token to avoid
 * collisions and directory-traversal attacks.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced as part of the personal productivity
 * expansion</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class PersonalDocumentService {

	private static final Logger log = LoggerFactory.getLogger(PersonalDocumentService.class);

	/**
	 * Maximum permitted upload size; files exceeding this are rejected before any
	 * I/O.
	 */
	private static final long MAX_UPLOAD_BYTES = 25 * 1024 * 1024; // 25 MB

	/**
	 * Root directory for personal upload storage, configurable via
	 * {@code app.data-dir}.
	 */
	@Value("${app.data-dir:./data}")
	private String dataDir;

	/** RAG ingestion service that vectorises extracted text for retrieval. */
	private final RagService ragService;

	/** JDBC template (reserved for future metadata persistence). */
	@SuppressWarnings("unused")
	private final JdbcTemplate db;

	/**
	 * Constructor-injects RAG and persistence dependencies.
	 *
	 * @param ragService the RAG ingestion service for vectorising extracted text
	 * @param db         the JDBC template
	 * @since v2026.2.1
	 */
	public PersonalDocumentService(RagService ragService, JdbcTemplate db) {
		this.ragService = ragService;
		this.db = db;
	}

	/**
	 * Uploads a personal document, stores it on disk, and ingests its text into the
	 * RAG vector store.
	 *
	 * <p>
	 * Steps performed:
	 * <ol>
	 * <li>Validates file size against the 25 MB cap.</li>
	 * <li>Validates the file extension against the allow-list.</li>
	 * <li>Writes the raw bytes to {@code data/personal_uploads/{owner}/}.</li>
	 * <li>Extracts plain text via {@link #extractText}.</li>
	 * <li>Ingests the extracted text into the RAG store scoped to
	 * {@code "personal:{owner}"}.</li>
	 * </ol>
	 *
	 * @param owner            the user ID who is uploading the document
	 * @param bytes            the raw file bytes
	 * @param originalFilename the original filename (used to determine format and
	 *                         for storage)
	 * @return a map with {@code filename}, {@code stored_path}, {@code size},
	 *         {@code rag_doc_id}, and {@code extracted_chars}
	 * @throws IOException              if file storage fails
	 * @throws IllegalArgumentException if the file is too large or the extension is
	 *                                  unsupported
	 * @since v2026.2.1
	 */
	public Map<String, Object> upload(String owner, byte[] bytes, String originalFilename) throws IOException {
		if (bytes.length > MAX_UPLOAD_BYTES) {
			throw new IllegalArgumentException("File too large (max 25 MB): " + bytes.length + " bytes");
		}

		// Validate MIME type by extension
		String lower = originalFilename.toLowerCase();
		String allowedExtensions = "pdf,docx,pptx,xlsx,txt,md,csv,json,js,ts,py,java,html,css,xml,yaml,yml,sh,rb,go,rs";
		String ext = lower.contains(".") ? lower.substring(lower.lastIndexOf('.') + 1) : "";
		if (!Arrays.asList(allowedExtensions.split(",")).contains(ext)) {
			throw new IllegalArgumentException("File type not supported: ." + ext);
		}

		// Store file
		Path ownerDir = Path.of(dataDir, "personal_uploads", sanitizeOwner(owner));
		Files.createDirectories(ownerDir);
		String storedFilename = UUID.randomUUID().toString().substring(0, 8) + "_"
				+ originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
		Path dest = ownerDir.resolve(storedFilename);
		Files.write(dest, bytes);

		// Extract text and ingest into RAG
		String text = extractText(bytes, originalFilename);
		String ragDocId = null;
		if (text != null && !text.isBlank()) {
			ragDocId = ragService.ingestText(text, originalFilename, "personal:" + owner);
		}

		return Map.of("filename", originalFilename, "stored_path", dest.toString(), "size", bytes.length, "rag_doc_id",
				ragDocId != null ? ragDocId : "", "extracted_chars", text != null ? text.length() : 0);
	}

	/**
	 * Extracts plain text from a document byte array using format-specific parsers.
	 *
	 * <p>
	 * Format dispatch: {@code .pdf} → PDFBox; {@code .docx} → Apache POI XWPF;
	 * {@code .pptx} → Apache POI XSLF; {@code .xlsx} → Apache POI WorkbookFactory;
	 * all other extensions → UTF-8 plain text.
	 *
	 * @param bytes    the raw document bytes
	 * @param filename the original filename, used to determine the format by
	 *                 extension
	 * @return extracted text, or {@code null} if extraction fails
	 * @since v2026.2.1
	 */
	public String extractText(byte[] bytes, String filename) {
		String lower = filename.toLowerCase();
		try {
			if (lower.endsWith(".pdf"))
				return extractPdf(bytes);
			if (lower.endsWith(".docx"))
				return extractDocx(bytes);
			if (lower.endsWith(".pptx"))
				return extractPptx(bytes);
			if (lower.endsWith(".xlsx"))
				return extractXlsx(bytes);
			// All other formats: treat as plain text
			return new String(bytes, "UTF-8");
		} catch (Exception e) {
			log.warn("[personal-docs] Text extraction failed for {}: {}", filename, e.getMessage());
			return null;
		}
	}

	private String extractPdf(byte[] bytes) throws Exception {
		try (PDDocument doc = Loader.loadPDF(bytes)) {
			return new PDFTextStripper().getText(doc);
		}
	}

	private String extractDocx(byte[] bytes) throws Exception {
		try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
				XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
			return extractor.getText();
		}
	}

	private String extractPptx(byte[] bytes) throws Exception {
		try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes));
				SlideShowExtractor<?, ?> extractor = new SlideShowExtractor<>(ppt)) {
			extractor.setSlidesByDefault(true);
			extractor.setNotesByDefault(true);
			return extractor.getText();
		}
	}

	private String extractXlsx(byte[] bytes) throws Exception {
		StringBuilder sb = new StringBuilder();
		try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
			for (Sheet sheet : workbook) {
				sb.append("## ").append(sheet.getSheetName()).append("\n");
				for (Row row : sheet) {
					List<String> cells = new ArrayList<>();
					for (Cell cell : row) {
						cells.add(cell.toString());
					}
					sb.append(String.join("\t", cells)).append("\n");
				}
				sb.append("\n");
			}
		}
		return sb.toString();
	}

	private String sanitizeOwner(String owner) {
		return owner.replaceAll("[^a-zA-Z0-9_-]", "_").substring(0, Math.min(owner.length(), 80));
	}
}
