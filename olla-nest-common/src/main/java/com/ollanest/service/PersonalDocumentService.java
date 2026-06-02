package com.ollanest.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.UUID;

/**
 * Personal document uploads — per-user file storage with automatic RAG ingestion.
 *
 * Supports: PDF, DOCX, PPTX, XLSX, plain text, Markdown, CSV, code files.
 * All formats are converted to Markdown/plain text before RAG ingestion (MarkItDown approach).
 * Upload cap: 25 MB per file.
 */
@Service
public class PersonalDocumentService {

    private static final Logger log = LoggerFactory.getLogger(PersonalDocumentService.class);
    private static final long MAX_UPLOAD_BYTES = 25 * 1024 * 1024; // 25 MB

    @Value("${app.data-dir:./data}")
    private String dataDir;

    private final RagService ragService;
    private final JdbcTemplate db;

    public PersonalDocumentService(RagService ragService, JdbcTemplate db) {
        this.ragService = ragService;
        this.db = db;
    }

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
        String storedFilename = UUID.randomUUID().toString().substring(0, 8) + "_" + originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path dest = ownerDir.resolve(storedFilename);
        Files.write(dest, bytes);

        // Extract text and ingest into RAG
        String text = extractText(bytes, originalFilename);
        String ragDocId = null;
        if (text != null && !text.isBlank()) {
            ragDocId = ragService.ingestText(text, originalFilename, "personal:" + owner);
        }

        return Map.of(
                "filename", originalFilename,
                "stored_path", dest.toString(),
                "size", bytes.length,
                "rag_doc_id", ragDocId != null ? ragDocId : "",
                "extracted_chars", text != null ? text.length() : 0
        );
    }

    public String extractText(byte[] bytes, String filename) {
        String lower = filename.toLowerCase();
        try {
            if (lower.endsWith(".pdf")) return extractPdf(bytes);
            if (lower.endsWith(".docx")) return extractDocx(bytes);
            if (lower.endsWith(".pptx")) return extractPptx(bytes);
            if (lower.endsWith(".xlsx")) return extractXlsx(bytes);
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
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            return new XWPFWordExtractor(doc).getText();
        }
    }

    private String extractPptx(byte[] bytes) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            SlideShowExtractor<?,?> extractor = new SlideShowExtractor<>(ppt);
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
