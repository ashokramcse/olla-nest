package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.VaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Vault (Bitwarden/Vaultwarden CLI) integration API. Admin-only for config/unlock. */
@RestController
@RequestMapping("/api/vault")
public class VaultController extends BaseController {

    private final VaultService vaultService;

    public VaultController(VaultService vaultService) {
        this.vaultService = vaultService;
    }

    @GetMapping
    public ResponseEntity<?> config(HttpServletRequest req) {
        User user = requireAdminUser(req);
        return ok(vaultService.getConfig());
    }

    @PostMapping("/config")
    public ResponseEntity<?> saveConfig(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        requireAdminUser(req);
        vaultService.saveConfig((String) body.get("bw_path"), (String) body.get("server_url"));
        return ok(Map.of("ok", true));
    }

    @PostMapping("/unlock")
    public ResponseEntity<?> unlock(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        requireAdminUser(req);
        String password = (String) body.get("master_password");
        if (password == null || password.isBlank()) return badRequest("master_password is required");
        return ok(vaultService.unlock(password));
    }

    @PostMapping("/lock")
    public ResponseEntity<?> lock(HttpServletRequest req) {
        requireAdminUser(req);
        vaultService.lock();
        return ok(Map.of("ok", true));
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest req) {
        requireAuth(req);
        return ok(Map.of("unlocked", vaultService.isUnlocked()));
    }

    @GetMapping("/item/{name}")
    public ResponseEntity<?> getItem(HttpServletRequest req, @PathVariable String name) {
        requireAdminUser(req);
        return ok(vaultService.getItem(name));
    }
}
