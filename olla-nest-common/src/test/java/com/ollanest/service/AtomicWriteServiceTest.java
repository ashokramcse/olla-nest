package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * OCD-level unit tests for {@link AtomicWriteService}.
 *
 * <p>Uses {@link TempDir} for real file-system writes and {@code new ObjectMapper()} directly —
 * no mock needed for real writes. Covers: {@code writeText()} — file contents, no temp file
 * left behind, parent dirs created; {@code writeJson()} — valid JSON written;
 * {@code writeBytes()} — exact bytes on disk.
 *
 * <p>No Spring context, no network calls.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AtomicWriteService — unit tests")
class AtomicWriteServiceTest {

    @TempDir
    Path tempDir;

    /** Real ObjectMapper — needed for writeJson(). */
    private final ObjectMapper mapper = new ObjectMapper();

    private AtomicWriteService svc;

    @BeforeEach
    void setUp() {
        svc = new AtomicWriteService(mapper);
    }

    // ── writeText() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("writeText()")
    class WriteText {

        @Test
        @DisplayName("file exists with correct content after write")
        void fileHasCorrectContent() throws Exception {
            Path dest = tempDir.resolve("output.txt");
            svc.writeText(dest, "Hello, atomic world!");
            // Verify the file was created and contains exactly the expected content
            assertThat(Files.readString(dest, StandardCharsets.UTF_8)).isEqualTo("Hello, atomic world!");
        }

        @Test
        @DisplayName("no .tmp file left behind after successful write")
        void noTempFileRemains() throws Exception {
            Path dest = tempDir.resolve("clean.txt");
            svc.writeText(dest, "Content");
            // Atomic write uses a temp file then renames — temp must be cleaned up on success
            long tmpCount = Files.list(tempDir).filter(p -> p.toString().contains(".tmp.")).count();
            assertThat(tmpCount).isZero();
        }

        @Test
        @DisplayName("overwrites existing file atomically")
        void overwritesExistingFile() throws Exception {
            Path dest = tempDir.resolve("overwrite.txt");
            // Step 1: write original content
            svc.writeText(dest, "Original");
            // Step 2: overwrite atomically — old content replaced in a single rename
            svc.writeText(dest, "Updated");
            assertThat(Files.readString(dest, StandardCharsets.UTF_8)).isEqualTo("Updated");
        }

        @Test
        @DisplayName("creates parent directories if they do not exist")
        void createsParentDirs() throws Exception {
            // Deeply nested path — service must create all intermediate directories
            Path dest = tempDir.resolve("deep/nested/dir/file.txt");
            svc.writeText(dest, "Deep content");
            assertThat(Files.exists(dest)).isTrue();
            assertThat(Files.readString(dest, StandardCharsets.UTF_8)).isEqualTo("Deep content");
        }

        @Test
        @DisplayName("empty string is written as empty file")
        void emptyStringWrittenAsEmptyFile() throws Exception {
            Path dest = tempDir.resolve("empty.txt");
            svc.writeText(dest, "");
            // Empty string must produce a 0-byte file, not throw
            assertThat(Files.readString(dest, StandardCharsets.UTF_8)).isEmpty();
        }
    }

    // ── writeJson() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("writeJson()")
    class WriteJson {

        @Test
        @DisplayName("written file is valid JSON and contains expected fields")
        void writesValidJson() throws Exception {
            Path dest = tempDir.resolve("data.json");
            var data = Map.of("key", "value", "count", 42);
            svc.writeJson(dest, data);
            String written = Files.readString(dest, StandardCharsets.UTF_8);
            // Verify it is valid JSON by re-parsing — malformed JSON would throw here
            var parsed = mapper.readTree(written);
            assertThat(parsed.get("key").asText()).isEqualTo("value");
            assertThat(parsed.get("count").asInt()).isEqualTo(42);
        }

        @Test
        @DisplayName("output is pretty-printed (contains newlines)")
        void prettyPrinted() throws Exception {
            Path dest = tempDir.resolve("pretty.json");
            svc.writeJson(dest, Map.of("a", 1));
            String written = Files.readString(dest, StandardCharsets.UTF_8);
            // Pretty-printing makes files human-readable and diff-friendly in git
            assertThat(written).contains("\n");
        }
    }

    // ── writeBytes() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("writeBytes()")
    class WriteBytes {

        @Test
        @DisplayName("file has exact byte content")
        void exactBytesOnDisk() throws Exception {
            byte[] bytes = {0x01, 0x02, 0x03, 0x04, (byte) 0xFF};
            Path dest = tempDir.resolve("binary.bin");
            svc.writeBytes(dest, bytes);
            // Binary content must be written verbatim — no encoding or transformation
            assertThat(Files.readAllBytes(dest)).isEqualTo(bytes);
        }

        @Test
        @DisplayName("zero-length byte array creates an empty file")
        void emptyBytesCreatesEmptyFile() throws Exception {
            Path dest = tempDir.resolve("empty.bin");
            svc.writeBytes(dest, new byte[0]);
            // File must exist even with zero bytes
            assertThat(Files.exists(dest)).isTrue();
            assertThat(Files.readAllBytes(dest)).isEmpty();
        }

        @Test
        @DisplayName("overwrites existing file with new bytes")
        void overwritesExistingBytes() throws Exception {
            Path dest = tempDir.resolve("overwrite.bin");
            // Step 1: write 3 bytes
            svc.writeBytes(dest, new byte[]{1, 2, 3});
            // Step 2: overwrite with 2 bytes — old content must be gone (not padded)
            svc.writeBytes(dest, new byte[]{9, 8});
            assertThat(Files.readAllBytes(dest)).isEqualTo(new byte[]{9, 8});
        }
    }
}
