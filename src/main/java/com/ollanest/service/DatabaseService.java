package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Handles schema seeding (default data) after Flyway runs migrations.
 * Uses JdbcTemplate for all DB operations.
 */
@Service
public class DatabaseService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseService.class);
    private final JdbcTemplate db;
    private final AppConfig appConfig;
    private final ObjectMapper mapper;

    public DatabaseService(JdbcTemplate db, AppConfig appConfig, ObjectMapper mapper) {
        this.db = db;
        this.appConfig = appConfig;
        this.mapper = mapper;
    }

    @PostConstruct
    public void seedDatabase() {
        try {
            seedSettings();
            seedDepartments();
            seedGroups();
            seedPermissions();
            seedRoles();
            seedUsers();
            log.info("[db] Database seeding complete.");
        } catch (Exception e) {
            log.error("[db] Seeding error: {}", e.getMessage(), e);
        }
    }

    private int tableCount(String table) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count != null ? count : 0;
    }

    public String getSetting(String key, String fallback) {
        try {
            List<Map<String, Object>> rows = db.queryForList("SELECT value FROM settings WHERE key = ?", key);
            if (rows.isEmpty()) return fallback;
            String value = (String) rows.get(0).get("value");
            return value != null ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    public boolean getSettingBool(String key, boolean fallback) {
        String v = getSetting(key, null);
        if (v == null) return fallback;
        if ("true".equals(v)) return true;
        if ("false".equals(v)) return false;
        return fallback;
    }

    public void setSetting(String key, String value) {
        db.update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", key, value);
    }

    private void seedSettings() {
        if (tableCount("settings") > 0) return;
        String dataDir = appConfig.getDataDir();
        String defaultWorkspace = dataDir + "/workspace";
        String ollamaUrl = System.getenv("OLLAMA_URL");
        if (ollamaUrl == null || ollamaUrl.isBlank()) ollamaUrl = "http://localhost:11434";

        Object[][] defaults = {
            {"activeUserId", "u-admin"},
            {"routerEnabled", "true"},
            {"allowApiModels", "false"},
            {"localOnlyDefault", "true"},
            {"localWritesEnabled", "true"},
            {"workspaceRoot", defaultWorkspace},
            {"localPermissionMode", "default"},
            {"ollamaUrl", ollamaUrl},
            {"apiModelProvider", "not-configured"},
            {"sqlProvider", "sqlite"},
            {"documentProvider", "json-document-store"},
            {"realtimeProvider", "in-memory"},
        };
        for (Object[] kv : defaults) {
            db.update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", kv[0], kv[1]);
        }
    }

    private void seedDepartments() {
        if (tableCount("departments") > 0) return;
        String[][] depts = {
            {"dept-general", "General"},
            {"dept-product", "Product"},
            {"dept-support", "Support"},
        };
        for (String[] d : depts) {
            db.update("INSERT INTO departments (id, name) VALUES (?, ?)", d[0], d[1]);
        }
    }

    private void seedGroups() {
        if (tableCount("groups") > 0) return;
        String[][] groups = {
            {"group-all", "All Employees"},
            {"group-builders", "Builders"},
            {"group-admins", "Admins"},
        };
        for (String[] g : groups) {
            db.update("INSERT INTO groups (id, name) VALUES (?, ?)", g[0], g[1]);
        }
    }

    private void seedPermissions() {
        if (tableCount("permission_catalog") > 0) return;
        Object[][] perms = {
            {"chat:use", "AI Usage", "Use the AI workspace", "low"},
            {"models:local:use", "Model Usage", "Use approved Ollama/local models", "low"},
            {"models:external:use", "Model Usage", "Use external premium AI providers", "high"},
            {"models:coding:use", "Model Usage", "Use coding models and coding workflows", "medium"},
            {"models:reasoning:use", "Model Usage", "Use reasoning models", "medium"},
            {"ollama:models:pull", "Ollama Governance", "Pull models into Ollama", "high"},
            {"ollama:models:import", "Ollama Governance", "Import custom/GGUF models", "high"},
            {"ollama:modelfile:create", "Ollama Governance", "Create models with Modelfiles", "high"},
            {"workspace:build", "Local Work", "Create local workspace files and access terminal shell", "critical"},
            {"files:upload", "AI Workflow", "Upload files to AI workflows", "medium"},
            {"tools:call", "AI Workflow", "Use tool calling", "high"},
            {"internet:use", "AI Workflow", "Use internet-enabled agents", "high"},
            {"agents:run", "AI Workflow", "Run AI agents", "high"},
            {"api:use", "Developer Access", "Use Olla Nest APIs", "medium"},
            {"audit:read", "Governance", "Read audit logs", "medium"},
            {"users:manage", "Administration", "Manage users", "high"},
            {"models:manage", "Administration", "Manage model governance", "high"},
            {"admin:manage", "Administration", "Manage platform settings", "critical"},
        };
        for (Object[] p : perms) {
            db.update("INSERT INTO permission_catalog (key, category, description, risk_level) VALUES (?, ?, ?, ?)",
                p[0], p[1], p[2], p[3]);
        }
    }

    private void seedRoles() {
        if (tableCount("role_catalog") > 0) return;
        Object[][] roles = {
            {"platform-owner", "Platform Owner", "Full control over the AI platform",
                "[\"admin:manage\",\"users:manage\",\"models:manage\",\"audit:read\",\"chat:use\",\"models:local:use\",\"models:external:use\",\"ollama:models:pull\",\"ollama:models:import\",\"ollama:modelfile:create\",\"api:use\",\"agents:run\"]", 1},
            {"ai-infra-admin", "AI Infrastructure Admin", "Manage Ollama infrastructure and model sources",
                "[\"models:manage\",\"models:local:use\",\"ollama:models:pull\",\"ollama:models:import\",\"ollama:modelfile:create\",\"audit:read\"]", 1},
            {"security-admin", "Security Admin", "Manage governance, audit, and risk",
                "[\"users:manage\",\"audit:read\",\"admin:manage\"]", 1},
            {"department-admin", "Department Admin", "Manage department users and access requests",
                "[\"users:manage\",\"audit:read\",\"chat:use\"]", 1},
            {"ai-developer", "AI Developer", "Build with coding models, tools, and local files",
                "[\"chat:use\",\"models:local:use\",\"models:coding:use\",\"workspace:build\",\"files:upload\",\"tools:call\",\"api:use\"]", 1},
            {"ai-analyst", "AI Analyst", "Use analysis and reasoning workflows",
                "[\"chat:use\",\"models:local:use\",\"models:reasoning:use\",\"files:upload\"]", 1},
            {"engineering-user", "Engineering User", "Use coding and local AI models",
                "[\"chat:use\",\"models:local:use\",\"models:coding:use\",\"workspace:build\"]", 1},
            {"research-user", "Research User", "Use reasoning models and knowledge workflows",
                "[\"chat:use\",\"models:local:use\",\"models:reasoning:use\",\"files:upload\"]", 1},
            {"viewer", "Viewer", "Read-only AI workspace visibility",
                "[\"chat:use\"]", 1},
        };
        for (Object[] r : roles) {
            db.update("INSERT INTO role_catalog (id, name, description, permissions, system_role) VALUES (?, ?, ?, ?, ?)",
                r[0], r[1], r[2], r[3], r[4]);
        }
    }

    private void seedUsers() {
        if (tableCount("users") > 0) return;
        String adminHash = BCrypt.hashpw(appConfig.getDefaultAdminPassword(), BCrypt.gensalt(12));
        String userHash = BCrypt.hashpw(appConfig.getDefaultUserPassword(), BCrypt.gensalt(12));
        String adminEmail = appConfig.getDefaultAdminEmail();

        Object[][] users = {
            {"u-admin", "Admin", adminEmail, adminHash, "admin", "[\"admin:manage\",\"chat:use\",\"models:manage\",\"users:manage\"]", "dept-product"},
            {"u-user", "Employee", "employee@ollanest.local", userHash, "user", "[\"chat:use\"]", "dept-general"},
            {"u-builder", "Builder Employee", "builder@ollanest.local", userHash, "user", "[\"chat:use\",\"workspace:build\"]", "dept-product"},
            {"u-support", "Support Employee", "support@ollanest.local", userHash, "user", "[\"chat:use\",\"workspace:review\"]", "dept-support"},
        };
        for (Object[] u : users) {
            db.update("INSERT INTO users (id, name, email, password_hash, role, rights, department_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                u[0], u[1], u[2], u[3], u[4], u[5], u[6]);
        }

        // Group memberships
        String[][] memberships = {
            {"u-admin", "group-admins"},
            {"u-admin", "group-all"},
            {"u-user", "group-all"},
            {"u-builder", "group-all"},
            {"u-builder", "group-builders"},
            {"u-support", "group-all"},
        };
        for (String[] m : memberships) {
            db.update("INSERT OR IGNORE INTO user_groups (user_id, group_id) VALUES (?, ?)", m[0], m[1]);
        }
    }
}
