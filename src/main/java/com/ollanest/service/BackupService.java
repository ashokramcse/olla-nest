package com.ollanest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Scheduled SQLite backup using VACUUM INTO.
 * Keeps the last 7 backups.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private final JdbcTemplate db;

    @Value("${app.data-dir:./data}")
    private String dataDir;

    public BackupService(JdbcTemplate db) {
        this.db = db;
    }

    // Run daily at 3 AM
    @Scheduled(cron = "0 0 3 * * *")
    public void scheduledBackup() {
        try {
            runBackup();
        } catch (Exception e) {
            log.error("[backup] Scheduled backup failed: {}", e.getMessage());
        }
    }

    public java.util.Map<String, Object> runBackup() {
        File backupDir = new File(dataDir, "backups");
        if (!backupDir.exists()) backupDir.mkdirs();

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"));
        File dest = new File(backupDir, "olla-nest-" + ts + ".sqlite");

        try {
            db.execute("VACUUM INTO '" + dest.getAbsolutePath().replace("'", "''") + "'");

            // Keep only last 7 backups
            File[] backups = backupDir.listFiles(f -> f.getName().endsWith(".sqlite"));
            if (backups != null && backups.length > 7) {
                Arrays.sort(backups, Comparator.comparing(File::getName));
                for (int i = 0; i < backups.length - 7; i++) {
                    backups[i].delete();
                }
            }
            log.info("[backup] Backup written to {}", dest.getAbsolutePath());
            return java.util.Map.of("ok", true, "file", dest.getAbsolutePath());
        } catch (Exception e) {
            log.error("[backup] Backup failed: {}", e.getMessage());
            return java.util.Map.of("ok", false, "error", e.getMessage());
        }
    }
}
