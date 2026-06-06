package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CookbookService}.
 *
 * <p>Covers {@code getCatalog()} (hardcoded entries), {@code getDownloads()}
 * (in-memory state), and {@code detectHardware()} (pure platform inspection).
 * {@code startDownload()} spawns threads and real HTTP — excluded from unit
 * tests.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CookbookService — unit tests")
class CookbookServiceTest {

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;
    @Mock DatabaseService databaseService;

    @InjectMocks CookbookService service;

    @BeforeEach
    void stubDefaults() {
        // Default: no downloaded models
        when(db.queryForList(anyString(), anyString(), eq(1))).thenReturn(List.of());
        when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
    }

    // ── detectHardware() ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("detectHardware()")
    class DetectHardware {

        @Test
        @DisplayName("returns a map with a 'type' (backend) key")
        void returnsMapWithBackend() {
            Map<String, Object> hw = service.detectHardware();
            assertThat(hw).containsKey("backend");
        }

        @Test
        @DisplayName("does not throw on any OS")
        void doesNotThrow() {
            assertThatCode(() -> service.detectHardware()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("result contains os, has_gpu, gpu_vram_gb, available_ram_gb keys")
        void containsExpectedKeys() {
            Map<String, Object> hw = service.detectHardware();
            assertThat(hw).containsKeys("os", "has_gpu", "gpu_vram_gb", "available_ram_gb", "gpu_count");
        }

        @Test
        @DisplayName("available_ram_gb is a positive number")
        void ramGbPositive() {
            Map<String, Object> hw = service.detectHardware();
            double ram = ((Number) hw.get("available_ram_gb")).doubleValue();
            assertThat(ram).isGreaterThanOrEqualTo(0.0);
        }
    }

    // ── getCatalog() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getCatalog()")
    class GetCatalog {

        @Test
        @DisplayName("returns non-empty list of models")
        void returnsNonEmptyList() {
            List<Map<String, Object>> catalog = service.getCatalog();
            assertThat(catalog).isNotEmpty();
        }

        @Test
        @DisplayName("each entry has name, hf_repo, quantization, fits, is_downloaded")
        void entriesHaveExpectedKeys() {
            List<Map<String, Object>> catalog = service.getCatalog();
            for (Map<String, Object> entry : catalog) {
                assertThat(entry).containsKeys("name", "hf_repo", "quantization", "fits", "is_downloaded");
            }
        }

        @Test
        @DisplayName("contains at least 10 models in the hardcoded catalog")
        void atLeast10Models() {
            assertThat(service.getCatalog()).hasSizeGreaterThanOrEqualTo(10);
        }
    }

    // ── getDownloads() ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getDownloads()")
    class GetDownloads {

        @Test
        @DisplayName("returns empty list when no active downloads")
        void returnsEmptyWhenNoDownloads() {
            List<Map<String, Object>> downloads = service.getDownloads();
            assertThat(downloads).isNotNull().isEmpty();
        }
    }
}
