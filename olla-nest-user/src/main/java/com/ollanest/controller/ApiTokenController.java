package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.ApiTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/** API Token management — mint, list, revoke bearer tokens. */
@RestController
@RequestMapping("/api/tokens")
public class ApiTokenController extends BaseController {

    private final ApiTokenService tokenService;

    public ApiTokenController(ApiTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(tokenService.list(user.id));
    }

    @PostMapping
    public ResponseEntity<?> mint(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        String name = (String) body.get("name");
        @SuppressWarnings("unchecked")
        List<String> scopes = (List<String>) body.getOrDefault("scopes", List.of("chat"));
        // Full token is returned once in the response
        return created(tokenService.mint(user.id, name, scopes));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> revoke(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        tokenService.revoke(id, user.id);
        return ok(Map.of("ok", true));
    }

    @DeleteMapping
    public ResponseEntity<?> revokeAll(HttpServletRequest req) {
        User user = requireAuth(req);
        tokenService.revokeAll(user.id);
        return ok(Map.of("ok", true));
    }
}
