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
 * Unit tests for {@link DeepResearchService}.
 *
 * <p>Covers the lightweight query methods ({@code cancel}, {@code listTasks},
 * {@code getReport}) that do not spawn SSE streaming. The
 * {@code executeResearch()} method — which spans multiple LLM calls and SSE
 * emission — is excluded from unit coverage; it is exercised by integration
 * tests.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DeepResearchService — unit tests")
class DeepResearchServiceTest {

    @Mock ProviderService providerService;
    @Mock RouterService routerService;
    @Mock WebSearchService webSearchService;
    @Mock RagService ragService;
    @Mock ObjectMapper mapper;
    @Mock JdbcTemplate db;
    @Mock VisualReportService visualReportService;

    @InjectMocks DeepResearchService service;

    @BeforeEach
    void stubDefaults() {
        when(db.update(anyString(), any(), any())).thenReturn(1);
        when(db.update(anyString(), any(), any(), any())).thenReturn(1);
        when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(db.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of());
    }

    // ── cancel() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancel()")
    class Cancel {

        @Test
        @DisplayName("does not throw for an unknown taskId")
        void noThrowForUnknownTask() {
            assertThatCode(() -> service.cancel("res-unknown-task"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("calls DB update to mark task as cancelled")
        void callsDbUpdate() {
            service.cancel("res-abc123");
            verify(db, atLeastOnce()).update(contains("cancelled"), any(), eq("res-abc123"));
        }
    }

    // ── listTasks() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listTasks()")
    class ListTasks {

        @Test
        @DisplayName("returns empty list when no tasks exist")
        void returnsEmptyList() {
            when(db.queryForList(anyString(), eq("user-test-001"))).thenReturn(List.of());
            List<Map<String, Object>> result = service.listTasks("user-test-001");
            assertThat(result).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("returns rows from DB")
        void returnsDbRows() {
            List<Map<String, Object>> rows = List.of(
                    Map.of("id", "res-1", "owner", "user-test-001", "status", "completed")
            );
            when(db.queryForList(anyString(), eq("user-test-001"))).thenReturn(rows);
            List<Map<String, Object>> result = service.listTasks("user-test-001");
            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("id")).isEqualTo("res-1");
        }
    }

    // ── getReport() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getReport()")
    class GetReport {

        @Test
        @DisplayName("returns null when task not found")
        void returnsNullWhenNotFound() {
            when(db.queryForList(anyString(), eq("res-unknown"), eq("user-test-001")))
                    .thenReturn(List.of());
            String result = service.getReport("res-unknown", "user-test-001");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns report_html when task found")
        void returnsReportHtml() {
            Map<String, Object> row = Map.of("report_html", "<h1>Report</h1>");
            when(db.queryForList(anyString(), eq("res-abc"), eq("user-test-001")))
                    .thenReturn(List.of(row));
            String result = service.getReport("res-abc", "user-test-001");
            assertThat(result).isEqualTo("<h1>Report</h1>");
        }

        @Test
        @DisplayName("returns null when report_html is null in row")
        void returnsNullWhenReportHtmlNull() {
            Map<String, Object> row = new java.util.HashMap<>();
            row.put("report_html", null);
            when(db.queryForList(anyString(), eq("res-null"), eq("user-test-001")))
                    .thenReturn(List.of(row));
            String result = service.getReport("res-null", "user-test-001");
            assertThat(result).isNull();
        }
    }
}
