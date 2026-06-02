package com.ollanest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Bitwarden/Vaultwarden CLI integration.
 *
 * Locates the `bw` CLI, unlocks the vault with a master password, stores the
 * BW_SESSION key encrypted in the DB. The agent can retrieve secrets via the
 * session key. The key file is stored at mode 0600 on POSIX.
 */
@Service
public class VaultService {

    private static final Logger log = LoggerFactory.getLogger(VaultService.class);

    private final JdbcTemplate db;
    private final CryptoService cryptoService;

    public VaultService(JdbcTemplate db, CryptoService cryptoService) {
        this.db = db;
        this.cryptoService = cryptoService;
    }

    public Map<String, Object> getConfig() {
        var rows = db.queryForList("SELECT id, bw_path, server_url, enabled FROM vault_config WHERE id='singleton'");
        if (rows.isEmpty()) return Map.of("enabled", false);
        Map<String, Object> r = new LinkedHashMap<>(rows.get(0));
        r.remove("session_enc"); // never expose
        return r;
    }

    public void saveConfig(String bwPath, String serverUrl) {
        String now = Instant.now().toString();
        int updated = db.update("UPDATE vault_config SET bw_path=?, server_url=?, updated_at=? WHERE id='singleton'",
                bwPath != null ? bwPath : "bw", serverUrl, now);
        if (updated == 0) {
            db.update("INSERT INTO vault_config (id, bw_path, server_url, enabled, updated_at) VALUES ('singleton',?,?,0,?)",
                    bwPath != null ? bwPath : "bw", serverUrl, now);
        }
    }

    public Map<String, Object> unlock(String masterPassword) {
        String bwPath = getBwPath();
        try {
            ProcessBuilder pb = new ProcessBuilder(bwPath, "unlock", "--passwordenv", "BW_MASTER")
                    .redirectErrorStream(true);
            pb.environment().put("BW_MASTER", masterPassword);
            String serverUrl = getServerUrl();
            if (serverUrl != null && !serverUrl.isBlank()) {
                pb.environment().put("BW_SERVER", serverUrl);
            }
            Process p = pb.start();
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return Map.of("ok", false, "error", "Timeout unlocking vault"); }

            String output = new String(p.getInputStream().readAllBytes());
            // Parse BW_SESSION from output: "export BW_SESSION="xxxx""
            String session = null;
            for (String line : output.split("\n")) {
                if (line.contains("BW_SESSION=")) {
                    session = line.replaceAll(".*BW_SESSION=\"?([^\"\\s]+)\"?.*", "$1").trim();
                    break;
                }
            }

            if (session == null || session.contains("BW_SESSION")) {
                return Map.of("ok", false, "error", "Failed to unlock: " + output.substring(0, Math.min(200, output.length())));
            }

            String encSession = cryptoService.encryptKey(session);
            db.update("UPDATE vault_config SET session_enc=?, enabled=1, updated_at=? WHERE id='singleton'",
                    encSession, Instant.now().toString());
            return Map.of("ok", true, "message", "Vault unlocked successfully");

        } catch (Exception e) {
            log.warn("[vault] Unlock failed: {}", e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    public void lock() {
        db.update("UPDATE vault_config SET session_enc=NULL, enabled=0, updated_at=? WHERE id='singleton'",
                Instant.now().toString());
    }

    public boolean isUnlocked() {
        var rows = db.queryForList("SELECT session_enc, enabled FROM vault_config WHERE id='singleton'");
        if (rows.isEmpty()) return false;
        return rows.get(0).get("session_enc") != null && Integer.valueOf(1).equals(rows.get(0).get("enabled"));
    }

    public Map<String, Object> getItem(String name) {
        if (!isUnlocked()) return Map.of("ok", false, "error", "Vault is locked");
        String session = getSession();
        if (session == null) return Map.of("ok", false, "error", "No active session");
        try {
            ProcessBuilder pb = new ProcessBuilder(getBwPath(), "get", "item", name)
                    .redirectErrorStream(true);
            pb.environment().put("BW_SESSION", session);
            Process p = pb.start();
            boolean finished = p.waitFor(15, TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return Map.of("ok", false, "error", "Timeout"); }
            String output = new String(p.getInputStream().readAllBytes());
            return Map.of("ok", true, "item", output);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private String getBwPath() {
        var rows = db.queryForList("SELECT bw_path FROM vault_config WHERE id='singleton'");
        return rows.isEmpty() ? "bw" : (String) rows.get(0).getOrDefault("bw_path", "bw");
    }

    private String getServerUrl() {
        var rows = db.queryForList("SELECT server_url FROM vault_config WHERE id='singleton'");
        return rows.isEmpty() ? null : (String) rows.get(0).get("server_url");
    }

    private String getSession() {
        try {
            var rows = db.queryForList("SELECT session_enc FROM vault_config WHERE id='singleton'");
            if (rows.isEmpty() || rows.get(0).get("session_enc") == null) return null;
            return cryptoService.decryptKey((String) rows.get(0).get("session_enc"));
        } catch (Exception e) { return null; }
    }
}
