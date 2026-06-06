package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link McpServerService}.
 *
 * <p>Covers CRUD operations for MCP server management.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("McpServerService — unit tests")
class McpServerServiceTest {

    @Mock JdbcTemplate db;
    @Mock ObjectMapper mapper;

    @InjectMocks McpServerService mcpServerService;

    private Map<String, Object> serverRow(String id, String name) {
        return Map.of("id", id, "name", name, "command", "node",
                "args_json", "[]", "env_json", "{}", "transport", "stdio",
                "url", null, "disabled_tools_json", "[]", "enabled", 1);
    }

    // ── create() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("INSERT is called")
        void insertsRow() throws Exception {
            when(mapper.writeValueAsString(any())).thenReturn("[]");
            when(db.queryForList(anyString(), anyString()))
                    .thenReturn(List.of(serverRow("mcp-abc", "My Server")));
            mcpServerService.create(Map.of("name", "My Server"));
            verify(db).update(contains("INSERT INTO mcp_servers"), (Object[]) any());
        }

        @Test
        @DisplayName("id starts with 'mcp-'")
        void idPrefix() throws Exception {
            when(mapper.writeValueAsString(any())).thenReturn("[]");
            when(db.queryForList(anyString(), anyString()))
                    .thenReturn(List.of(serverRow("mcp-xyz", "Test")));
            Map<String, Object> result = mcpServerService.create(Map.of("name", "Test"));
            assertThat(result.get("id").toString()).startsWith("mcp-");
        }

        @Test
        @DisplayName("name is stored in returned record")
        void nameStored() throws Exception {
            when(mapper.writeValueAsString(any())).thenReturn("[]");
            when(db.queryForList(anyString(), anyString()))
                    .thenReturn(List.of(serverRow("mcp-1", "My Server")));
            Map<String, Object> result = mcpServerService.create(Map.of("name", "My Server"));
            assertThat(result.get("name")).isEqualTo("My Server");
        }
    }

    // ── getById() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getById()")
    class GetById {

        @Test
        @DisplayName("returns null when empty")
        void nullWhenEmpty() {
            when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
            assertThat(mcpServerService.getById("mcp-999")).isNull();
        }

        @Test
        @DisplayName("returns enriched record when found")
        void returnsMappedRow() throws Exception {
            when(mapper.readValue(anyString(), eq(Object.class))).thenReturn(List.of());
            when(db.queryForList(anyString(), eq("mcp-1")))
                    .thenReturn(List.of(serverRow("mcp-1", "Server1")));
            Map<String, Object> result = mcpServerService.getById("mcp-1");
            assertThat(result).isNotNull();
        }
    }

    // ── list() ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("list()")
    class List_ {

        @Test
        @DisplayName("calls SELECT FROM mcp_servers")
        void queriesTable() throws Exception {
            when(mapper.readValue(anyString(), eq(Object.class))).thenReturn(List.of());
            when(db.queryForList(anyString())).thenReturn(List.of(serverRow("mcp-1", "S1")));
            List<Map<String, Object>> results = mcpServerService.list();
            assertThat(results).isNotNull();
            verify(db).queryForList(anyString());
        }
    }

    // ── delete() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("DELETE WHERE id=? is called")
        void deletesById() {
            mcpServerService.delete("mcp-1");
            verify(db).update(contains("DELETE FROM mcp_servers WHERE id=?"), eq("mcp-1"));
        }
    }

    // ── setEnabled() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setEnabled()")
    class SetEnabled {

        @Test
        @DisplayName("UPDATE SET enabled=1 when enabled=true")
        void setsEnabledTrue() {
            mcpServerService.setEnabled("mcp-1", true);
            verify(db).update(contains("UPDATE mcp_servers SET enabled=?"), eq(1), anyString(), eq("mcp-1"));
        }

        @Test
        @DisplayName("UPDATE SET enabled=0 when enabled=false")
        void setsEnabledFalse() {
            mcpServerService.setEnabled("mcp-1", false);
            verify(db).update(contains("UPDATE mcp_servers SET enabled=?"), eq(0), anyString(), eq("mcp-1"));
        }
    }

    // ── setDisabledTools() ────────────────────────────────────────────────────

    @Nested
    @DisplayName("setDisabledTools()")
    class SetDisabledTools {

        @Test
        @DisplayName("UPDATE is called with serialized tools JSON")
        void updatesWithJson() throws Exception {
            when(mapper.writeValueAsString(any())).thenReturn("[\"tool1\"]");
            mcpServerService.setDisabledTools("mcp-1", List.of("tool1"));
            verify(db).update(contains("UPDATE mcp_servers SET disabled_tools_json=?"), anyString(), anyString(), eq("mcp-1"));
        }
    }
}
