package com.ollanest.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Photo and image library service with album management, EXIF extraction,
 * SHA-256 deduplication, and a canvas-style editor with draft persistence.
 *
 * <p>Images are stored on disk under {@code data/gallery/}. Metadata and album
 * assignments are persisted in {@code gallery_albums} and {@code gallery_images} tables.
 * Deduplication is per-user: re-uploading the same image returns the existing record
 * without writing a new file.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Users need a private, self-hosted photo library that does not upload their images to
 * a third-party cloud. This service stores images locally, extracts EXIF metadata for
 * filtering and display, and provides a JSON editor-draft store for the client-side
 * canvas editor.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>SHA-256 deduplication is intentionally per-owner — the same physical file can be
 *     independently uploaded by different users without cross-user visibility.</li>
 * <li>Soft delete ({@code is_active=0}) is used for images so file references remain
 *     intact for any drafts that pointed to them.</li>
 * <li>EXIF extraction is best-effort via the {@code metadata-extractor} library;
 *     non-JPEG files or stripped EXIF silently return an empty map.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced with album CRUD, image upload, EXIF extraction,
 *     deduplication, and editor draft persistence</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class GalleryService {

    private static final Logger log = LoggerFactory.getLogger(GalleryService.class);

    /** JDBC template for album, image, and draft persistence. */
    private final JdbcTemplate db;

    /** Shared Jackson mapper for EXIF and payload JSON serialisation. */
    private final ObjectMapper mapper;

    /** Root data directory injected from {@code app.data-dir}; images stored under {@code gallery/} subdirectory. */
    @Value("${app.data-dir:./data}")
    private String dataDir;

    /**
     * Constructor-injects persistence and serialisation dependencies.
     *
     * @param db     JDBC template for gallery tables
     * @param mapper shared Jackson mapper
     * @since v2026.2.1
     */
    public GalleryService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    // ── Albums ────────────────────────────────────────────────────────────────

    /**
     * Creates a new gallery album.
     *
     * @param owner the user ID
     * @param req   album fields: {@code name}, {@code description}, {@code team_id}
     * @return the created album record
     * @since v2026.2.1
     */
    public Map<String, Object> createAlbum(String owner, Map<String, Object> req) {
        String id = "alb-" + Long.toString(System.currentTimeMillis(), 36);
        String now = Instant.now().toString();
        db.update("INSERT INTO gallery_albums (id, owner, name, description, team_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                id, owner, req.getOrDefault("name", "Album"), req.get("description"), req.get("team_id"), now, now);
        return getAlbum(id, owner);
    }

    /**
     * Returns all albums for the given owner with an image count, ordered by name.
     *
     * @param owner the user ID
     * @return list of album rows including {@code image_count}; never null
     * @since v2026.2.1
     */
    public List<Map<String, Object>> listAlbums(String owner) {
        return db.queryForList("SELECT a.*, COUNT(i.id) as image_count FROM gallery_albums a LEFT JOIN gallery_images i ON i.album_id=a.id WHERE a.owner=? GROUP BY a.id ORDER BY a.name ASC", owner);
    }

    /**
     * Returns the album with the given ID owned by the given user.
     *
     * @param id    the album ID
     * @param owner the user ID
     * @return the album record, or {@code null} if not found
     * @since v2026.2.1
     */
    public Map<String, Object> getAlbum(String id, String owner) {
        var rows = db.queryForList("SELECT * FROM gallery_albums WHERE id=? AND owner=?", id, owner);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Deletes an album owned by the given user.
     *
     * @param id    the album ID
     * @param owner the user ID — only the owner may delete
     * @since v2026.2.1
     */
    public void deleteAlbum(String id, String owner) {
        db.update("DELETE FROM gallery_albums WHERE id=? AND owner=?", id, owner);
    }

    // ── Images ────────────────────────────────────────────────────────────────

    /**
     * Uploads an image, extracting EXIF metadata and deduplicating by SHA-256 hash per owner.
     *
     * @param owner    the user ID
     * @param bytes    raw image bytes
     * @param filename original filename (used to determine MIME type)
     * @param albumId  optional album ID to assign the image to; may be {@code null}
     * @return upload result map with {@code id}, {@code filename}, {@code size}, {@code duplicate} flag, and {@code exif}
     * @throws IOException if the file cannot be written to disk
     * @since v2026.2.1
     */
    public Map<String, Object> uploadImage(String owner, byte[] bytes, String filename, String albumId) throws IOException {
        // SHA-256 dedup per owner
        String hash = sha256Hex(bytes);
        var existing = db.queryForList(
                "SELECT id, filename FROM gallery_images WHERE file_hash=? AND owner=? AND is_active=1", hash, owner);
        if (!existing.isEmpty()) {
            return Map.of("duplicate", true, "id", existing.get(0).get("id"), "filename", existing.get(0).get("filename"));
        }

        // Store file
        Path galleryDir = Path.of(dataDir, "gallery");
        Files.createDirectories(galleryDir);
        String safeFilename = System.currentTimeMillis() + "_" + filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path dest = galleryDir.resolve(safeFilename);
        Files.write(dest, bytes);

        // Extract EXIF
        Map<String, Object> exif = extractExif(bytes);
        int width = exif.containsKey("width") ? ((Number) exif.get("width")).intValue() : 0;
        int height = exif.containsKey("height") ? ((Number) exif.get("height")).intValue() : 0;

        String id = "img-" + Long.toString(System.currentTimeMillis(), 36);
        String mime = guessMime(filename);
        db.update("""
                INSERT INTO gallery_images (id, owner, album_id, filename, file_path, file_hash, file_size, mime_type, width, height, exif_json, is_active, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                id, owner, albumId, filename, dest.toString(), hash, bytes.length, mime, width, height,
                toJson(exif), 1, Instant.now().toString());

        return Map.of("id", id, "filename", filename, "size", bytes.length, "duplicate", false, "exif", exif);
    }

    /**
     * Returns a paginated list of active images for the given owner, optionally filtered by album.
     *
     * @param owner    the user ID
     * @param albumId  optional album filter; {@code null} returns all images
     * @param page     1-based page number
     * @param pageSize number of images per page
     * @return list of image rows (without file_path or exif_json); never null
     * @since v2026.2.1
     */
    public List<Map<String, Object>> listImages(String owner, String albumId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        if (albumId != null) {
            return db.queryForList(
                    "SELECT id, owner, album_id, filename, file_size, mime_type, width, height, is_generated, created_at FROM gallery_images WHERE owner=? AND album_id=? AND is_active=1 ORDER BY created_at DESC LIMIT ? OFFSET ?",
                    owner, albumId, pageSize, offset);
        }
        return db.queryForList(
                "SELECT id, owner, album_id, filename, file_size, mime_type, width, height, is_generated, created_at FROM gallery_images WHERE owner=? AND is_active=1 ORDER BY created_at DESC LIMIT ? OFFSET ?",
                owner, pageSize, offset);
    }

    /**
     * Soft-deletes an image by setting {@code is_active=0}.
     *
     * @param id    the image ID
     * @param owner the user ID — only the owner may delete
     * @since v2026.2.1
     */
    public void deleteImage(String id, String owner) {
        db.update("UPDATE gallery_images SET is_active=0 WHERE id=? AND owner=?", id, owner);
    }

    // ── Editor Drafts ─────────────────────────────────────────────────────────

    /**
     * Creates or updates an editor draft. If {@code req} contains an existing {@code id},
     * the draft is updated; otherwise a new draft is created.
     *
     * @param owner the user ID
     * @param req   draft fields: {@code id} (for update), {@code name}, {@code source_image_id},
     *              {@code width}, {@code height}, {@code payload}, {@code thumbnail}
     * @return the saved draft record
     * @since v2026.2.1
     */
    public Map<String, Object> saveDraft(String owner, Map<String, Object> req) {
        String existingId = (String) req.get("id");
        String now = Instant.now().toString();
        if (existingId != null && !existingId.isBlank()) {
            db.update("UPDATE editor_drafts SET name=?, payload_json=?, thumbnail=?, updated_at=? WHERE id=? AND owner=?",
                    req.getOrDefault("name", "Draft"),
                    toJson(req.getOrDefault("payload", Map.of())),
                    req.get("thumbnail"),
                    now, existingId, owner);
            return getDraft(existingId, owner);
        }
        String id = "dft-" + Long.toString(System.currentTimeMillis(), 36);
        db.update("INSERT INTO editor_drafts (id, owner, name, source_image_id, width, height, payload_json, thumbnail, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, owner,
                req.getOrDefault("name", "Draft"),
                req.get("source_image_id"),
                req.get("width"), req.get("height"),
                toJson(req.getOrDefault("payload", Map.of())),
                req.get("thumbnail"),
                now, now);
        return getDraft(id, owner);
    }

    /**
     * Returns all editor drafts for the given owner, most recently updated first.
     *
     * @param owner the user ID
     * @return list of draft summary rows (without payload_json); never null
     * @since v2026.2.1
     */
    public List<Map<String, Object>> listDrafts(String owner) {
        return db.queryForList("SELECT id, owner, name, thumbnail, width, height, created_at, updated_at FROM editor_drafts WHERE owner=? ORDER BY updated_at DESC", owner);
    }

    /**
     * Returns an editor draft by ID, restricted to the given owner.
     *
     * @param id    the draft ID
     * @param owner the user ID
     * @return the draft record including payload, or {@code null} if not found
     * @since v2026.2.1
     */
    public Map<String, Object> getDraft(String id, String owner) {
        var rows = db.queryForList("SELECT * FROM editor_drafts WHERE id=? AND owner=?", id, owner);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Permanently deletes an editor draft.
     *
     * @param id    the draft ID
     * @param owner the user ID — only the owner may delete
     * @since v2026.2.1
     */
    public void deleteDraft(String id, String owner) {
        db.update("DELETE FROM editor_drafts WHERE id=? AND owner=?", id, owner);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> extractExif(byte[] bytes) {
        Map<String, Object> exif = new LinkedHashMap<>();
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            JpegDirectory jpeg = metadata.getFirstDirectoryOfType(JpegDirectory.class);
            if (jpeg != null) {
                exif.put("width", jpeg.getImageWidth());
                exif.put("height", jpeg.getImageHeight());
            }
            ExifIFD0Directory exif0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif0 != null) {
                if (exif0.containsTag(ExifIFD0Directory.TAG_MAKE)) exif.put("make", exif0.getString(ExifIFD0Directory.TAG_MAKE));
                if (exif0.containsTag(ExifIFD0Directory.TAG_MODEL)) exif.put("model", exif0.getString(ExifIFD0Directory.TAG_MODEL));
                if (exif0.containsTag(ExifIFD0Directory.TAG_DATETIME)) exif.put("date_taken", exif0.getString(ExifIFD0Directory.TAG_DATETIME));
            }
        } catch (Exception e) {
            log.debug("[gallery] EXIF extraction failed: {}", e.getMessage());
        }
        return exif;
    }

    private String guessMime(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) { return UUID.randomUUID().toString(); }
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }
}
