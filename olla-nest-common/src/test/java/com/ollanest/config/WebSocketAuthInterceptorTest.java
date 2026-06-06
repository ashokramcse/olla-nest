package com.ollanest.config;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;
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
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * OCD-level unit tests for {@link WebSocketAuthInterceptor}.
 *
 * <p>Covers authentication and authorization checks in beforeHandshake().
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebSocketAuthInterceptor — unit tests")
class WebSocketAuthInterceptorTest {

    @Mock AuthService authService;

    @InjectMocks WebSocketAuthInterceptor interceptor;

    @Mock ServerHttpResponse response;
    @Mock WebSocketHandler wsHandler;

    private ServletServerHttpRequest mockRequest(HttpServletRequest servletReq) {
        ServletServerHttpRequest req = mock(ServletServerHttpRequest.class);
        when(req.getServletRequest()).thenReturn(servletReq);
        return req;
    }

    // ── beforeHandshake() ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("beforeHandshake()")
    class BeforeHandshake {

        @Test
        @DisplayName("returns false when no session token (not authenticated)")
        void falseWhenUnauthenticated() throws Exception {
            HttpServletRequest servletReq = mock(HttpServletRequest.class);
            ServletServerHttpRequest request = mockRequest(servletReq);
            when(authService.getSessionUser(servletReq)).thenReturn(null);

            boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when user has no workspace:build right and is not admin")
        void falseWhenNoRights() throws Exception {
            User user = UserFactory.regularUser();
            user.rights = Arrays.asList("chat:use");

            HttpServletRequest servletReq = mock(HttpServletRequest.class);
            ServletServerHttpRequest request = mockRequest(servletReq);
            when(authService.getSessionUser(servletReq)).thenReturn(user);

            boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns true when user is admin")
        void trueWhenAdmin() throws Exception {
            User user = UserFactory.admin();

            HttpServletRequest servletReq = mock(HttpServletRequest.class);
            ServletServerHttpRequest request = mockRequest(servletReq);
            when(authService.getSessionUser(servletReq)).thenReturn(user);

            boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns true when user has workspace:build right")
        void trueWhenHasWorkspaceBuild() throws Exception {
            User user = UserFactory.regularUser();
            user.rights = Arrays.asList("chat:use", "workspace:build");

            HttpServletRequest servletReq = mock(HttpServletRequest.class);
            ServletServerHttpRequest request = mockRequest(servletReq);
            when(authService.getSessionUser(servletReq)).thenReturn(user);

            boolean result = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("admin user has userId stored in attributes")
        void adminUserIdInAttributes() throws Exception {
            User user = UserFactory.admin();

            HttpServletRequest servletReq = mock(HttpServletRequest.class);
            ServletServerHttpRequest request = mockRequest(servletReq);
            when(authService.getSessionUser(servletReq)).thenReturn(user);

            Map<String, Object> attrs = new HashMap<>();
            interceptor.beforeHandshake(request, response, wsHandler, attrs);
            assertThat(attrs.get("authenticatedUserId")).isEqualTo(user.id);
        }
    }
}
