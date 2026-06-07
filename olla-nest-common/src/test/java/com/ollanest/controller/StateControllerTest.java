package com.ollanest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.model.User;
import com.ollanest.service.*;
import com.ollanest.testinfra.UserFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link StateController}.
 *
 * <p>Covers GET /api/state — auth check and response body content.
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StateController — unit tests")
class StateControllerTest {

    @Mock JdbcTemplate db;
    @Mock UserService userService;
    @Mock ModelService modelService;
    @Mock DatabaseService databaseService;
    @Mock ChatService chatService;
    @Mock OllamaService ollamaService;
    @Mock WorkspaceService workspaceService;
    @Mock ObjectMapper mapper;

    @InjectMocks StateController stateController;

    private HttpServletRequest authenticatedRequest(User user) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("authenticatedUser")).thenReturn(user);
        when(req.getMethod()).thenReturn("GET");
        return req;
    }

    private HttpServletRequest unauthenticatedRequest() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("authenticatedUser")).thenReturn(null);
        when(req.getMethod()).thenReturn("GET");
        return req;
    }

    // ── getState() — unauthenticated ──────────────────────────────────────────

    @Nested
    @DisplayName("getState() — unauthenticated")
    class Unauthenticated {

        @Test
        @DisplayName("returns 401 when no authenticated user")
        void returns401WhenNoUser() {
            // Unauthenticated request must be rejected before any DB queries
            ResponseEntity<Map<String, Object>> response = stateController.getState(unauthenticatedRequest());
            assertThat(response.getStatusCodeValue()).isEqualTo(401);
        }
    }

    // ── getState() — authenticated regular user ───────────────────────────────

    @Nested
    @DisplayName("getState() — authenticated user")
    class AuthenticatedUser {

        @Test
        @DisplayName("returns 200 with non-null body")
        void returns200WithBody() {
            User user = UserFactory.regularUser();
            HttpServletRequest req = authenticatedRequest(user);

            // Stub all DB queries that getState() performs to build the app state snapshot
            when(db.queryForList(contains("FROM models"))).thenReturn(List.of());
            when(db.queryForList(contains("FROM chat_sessions"), anyString())).thenReturn(List.of());
            when(db.queryForList(contains("FROM settings"))).thenReturn(List.of());
            when(db.queryForList(contains("FROM departments"))).thenReturn(List.of());
            when(db.queryForList(contains("FROM groups"))).thenReturn(List.of());
            when(db.queryForList(contains("FROM teams"))).thenReturn(List.of());
            when(db.queryForList(contains("FROM users"))).thenReturn(List.of());
            when(workspaceService.workspaceForUser(anyString())).thenReturn(Map.of());
            when(databaseService.getSetting(anyString(), anyString())).thenReturn("");
            when(chatService.getActiveChat(anyString())).thenReturn(Map.of("id", "chat-1"));
            when(chatService.buildChatObject(any())).thenReturn(Map.of("id", "chat-1"));

            ResponseEntity<Map<String, Object>> response = stateController.getState(req);
            assertThat(response.getStatusCodeValue()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
        }

        @Test
        @DisplayName("response body contains user info")
        void bodyContainsUser() {
            User user = UserFactory.regularUser();
            HttpServletRequest req = authenticatedRequest(user);

            // Stub all DB overloads to handle any signature getState() may invoke
            when(db.queryForList(anyString())).thenReturn(List.of());
            when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
            when(db.queryForList(anyString(), anyString(), anyInt())).thenReturn(List.of());
            when(workspaceService.workspaceForUser(anyString())).thenReturn(Map.of());
            when(databaseService.getSetting(anyString(), anyString())).thenReturn("");
            when(chatService.getActiveChat(anyString())).thenReturn(Map.of("id", "chat-1"));
            when(chatService.buildChatObject(any())).thenReturn(Map.of("id", "chat-1"));

            ResponseEntity<Map<String, Object>> response = stateController.getState(req);
            // User info must be present so the frontend can initialise the session
            assertThat(response.getBody()).containsKey("user");
        }
    }

    // ── getState() — admin user ───────────────────────────────────────────────

    @Nested
    @DisplayName("getState() — admin user")
    class AdminUser {

        @Test
        @DisplayName("admin user gets response with non-null body")
        void adminGetsFullResponse() {
            User admin = UserFactory.admin();
            HttpServletRequest req = authenticatedRequest(admin);

            // Stub admin-specific DB queries (audit_events, all users, etc.)
            when(db.queryForList(contains("FROM models"))).thenReturn(List.of());
            when(db.queryForList(contains("FROM chat_sessions WHERE is_active"))).thenReturn(List.of());
            when(db.queryForList(contains("FROM audit_events"))).thenReturn(List.of());
            when(db.queryForList(contains("FROM settings"))).thenReturn(List.of());
            when(db.queryForList(contains("FROM departments"))).thenReturn(List.of());
            when(db.queryForList(contains("FROM groups"))).thenReturn(List.of());
            when(db.queryForList(contains("FROM teams"))).thenReturn(List.of());
            when(db.queryForList(contains("FROM users"))).thenReturn(List.of());
            when(workspaceService.workspaceForUser(anyString())).thenReturn(Map.of());
            when(databaseService.getSetting(anyString(), anyString())).thenReturn("");

            ResponseEntity<Map<String, Object>> response = stateController.getState(req);
            assertThat(response.getStatusCodeValue()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
        }
    }
}
