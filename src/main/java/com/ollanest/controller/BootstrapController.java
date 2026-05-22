package com.ollanest.controller;

import com.ollanest.config.AppConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/bootstrap")
public class BootstrapController {

    private final JdbcTemplate db;
    private final AppConfig appConfig;

    public BootstrapController(JdbcTemplate db, AppConfig appConfig) {
        this.db = db;
        this.appConfig = appConfig;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> bootstrap() {
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT password_hash FROM users WHERE id = 'u-admin'");
        if (!rows.isEmpty()) {
            String hash = (String) rows.get(0).get("password_hash");
            if (hash != null && BCrypt.checkpw(appConfig.getDefaultAdminPassword(), hash)) {
                return ResponseEntity.ok(Map.of("ready", true, "firstBoot", true));
            }
        }
        return ResponseEntity.ok(Map.of("ready", true, "firstBoot", false));
    }
}
