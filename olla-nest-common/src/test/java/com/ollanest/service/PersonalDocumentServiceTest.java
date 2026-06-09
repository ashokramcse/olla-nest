package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.ollanest.testinfra.UserFactory;

/**
 * OCD-level unit tests for {@link PersonalDocumentService}.
 *
 * <p>
 * Covers: {@code upload()} — IllegalArgumentException for files > 25 MB;
 * IllegalArgumentException for unsupported extensions (.exe, .zip, .bat);
 * accepts supported types (.pdf, .txt, .java, .py, .md) without throwing;
 * result map contains expected keys when upload succeeds.
 *
 * <p>
 * Uses {@link TempDir} + {@code ReflectionTestUtils} to inject {@code dataDir}.
 * RAG ingestion is stubbed via {@link RagService}.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PersonalDocumentService — unit tests")
class PersonalDocumentServiceTest {

	private static final String OWNER = UserFactory.USER_ID;
	private static final long MAX_25MB = 25L * 1024 * 1024;

	@TempDir
	Path tempDir;

	@Mock
	RagService ragService;
	@Mock
	JdbcTemplate db;

	@InjectMocks
	PersonalDocumentService svc;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(svc, "dataDir", tempDir.toString());
		// Stub RAG ingestion to return a doc ID without touching the real embedding
		// pipeline
		when(ragService.ingestText(any(), any(), any())).thenReturn("rag-doc-1");
	}

	// ── upload() — size validation ────────────────────────────────────────────

	@Nested
	@DisplayName("upload() — size validation")
	class SizeValidation {

		@Test
		@DisplayName("throws IllegalArgumentException for file larger than 25 MB")
		void throwsForOversizedFile() {
			// File one byte over the 25 MB limit must be rejected immediately
			byte[] oversized = new byte[(int) MAX_25MB + 1];
			assertThatThrownBy(() -> svc.upload(OWNER, oversized, "large.txt"))
					.isInstanceOf(IllegalArgumentException.class)
					// Message must mention the 25 MB limit so the user understands the rejection
					.hasMessageContaining("25 MB");
		}

		@Test
		@DisplayName("does not throw for file exactly at the 25 MB limit")
		void acceptsExactlyAtLimit() {
			// Boundary check: exactly 25 MB must be accepted (limit is inclusive)
			byte[] atLimit = new byte[(int) MAX_25MB];
			assertThatCode(() -> svc.upload(OWNER, atLimit, "exact.txt")).doesNotThrowAnyException();
		}
	}

	// ── upload() — extension validation ──────────────────────────────────────

	@Nested
	@DisplayName("upload() — extension validation")
	class ExtensionValidation {

		@ParameterizedTest
		@ValueSource(strings = { "file.exe", "archive.zip", "script.bat", "payload.bin", "virus.msi" })
		@DisplayName("throws IllegalArgumentException for disallowed extensions")
		void throwsForDisallowedExtensions(String filename) {
			// SECURITY: binary/executable extensions must be rejected to prevent malicious
			// uploads
			byte[] content = "bad content".getBytes(StandardCharsets.UTF_8);
			assertThatThrownBy(() -> svc.upload(OWNER, content, filename)).isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("not supported");
		}

		@ParameterizedTest
		@ValueSource(strings = { "document.txt", "code.java", "script.py", "notes.md", "data.csv" })
		@DisplayName("accepts supported text-based extensions")
		void acceptsSupportedTextExtensions(String filename) {
			// Common text/code extensions must pass the extension guard without exception
			byte[] content = "Hello World".getBytes(StandardCharsets.UTF_8);
			assertThatCode(() -> svc.upload(OWNER, content, filename)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("accepts .pdf extension (PDF extraction path)")
		void acceptsPdf() {
			// Minimal bytes — PDF extraction will fail gracefully but the extension guard
			// must pass
			byte[] content = "%PDF-1.4 minimal".getBytes(StandardCharsets.UTF_8);
			assertThatCode(() -> svc.upload(OWNER, content, "report.pdf")).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("accepts .js and .ts extensions")
		void acceptsJsAndTs() {
			// JavaScript and TypeScript are common code files — must be uploadable
			byte[] js = "console.log('hi')".getBytes(StandardCharsets.UTF_8);
			assertThatCode(() -> svc.upload(OWNER, js, "app.js")).doesNotThrowAnyException();
			byte[] ts = "const x: string = 'hi'".getBytes(StandardCharsets.UTF_8);
			assertThatCode(() -> svc.upload(OWNER, ts, "app.ts")).doesNotThrowAnyException();
		}
	}

	// ── upload() — success result shape ──────────────────────────────────────

	@Nested
	@DisplayName("upload() — success result shape")
	class SuccessResult {

		@Test
		@DisplayName("result contains filename, size, and rag_doc_id keys")
		void resultHasExpectedKeys() throws Exception {
			byte[] content = "Hello World content".getBytes(StandardCharsets.UTF_8);
			var result = svc.upload(OWNER, content, "hello.txt");
			// Callers rely on these three keys — missing any one would break the UI
			// response
			assertThat(result).containsKey("filename").containsKey("size").containsKey("rag_doc_id");
		}

		@Test
		@DisplayName("filename in result matches the original filename")
		void filenameMatchesOriginal() throws Exception {
			byte[] content = "test".getBytes(StandardCharsets.UTF_8);
			var result = svc.upload(OWNER, content, "myfile.txt");
			// Filename must be echoed back so the caller can confirm what was stored
			assertThat(result.get("filename")).isEqualTo("myfile.txt");
		}

		@Test
		@DisplayName("size in result matches byte array length")
		void sizeMatchesByteLength() throws Exception {
			byte[] content = "exact length test".getBytes(StandardCharsets.UTF_8);
			var result = svc.upload(OWNER, content, "size.txt");
			// Size must reflect the actual uploaded content length — not a guess
			assertThat(result.get("size")).isEqualTo(content.length);
		}
	}
}
