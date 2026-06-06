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

    /**
     * Registers a new background job and persists it with {@code running} status.
     *
     * @param owner   the user ID who owns this job
     * @param jobType a short type identifier (e.g. {@code email_poll}, {@code connector_sync})
     * @param name    human-readable job name
     * @return the generated job ID (prefix {@code job-})
     */
    public String register(String owner, String jobType, String name) {
        String id = "job-" + Long.toString(System.currentTimeMillis(), 36) + "-" + UUID.randomUUID().toString().substring(0, 6);
        db.update("INSERT INTO background_jobs (id, owner, job_type, name, status, progress, started_at) VALUES (?,?,?,?,?,?,?)",
                id, owner, jobType, name, "running", 0, Instant.now().toString());
        return id;
    }

    /**
     * Updates the progress percentage and status message of a running job.
     *
     * @param id       the job ID
     * @param progress progress value 0–100
     * @param msg      human-readable status message; may be {@code null}
     */
    public void updateProgress(String id, int progress, String msg) {
        db.update("UPDATE background_jobs SET progress=?, progress_msg=? WHERE id=?", progress, msg, id);
    }

    /**
     * Marks a job as completed, stores the result, and removes its thread registration.
     *
     * @param id     the job ID
     * @param result optional result object to serialize as JSON; may be {@code null}
     */
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

    /**
     * Marks a job as failed with the given error message and removes its thread registration.
     *
     * @param id    the job ID
     * @param error the error message to persist
     */
    public void fail(String id, String error) {
        db.update("UPDATE background_jobs SET status='error', error=?, finished_at=? WHERE id=?",
                error, Instant.now().toString(), id);
        runningThreads.remove(id);
    }

    /**
     * Cancels the background job by interrupting its thread (if registered) and marking it cancelled.
     *
     * @param id the job ID
     * @return {@code true} if a thread was found and interrupted; {@code false} otherwise
     */
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

    /**
     * Registers the thread executing this job so it can be interrupted on cancel.
     *
     * @param id     the job ID
     * @param thread the thread running the job
     */
    public void registerThread(String id, Thread thread) {
        runningThreads.put(id, thread);
    }

    /**
     * Returns all currently running jobs across all owners, ordered newest-first.
     *
     * @return list of running job rows (id, owner, job_type, name, progress, started_at); never null
     */
    public List<Map<String, Object>> listActive() {
        return db.queryForList(
                "SELECT id, owner, job_type, name, status, progress, progress_msg, started_at FROM background_jobs WHERE status='running' ORDER BY started_at DESC");
    }

    /**
     * Returns jobs for a specific owner, newest first.
     *
     * @param owner the user ID
     * @param limit maximum results; {@code 0} or negative defaults to 20
     * @return list of job rows; never null
     */
    public List<Map<String, Object>> listByOwner(String owner, int limit) {
        return db.queryForList(
                "SELECT id, owner, job_type, name, status, progress, progress_msg, error, started_at, finished_at FROM background_jobs WHERE owner=? ORDER BY started_at DESC LIMIT ?",
                owner, limit > 0 ? limit : 20);
    }

    /**
     * Returns the full job record by ID, including result and error fields.
     *
     * @param id the job ID
     * @return the job record, or {@code null} if not found
     */
    public Map<String, Object> getById(String id) {
        var rows = db.queryForList("SELECT * FROM background_jobs WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Scheduled hourly cleanup — deletes completed, errored, and cancelled jobs older than 7 days.
     */
    @Scheduled(fixedDelay = 3600000, initialDelay = 300000) // hourly cleanup
    public void cleanOldJobs() {
        try {
            db.update("DELETE FROM background_jobs WHERE status IN ('completed','error','cancelled') AND finished_at < datetime('now', '-7 days')");
        } catch (Exception e) {
            log.warn("[bgjobs] Cleanup error: {}", e.getMessage());
        }
    }
}
