package com.ollanest.config;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Rejects WebSocket handshakes unless the user is authenticated
 * and has the workspace:build right.
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final AuthService authService;

    public WebSocketAuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) return false;
        HttpServletRequest servletReq = ((ServletServerHttpRequest) request).getServletRequest();
        User user = authService.getSessionUser(servletReq);
        if (user == null) return false;
        // Require workspace:build right
        boolean hasRight = "admin".equals(user.role)
            || (user.rights != null && user.rights.contains("workspace:build"));
        return hasRight;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {}
}
