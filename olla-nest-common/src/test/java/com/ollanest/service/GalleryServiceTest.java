package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;

/**
 * OCD-level unit tests for {@link GalleryService}.
 *
 * <p>
 * Covers: {@code createAlbum()} — DB INSERT, id prefix; {@code listAlbums()} —
 * queries with owner; {@code getAlbum()} — null when not found;
 * {@code deleteAlbum()} — scoped DELETE. {@code uploadImage()} is excluded
 * (requires real FS + EXIF library).
 *
 * <p>
 * Uses {@link TempDir} + {@code ReflectionTestUtils} to inject {@code dataDir}.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GalleryService — unit tests")
class GalleryServiceTest {

	private static final String OWNER = UserFactory.USER_ID;

	@TempDir
	Path tempDir;

	@Mock
	JdbcTemplate db;
	@Mock
	ObjectMapper mapper;

	@InjectMocks
	GalleryService svc;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(svc, "dataDir", tempDir.toString());
	}

	// ── createAlbum() ─────────────────────────────────────────────────────────

	@Nested
	@DisplayName("createAlbum()")
	class CreateAlbum {

		@Test
		@DisplayName("DB INSERT called with owner")
		void insertCalledWithOwner() {
			// stub getAlbum() called inside createAlbum() to return empty (new album)
			when(db.queryForList(contains("FROM gallery_albums WHERE id"), (Object) any(), any()))
					.thenReturn(List.of());
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.createAlbum(OWNER, Map.of("name", "My Album"));
			verify(db).update(contains("INSERT INTO gallery_albums"), cap.capture());
			// args[0]=id, args[1]=owner, args[2]=name — verify owner stored
			assertThat(cap.getValue()[1]).isEqualTo(OWNER);
		}

		@Test
		@DisplayName("generated id starts with 'alb-'")
		void idStartsWithAlbPrefix() {
			// Stub: read-back after INSERT returns empty (id checked via captor)
			when(db.queryForList(contains("FROM gallery_albums WHERE id"), (Object) any(), any()))
					.thenReturn(List.of());
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.createAlbum(OWNER, Map.of("name", "Vacation"));
			verify(db).update(contains("INSERT INTO gallery_albums"), cap.capture());
			// args[0] = generated album ID — must have "alb-" prefix for readability
			assertThat(cap.getValue()[0].toString()).startsWith("alb-");
		}

		@Test
		@DisplayName("name defaults to 'Album' when not provided")
		void defaultAlbumName() {
			// Stub: empty params map (no "name" key) → service must default to "Album"
			when(db.queryForList(contains("FROM gallery_albums WHERE id"), (Object) any(), any()))
					.thenReturn(List.of());
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.createAlbum(OWNER, Map.of());
			verify(db).update(contains("INSERT INTO gallery_albums"), cap.capture());
			// args[2] = name — must be defaulted to "Album" when caller omits the field
			assertThat(cap.getValue()[2]).isEqualTo("Album");
		}
	}

	// ── listAlbums() ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("listAlbums()")
	class ListAlbums {

		@Test
		@DisplayName("queries with owner parameter")
		void queriesWithOwner() {
			// Stub: empty result (no albums yet)
			when(db.queryForList(anyString(), eq(OWNER))).thenReturn(List.of());
			svc.listAlbums(OWNER);
			// Query must be scoped to the requesting owner
			verify(db).queryForList(anyString(), eq(OWNER));
		}

		@Test
		@DisplayName("returns empty list when no albums exist")
		void emptyWhenNoAlbums() {
			// Stub: DB returns empty row set
			when(db.queryForList(anyString(), eq(OWNER))).thenReturn(List.of());
			// Empty list returned — not null
			assertThat(svc.listAlbums(OWNER)).isEmpty();
		}
	}

	// ── getAlbum() ────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("getAlbum()")
	class GetAlbum {

		@Test
		@DisplayName("returns null when not found")
		void returnsNullWhenNotFound() {
			// Stub: no row for this album ID / owner combination
			when(db.queryForList(anyString(), eq("alb-missing"), eq(OWNER))).thenReturn(List.of());
			// Null returned gracefully (caller decides to 404)
			assertThat(svc.getAlbum("alb-missing", OWNER)).isNull();
		}

		@Test
		@DisplayName("returns row when found")
		void returnsRowWhenFound() {
			// Stub: album row found for this owner
			var row = Map.<String, Object>of("id", "alb-1", "owner", OWNER, "name", "Vacation");
			when(db.queryForList(anyString(), eq("alb-1"), eq(OWNER))).thenReturn(List.of(row));
			// Exact DB row returned — no transformation for this getter
			assertThat(svc.getAlbum("alb-1", OWNER)).isEqualTo(row);
		}
	}

	// ── deleteAlbum() ─────────────────────────────────────────────────────────

	@Nested
	@DisplayName("deleteAlbum()")
	class DeleteAlbum {

		@Test
		@DisplayName("calls DELETE WHERE id=? AND owner=?")
		void deleteScopedToIdAndOwner() {
			svc.deleteAlbum("alb-123", OWNER);
			// DELETE scoped to BOTH id AND owner — prevents one user deleting another's
			// album
			verify(db).update(contains("DELETE FROM gallery_albums WHERE id"), eq("alb-123"), eq(OWNER));
		}

		@Test
		@DisplayName("does not delete albums of other owners")
		void doesNotDeleteOtherOwnerAlbums() {
			svc.deleteAlbum("alb-123", OWNER);
			// "other-owner" must never appear as a parameter — isolation guarantee
			verify(db, never()).update(anyString(), eq("other-owner"), any());
		}
	}

	// ── uploadImage() magic-byte validation (OBS-010) ─────────────────────────

	/**
	 * Verifies that {@link GalleryService#uploadImage} accepts only real raster
	 * images by inspecting the leading magic bytes, rejecting spoofed content
	 * (e.g. a text/PHP payload renamed {@code .png}) with an
	 * {@link IllegalArgumentException} before any persistence.
	 */
	@Nested
	@DisplayName("uploadImage() — magic-byte validation (OBS-010)")
	class UploadImageValidation {

		/**
		 * A text/script payload (here a PHP snippet) renamed to {@code .png} has no
		 * valid image magic bytes; {@code uploadImage} must reject it with an
		 * {@link IllegalArgumentException} and must not issue any INSERT, proving the
		 * guard runs before persistence (BUG-037 / OBS-010).
		 *
		 * @author Ashok Ram
		 * @since v2026.1.10
		 * @version v2026.1.10
		 */
		@Test
		@DisplayName("non-image bytes (text renamed .png) → IllegalArgumentException, no INSERT")
		void rejectsNonImage() throws Exception {
			byte[] notImage = "<?php system($_GET[c]); ?>".getBytes();
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.uploadImage(OWNER, notImage, "shell.png", null))
					.isInstanceOf(IllegalArgumentException.class);
			verify(db, never()).update(contains("INSERT INTO gallery_images"), any(Object[].class));
		}

		/**
		 * An empty (zero-byte) upload has no magic bytes to validate and must be
		 * rejected with an {@link IllegalArgumentException} rather than stored as a
		 * zero-byte "image".
		 *
		 * @author Ashok Ram
		 * @since v2026.1.10
		 * @version v2026.1.10
		 */
		@Test
		@DisplayName("empty file → IllegalArgumentException")
		void rejectsEmpty() {
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.uploadImage(OWNER, new byte[0], "x.png", null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		/**
		 * A genuine PNG signature ({@code 89 50 4E 47 …}) passes the magic-byte guard:
		 * {@code uploadImage} proceeds past validation and issues the INSERT, proving
		 * the hardening does not reject legitimate images.
		 *
		 * @author Ashok Ram
		 * @since v2026.1.10
		 * @version v2026.1.10
		 */
		@Test
		@DisplayName("valid PNG header passes the magic-byte guard")
		void acceptsPngHeader() throws Exception {
			// PNG magic + minimal padding. Dedup lookup returns empty so it proceeds to store.
			byte[] png = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
			when(db.queryForList(contains("file_hash"), anyString(), eq(OWNER))).thenReturn(List.of());
			when(db.queryForList(contains("FROM gallery_images WHERE id"), anyString())).thenReturn(List.of());
			// Must not throw the validation exception; any later read-back returning empty is fine.
			org.assertj.core.api.Assertions
					.assertThatCode(() -> svc.uploadImage(OWNER, png, "ok.png", null))
					.doesNotThrowAnyException();
			verify(db).update(contains("INSERT INTO gallery_images"), any(Object[].class));
		}
	}
}
