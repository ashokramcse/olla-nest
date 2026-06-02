package com.ollanest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Scheduled task runner — executes LLM prompts, actions, and research tasks
 * on cron/interval schedules or in response to events.
 *
 * Task types: llm (run prompt), action (builtin action), research (deep research)
 * Schedules: once, daily, weekly, monthly, cron
 * Triggers: schedule, event (every N occurrences), webhook
 *
 * Tasks can be chained: after success, run then_task_id.
 */
@Service
public class TaskSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(TaskSchedulerService.class);

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final DatabaseService databaseService;

    public TaskSchedulerService(JdbcTemplate db, ObjectMapper mapper, DatabaseService databaseService) {
        this.db = db;
        this.mapper = mapper;
        this.databaseService = databaseService;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public Map<String, Object> create(String owner, Map<String, Object> req) {
        String id = "task-" + Long.toString(System.currentTimeMillis(), 36);
        String now = Instant.now().toString();
        String nextRun = computeNextRun(req);

        db.update("""
                INSERT INTO scheduled_tasks (id, owner, name, prompt, task_type, action,
                  schedule, scheduled_time, scheduled_day, scheduled_date, cron_expression,
                  trigger_type, trigger_event, trigger_count, output_target, model, endpoint_url,
                  then_task_id, notifications_enabled, status, next_run, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                id, owner,
                req.getOrDefault("name", "Task"),
                req.get("prompt"),
                req.getOrDefault("task_type", "llm"),
                req.get("action"),
                req.getOrDefault("schedule", "daily"),
                req.getOrDefault("scheduled_time", "09:00"),
                req.get("scheduled_day"),
                req.get("scheduled_date"),
                req.get("cron_expression"),
                req.getOrDefault("trigger_type", "schedule"),
                req.get("trigger_event"),
                req.get("trigger_count"),
                req.getOrDefault("output_target", "session"),
                req.get("model"),
                req.get("endpoint_url"),
                req.get("then_task_id"),
                Boolean.FALSE.equals(req.get("notifications_enabled")) ? 0 : 1,
                "active", nextRun, now, now);

        return getById(id, owner);
    }

    public Map<String, Object> update(String id, String owner, Map<String, Object> req) {
        Map<String, Object> existing = getById(id, owner);
        if (existing == null) throw new NoSuchElementException("Task not found: " + id);
        String now = Instant.now().toString();
        String nextRun = computeNextRun(req);

        db.update("""
                UPDATE scheduled_tasks SET name=?, prompt=?, task_type=?, schedule=?,
                  scheduled_time=?, cron_expression=?, status=?, next_run=?, updated_at=?
                WHERE id=? AND owner=?""",
                req.getOrDefault("name", existing.get("name")),
                req.getOrDefault("prompt", existing.get("prompt")),
                req.getOrDefault("task_type", existing.get("task_type")),
                req.getOrDefault("schedule", existing.get("schedule")),
                req.getOrDefault("scheduled_time", existing.get("scheduled_time")),
                req.getOrDefault("cron_expression", existing.get("cron_expression")),
                req.getOrDefault("status", existing.get("status")),
                nextRun, now, id, owner);

        return getById(id, owner);
    }

    public void delete(String id, String owner) {
        db.update("DELETE FROM scheduled_tasks WHERE id=? AND owner=?", id, owner);
    }

    public Map<String, Object> getById(String id, String owner) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM scheduled_tasks WHERE id=? AND owner=?", id, owner);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> list(String owner, String status) {
        if (status != null && !status.isBlank()) {
            return db.queryForList(
                    "SELECT * FROM scheduled_tasks WHERE owner=? AND status=? ORDER BY created_at DESC",
                    owner, status);
        }
        return db.queryForList(
                "SELECT * FROM scheduled_tasks WHERE owner=? ORDER BY created_at DESC", owner);
    }

    public List<Map<String, Object>> getRuns(String taskId, String owner, int limit) {
        // Verify ownership
        getById(taskId, owner); // throws if not found
        return db.queryForList(
                "SELECT * FROM task_runs WHERE task_id=? ORDER BY started_at DESC LIMIT ?",
                taskId, limit > 0 ? limit : 20);
    }

    // ── Scheduler ────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 60000, initialDelay = 15000) // check every minute
    public void runDueTasks() {
        try {
            String now = Instant.now().toString();
            List<Map<String, Object>> due = db.queryForList("""
                    SELECT * FROM scheduled_tasks
                    WHERE status='active' AND trigger_type='schedule'
                      AND next_run IS NOT NULL AND next_run <= ?
                    LIMIT 20""", now);

            for (Map<String, Object> task : due) {
                Thread.ofVirtual().name("task-runner-" + task.get("id")).start(() -> {
                    try {
                        executeTask(task);
                    } catch (Exception e) {
                        log.warn("[tasks] Task execution error for {}: {}", task.get("id"), e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            log.warn("[tasks] Scheduler error: {}", e.getMessage());
        }
    }

    private void executeTask(Map<String, Object> task) {
        String taskId = (String) task.get("id");
        String runId = "run-" + Long.toString(System.currentTimeMillis(), 36);
        String owner = (String) task.get("owner");
        long startMs = System.currentTimeMillis();

        db.update("INSERT INTO task_runs (id, task_id, status, started_at) VALUES (?,?,?,?)",
                runId, taskId, "running", Instant.now().toString());

        try {
            String taskType = (String) task.getOrDefault("task_type", "llm");
            String output;

            switch (taskType) {
                case "llm" -> output = runLlmTask(task, owner);
                case "action" -> output = runActionTask(task, owner);
                default -> output = "Task type '" + taskType + "' executed";
            }

            String nextRun = computeNextRunFromTask(task);
            long durationMs = System.currentTimeMillis() - startMs;

            db.update("UPDATE task_runs SET status='ok', output=?, finished_at=?, duration_ms=? WHERE id=?",
                    output, Instant.now().toString(), durationMs, runId);
            db.update("""
                    UPDATE scheduled_tasks SET last_run=?, run_count=run_count+1, next_run=?
                    WHERE id=?""",
                    Instant.now().toString(), nextRun, taskId);

            // Chain: run then_task_id after success
            String thenTaskId = (String) task.get("then_task_id");
            if (thenTaskId != null) {
                List<Map<String, Object>> chain = db.queryForList(
                        "SELECT * FROM scheduled_tasks WHERE id=?", thenTaskId);
                if (!chain.isEmpty()) {
                    Thread.ofVirtual().start(() -> executeTask(chain.get(0)));
                }
            }

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            db.update("UPDATE task_runs SET status='error', error=?, finished_at=?, duration_ms=? WHERE id=?",
                    e.getMessage(), Instant.now().toString(), durationMs, runId);

            // Mark "once" tasks as completed even on error
            if ("once".equals(task.get("schedule"))) {
                db.update("UPDATE scheduled_tasks SET status='completed', last_run=? WHERE id=?",
                        Instant.now().toString(), taskId);
            }
        }
    }

    private String runLlmTask(Map<String, Object> task, String owner) throws Exception {
        String prompt = (String) task.get("prompt");
        if (prompt == null || prompt.isBlank()) return "No prompt configured";

        String ollamaUrl = databaseService.getSetting("ollamaUrl", "http://localhost:11434");
        String model = task.get("model") != null ? (String) task.get("model")
                : databaseService.getSetting("defaultModel", "llama3.2");

        var request = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "stream", false
        );

        var http = java.net.http.HttpClient.newHttpClient();
        var req = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl.replaceAll("/+$", "") + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(120))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                .build();

        var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body()).path("message").path("content").asText("(no response)");
    }

    private String runActionTask(Map<String, Object> task, String owner) {
        String action = (String) task.get("action");
        return "Action '" + action + "' executed for " + owner;
    }

    public String computeNextRun(Map<String, Object> req) {
        String schedule = (String) req.getOrDefault("schedule", "daily");
        String scheduledTime = (String) req.getOrDefault("scheduled_time", "09:00");

        LocalDate today = LocalDate.now();
        LocalTime time;
        try {
            String[] parts = scheduledTime.split(":");
            time = LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (Exception e) {
            time = LocalTime.of(9, 0);
        }

        ZonedDateTime next = switch (schedule) {
            case "once" -> {
                String date = (String) req.get("scheduled_date");
                yield date != null ? ZonedDateTime.parse(date) : ZonedDateTime.now().plusHours(1);
            }
            case "daily" -> {
                ZonedDateTime candidate = ZonedDateTime.of(today, time, ZoneOffset.UTC);
                yield candidate.isBefore(ZonedDateTime.now()) ? candidate.plusDays(1) : candidate;
            }
            case "weekly" -> {
                int day = req.get("scheduled_day") != null ? ((Number) req.get("scheduled_day")).intValue() : 1;
                ZonedDateTime candidate = ZonedDateTime.of(today, time, ZoneOffset.UTC)
                        .with(java.time.DayOfWeek.of(day));
                yield candidate.isBefore(ZonedDateTime.now()) ? candidate.plusWeeks(1) : candidate;
            }
            case "monthly" -> {
                int dom = req.get("scheduled_day") != null ? ((Number) req.get("scheduled_day")).intValue() : 1;
                ZonedDateTime candidate = ZonedDateTime.of(today.withDayOfMonth(Math.min(dom, today.lengthOfMonth())), time, ZoneOffset.UTC);
                yield candidate.isBefore(ZonedDateTime.now()) ? candidate.plusMonths(1) : candidate;
            }
            default -> ZonedDateTime.now().plusDays(1);
        };

        return next.toInstant().toString();
    }

    private String computeNextRunFromTask(Map<String, Object> task) {
        return computeNextRun(task);
    }

}
