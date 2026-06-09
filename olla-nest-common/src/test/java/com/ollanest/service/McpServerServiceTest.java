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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        // LinkedHashMap (not Map.of) because the "url" value is null, which Map.of rejects.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("command", "node");
        row.put("args_json", "[]");
        row.put("env_json", "{}");
        row.put("transport", "stdio");
        row.put("url", null);
        row.put("disabled_tools_json", "[]");
        row.put("enabled", 1);
        return row;
    }

    // ── create() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("INSERT is called")
        void insertsRow() throws Exception {
            // Stub: mapper serializes args/env lists to JSON; DB read-back returns created row
            when(mapper.writeValueAsString(any())).thenReturn("[]");
            when(db.queryForList(anyString(), anyString()))
                    .thenReturn(List.of(serverRow("mcp-abc", "My Server")));
            mcpServerService.create(Map.of("name", "My Server"));
            // Verify the INSERT into mcp_servers was called
            verify(db).update(contains("INSERT INTO mcp_servers"), any(Object[].class));
        }

        @Test
        @DisplayName("id starts with 'mcp-'")
        void idPrefix() throws Exception {
            // Stub mapper + DB read-back
            when(mapper.writeValueAsString(any())).thenReturn("[]");
            when(db.queryForList(anyString(), anyString()))
                    .thenReturn(List.of(serverRow("mcp-xyz", "Test")));
            Map<String, Object> result = mcpServerService.create(Map.of("name", "Test"));
            // ID prefix "mcp-" makes it easy to identify MCP server records in DB
            assertThat(result.get("id").toString()).startsWith("mcp-");
        }

        @Test
        @DisplayName("name is stored in returned record")
        void nameStored() throws Exception {
            // Stub DB read-back to return the row with the correct name
            when(mapper.writeValueAsString(any())).thenReturn("[]");
            when(db.queryForList(anyString(), anyString()))
                    .thenReturn(List.of(serverRow("mcp-1", "My Server")));
            Map<String, Object> result = mcpServerService.create(Map.of("name", "My Server"));
            // Returned record must reflect the name the caller supplied
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
            // Stub: no row found for unknown ID → service must return null (not throw)
            when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
            assertThat(mcpServerService.getById("mcp-999")).isNull();
        }

        @Test
        @DisplayName("returns enriched record when found")
        void returnsMappedRow() throws Exception {
            // Stub: mapper deserializes args_json / env_json fields to objects
            when(mapper.readValue(anyString(), eq(Object.class))).thenReturn(List.of());
            when(db.queryForList(anyString(), eq("mcp-1")))
                    .thenReturn(List.of(serverRow("mcp-1", "Server1")));
            Map<String, Object> result = mcpServerService.getById("mcp-1");
            // Non-null map returned (with JSON fields deserialized)
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
            // Stub: mapper for JSON field deserialization; DB returns one row
            when(mapper.readValue(anyString(), eq(Object.class))).thenReturn(List.of());
            when(db.queryForList(anyString())).thenReturn(List.of(serverRow("mcp-1", "S1")));
            List<Map<String, Object>> results = mcpServerService.list();
            // Results list is non-null and DB was queried
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
            // Parameterized DELETE — ID must appear as a bound parameter, never in the SQL string
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
            // enabled=true stored as integer 1 (SQLite boolean representation)
            verify(db).update(contains("UPDATE mcp_servers SET enabled=?"), eq(1), anyString(), eq("mcp-1"));
        }

        @Test
        @DisplayName("UPDATE SET enabled=0 when enabled=false")
        void setsEnabledFalse() {
            mcpServerService.setEnabled("mcp-1", false);
            // enabled=false stored as integer 0
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
            // Stub: mapper serializes the disabled tools list to a JSON array string
            when(mapper.writeValueAsString(any())).thenReturn("[\"tool1\"]");
            mcpServerService.setDisabledTools("mcp-1", List.of("tool1"));
            // The JSON string is stored in disabled_tools_json column
            verify(db).update(contains("UPDATE mcp_servers SET disabled_tools_json=?"), anyString(), anyString(), eq("mcp-1"));
        }
    }
}
