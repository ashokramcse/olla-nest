package com.ollanest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for long-running background jobs (downloads, research, email polling,
 * connector syncs). Tracks progress, allows cancellation, provides admin visibility.
 *
 * Jobs are persisted to the background_jobs table so they survive server restart
 * visibility-wise (actual running jobs do not survive restart, but history does).
 */
@Service
public class BackgroundJobService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobService.class);

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    // jobId -> Thread (for cancellation)
    private final Map<String, Thread> runningThreads = new ConcurrentHashMap<>();

    public BackgroundJobService(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public String register(String owner, String jobType, String name) {
        String id = "job-" + Long.toString(System.currentTimeMillis(), 36) + "-" + UUID.randomUUID().toString().substring(0, 6);
        db.update("INSERT INTO background_jobs (id, owner, job_type, name, status, progress, started_at) VALUES (?,?,?,?,?,?,?)",
                id, owner, jobType, name, "running", 0, Instant.now().toString());
        return id;
    }

    public void updateProgress(String id, int progress, String msg) {
        db.update("UPDATE background_jobs SET progress=?, progress_msg=? WHERE id=?", progress, msg, id);
    }

    public void complete(String id, Object result) {
        try {
            String resultJson = result != null ? mapper.writeValueAsString(result) : null;
            db.update("UPDATE background_jobs SET status='completed', progress=100, result_json=?, finished_at=? WHERE id=?",
                    resultJson, Instant.now().toString(), id);
        } catch (Exception e) {
            db.update("UPDATE background_jobs SET status='completed', progress=100, finished_at=? WHERE id=?",
                    Instant.now().toString(), id);
        }
        runningThreads.remove(id);
    }

    public void fail(String id, String error) {
        db.update("UPDATE background_jobs SET status='error', error=?, finished_at=? WHERE id=?",
                error, Instant.now().toString(), id);
        runningThreads.remove(id);
    }

    public boolean cancel(String id) {
        Thread t = runningThreads.get(id);
        if (t != null) {
            t.interrupt();
            runningThreads.remove(id);
        }
        db.update("UPDATE background_jobs SET status='cancelled', finished_at=? WHERE id=?",
                Instant.now().toString(), id);
        return t != null;
    }

    public void registerThread(String id, Thread thread) {
        runningThreads.put(id, thread);
    }

    public List<Map<String, Object>> listActive() {
        return db.queryForList(
                "SELECT id, owner, job_type, name, status, progress, progress_msg, started_at FROM background_jobs WHERE status='running' ORDER BY started_at DESC");
    }

    public List<Map<String, Object>> listByOwner(String owner, int limit) {
        return db.queryForList(
                "SELECT id, owner, job_type, name, status, progress, progress_msg, error, started_at, finished_at FROM background_jobs WHERE owner=? ORDER BY started_at DESC LIMIT ?",
                owner, limit > 0 ? limit : 20);
    }

    public Map<String, Object> getById(String id) {
        var rows = db.queryForList("SELECT * FROM background_jobs WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 300000) // hourly cleanup
    public void cleanOldJobs() {
        try {
            db.update("DELETE FROM background_jobs WHERE status IN ('completed','error','cancelled') AND finished_at < datetime('now', '-7 days')");
        } catch (Exception e) {
            log.warn("[bgjobs] Cleanup error: {}", e.getMessage());
        }
    }
}
