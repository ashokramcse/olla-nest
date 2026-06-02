package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;
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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link GalleryService}.
 *
 * <p>Covers: {@code createAlbum()} — DB INSERT, id prefix; {@code listAlbums()} — queries with
 * owner; {@code getAlbum()} — null when not found; {@code deleteAlbum()} — scoped DELETE.
 * {@code uploadImage()} is excluded (requires real FS + EXIF library).
 *
 * <p>Uses {@link TempDir} + {@code ReflectionTestUtils} to inject {@code dataDir}.
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

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;

    @InjectMocks GalleryService svc;

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
            // stub getAlbum() called inside createAlbum()
            when(db.queryForList(contains("FROM gallery_albums WHERE id"), any(), any())).thenReturn(List.of());
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            svc.createAlbum(OWNER, Map.of("name", "My Album"));
            verify(db).update(contains("INSERT INTO gallery_albums"), cap.capture());
            assertThat(cap.getValue()[1]).isEqualTo(OWNER);
        }

        @Test
        @DisplayName("generated id starts with 'alb-'")
        void idStartsWithAlbPrefix() {
            when(db.queryForList(contains("FROM gallery_albums WHERE id"), any(), any())).thenReturn(List.of());
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            svc.createAlbum(OWNER, Map.of("name", "Vacation"));
            verify(db).update(contains("INSERT INTO gallery_albums"), cap.capture());
            assertThat(cap.getValue()[0].toString()).startsWith("alb-");
        }

        @Test
        @DisplayName("name defaults to 'Album' when not provided")
        void defaultAlbumName() {
            when(db.queryForList(contains("FROM gallery_albums WHERE id"), any(), any())).thenReturn(List.of());
            ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
            svc.createAlbum(OWNER, Map.of());
            verify(db).update(contains("INSERT INTO gallery_albums"), cap.capture());
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
            when(db.queryForList(anyString(), eq(OWNER))).thenReturn(List.of());
            svc.listAlbums(OWNER);
            verify(db).queryForList(anyString(), eq(OWNER));
        }

        @Test
        @DisplayName("returns empty list when no albums exist")
        void emptyWhenNoAlbums() {
            when(db.queryForList(anyString(), eq(OWNER))).thenReturn(List.of());
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
            when(db.queryForList(anyString(), eq("alb-missing"), eq(OWNER))).thenReturn(List.of());
            assertThat(svc.getAlbum("alb-missing", OWNER)).isNull();
        }

        @Test
        @DisplayName("returns row when found")
        void returnsRowWhenFound() {
            var row = Map.<String, Object>of("id", "alb-1", "owner", OWNER, "name", "Vacation");
            when(db.queryForList(anyString(), eq("alb-1"), eq(OWNER))).thenReturn(List.of(row));
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
            verify(db).update(contains("DELETE FROM gallery_albums WHERE id"), eq("alb-123"), eq(OWNER));
        }

        @Test
        @DisplayName("does not delete albums of other owners")
        void doesNotDeleteOtherOwnerAlbums() {
            svc.deleteAlbum("alb-123", OWNER);
            verify(db, never()).update(anyString(), eq("other-owner"), any());
        }
    }
}
