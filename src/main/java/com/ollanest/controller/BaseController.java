package com.ollanest.controller;

import com.ollanest.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Base controller with shared auth helpers.
 */
public abstract class BaseController {

    protected User getUser(HttpServletRequest req) {
        return (User) req.getAttribute("authenticatedUser");
    }

    protected ResponseEntity<Map<String, Object>> requireAuth(HttpServletRequest req) {
        if (getUser(req) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Login required"));
        }
        return null;
    }

    protected ResponseEntity<Map<String, Object>> requireAdmin(HttpServletRequest req) {
        User user = getUser(req);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Login required"));
        }
        if (!"admin".equals(user.role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Admin access required"));
        }
        // CSRF guard on state-changing requests
        if (!"GET".equals(req.getMethod()) && req.getHeader("x-requested-with") == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Forbidden: missing CSRF header"));
        }
        return null;
    }

    protected ResponseEntity<Map<String, Object>> requireAuthWithCsrf(HttpServletRequest req) {
        User user = getUser(req);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Login required"));
        }
        if (!"GET".equals(req.getMethod()) && req.getHeader("x-requested-with") == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Forbidden: missing CSRF header"));
        }
        return null;
    }

    protected boolean isCsrfOk(HttpServletRequest req) {
        return "GET".equals(req.getMethod()) || req.getHeader("x-requested-with") != null;
    }
}
