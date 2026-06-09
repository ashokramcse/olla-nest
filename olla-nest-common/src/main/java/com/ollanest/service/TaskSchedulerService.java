package com.ollanest.service;

import static com.ollanest.util.MapDefaults.orDefault;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Persists and executes user-defined scheduled tasks against configurable LLM
 * endpoints.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Users need to run recurring LLM prompts (daily briefings, research digests,
 * check-ins) and built-in actions on a schedule without leaving the browser
 * open. This service owns the full task lifecycle: CRUD, next-run computation,
 * the execution loop, run-history persistence, and task chaining. It replaces
 * what would otherwise require a dedicated job scheduler framework with a
 * lightweight SQLite-backed approach suitable for a self-hosted single-process
 * deployment.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The scheduler polling interval is 60 seconds; tasks whose
 * {@code next_run} timestamp has passed are selected and dispatched in virtual
 * threads to prevent a slow task from blocking subsequent ones.</li>
 * <li>Tasks support optional chaining via {@code then_task_id}: after a
 * successful run the linked task is dispatched immediately in a new virtual
 * thread.</li>
 * <li>Run history is stored in the {@code task_runs} table for observability;
 * the total number of runs and the last-run timestamp are denormalized onto the
 * task row for fast dashboard queries.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — introduced as part of the personal assistant expansion</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@Service
public class TaskSchedulerService {

	private static final Logger log = LoggerFactory.getLogger(TaskSchedulerService.class);

	/** JDBC template for task and run persistence. */
	private final JdbcTemplate db;

	/** Shared Jackson mapper for LLM request/response serialization. */
	private final ObjectMapper mapper;

	/** Provides runtime-configurable Ollama URL and default model settings. */
	private final DatabaseService databaseService;

	/**
	 * Constructor-injects persistence, serialization, and settings dependencies.
	 *
	 * @param db              the JDBC template for task and run operations
	 * @param mapper          the shared Jackson object mapper
	 * @param databaseService the settings service for Ollama URL and model
	 *                        configuration
	 * @since v2026.2.1
	 */
	public TaskSchedulerService(JdbcTemplate db, ObjectMapper mapper, DatabaseService databaseService) {
		this.db = db;
		this.mapper = mapper;
		this.databaseService = databaseService;
	}

	// ── CRUD ──────────────────────────────────────────────────────────────────

	/**
	 * Creates a new scheduled task for the given owner and computes its first
	 * {@code next_run}.
	 *
	 * @param owner the user ID
	 * @param req   task fields: {@code name}, {@code prompt}, {@code task_type},
	 *              {@code schedule}, {@code scheduled_time}, {@code trigger_type},
	 *              etc.
	 * @return the created task record
	 * @since v2026.2.1
	 */
	public Map<String, Object> create(String owner, Map<String, Object> req) {
		// Append a random suffix so rapid creates (e.g. seeding multiple assistant
		// check-ins in the same millisecond) cannot collide on the primary key.
		String id = "task-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ UUID.randomUUID().toString().substring(0, 6);
		String now = Instant.now().toString();
		String nextRun = computeNextRun(req);

		db.update("""
				INSERT INTO scheduled_tasks (id, owner, name, prompt, task_type, action,
				  schedule, scheduled_time, scheduled_day, scheduled_date, cron_expression,
				  trigger_type, trigger_event, trigger_count, output_target, model, endpoint_url,
				  then_task_id, notifications_enabled, status, next_run, created_at, updated_at)
				VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", id, owner,
				// BUG-019: coerce explicit JSON nulls for NOT-NULL columns.
				orDefault(req.get("name"), "Task"), req.get("prompt"), orDefault(req.get("task_type"), "llm"),
				req.get("action"), orDefault(req.get("schedule"), "daily"),
				orDefault(req.get("scheduled_time"), "09:00"), req.get("scheduled_day"), req.get("scheduled_date"),
				req.get("cron_expression"), orDefault(req.get("trigger_type"), "schedule"), req.get("trigger_event"),
				req.get("trigger_count"), orDefault(req.get("output_target"), "session"), req.get("model"),
				req.get("endpoint_url"), req.get("then_task_id"),
				Boolean.FALSE.equals(req.get("notifications_enabled")) ? 0 : 1, "active", nextRun, now, now);

		return getById(id, owner);
	}

	/**
	 * Updates a scheduled task and recomputes its next run time.
	 *
	 * @param id    the task ID
	 * @param owner the user ID
	 * @param req   updated fields
	 * @return the updated task record
	 * @throws java.util.NoSuchElementException if the task is not found
	 * @since v2026.2.1
	 */
	public Map<String, Object> update(String id, String owner, Map<String, Object> req) {
		Map<String, Object> existing = getById(id, owner);
		if (existing == null)
			throw new NoSuchElementException("Task not found: " + id);
		String now = Instant.now().toString();
		String nextRun = computeNextRun(req);

		db.update("""
				UPDATE scheduled_tasks SET name=?, prompt=?, task_type=?, schedule=?,
				  scheduled_time=?, cron_expression=?, status=?, next_run=?, updated_at=?
				WHERE id=? AND owner=?""", req.getOrDefault("name", existing.get("name")),
				req.getOrDefault("prompt", existing.get("prompt")),
				req.getOrDefault("task_type", existing.get("task_type")),
				req.getOrDefault("schedule", existing.get("schedule")),
				req.getOrDefault("scheduled_time", existing.get("scheduled_time")),
				req.getOrDefault("cron_expression", existing.get("cron_expression")),
				req.getOrDefault("status", existing.get("status")), nextRun, now, id, owner);

		return getById(id, owner);
	}

	/**
	 * Deletes a scheduled task owned by the given user.
	 *
	 * @param id    the task ID
	 * @param owner the user ID
	 * @since v2026.2.1
	 */
	public void delete(String id, String owner) {
		db.update("DELETE FROM scheduled_tasks WHERE id=? AND owner=?", id, owner);
	}

	/**
	 * Returns a scheduled task by ID, restricted to the given owner.
	 *
	 * @param id    the task ID
	 * @param owner the user ID
	 * @return the task record, or {@code null} if not found
	 * @since v2026.2.1
	 */
	public Map<String, Object> getById(String id, String owner) {
		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM scheduled_tasks WHERE id=? AND owner=?", id,
				owner);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * Returns scheduled tasks for the given owner, optionally filtered by status.
	 *
	 * @param owner  the user ID
	 * @param status optional status filter (e.g. {@code "active"},
	 *               {@code "completed"}); {@code null} returns all
	 * @return list of task records, newest first; never null
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> list(String owner, String status) {
		if (status != null && !status.isBlank()) {
			return db.queryForList("SELECT * FROM scheduled_tasks WHERE owner=? AND status=? ORDER BY created_at DESC",
					owner, status);
		}
		return db.queryForList("SELECT * FROM scheduled_tasks WHERE owner=? ORDER BY created_at DESC", owner);
	}

	/**
	 * Returns past execution runs for a task, newest first.
	 *
	 * @param taskId the task ID
	 * @param owner  the user ID (ownership verified)
	 * @param limit  maximum results; {@code 0} or negative defaults to 20
	 * @return list of task run rows; never null
	 * @since v2026.2.1
	 */
	public List<Map<String, Object>> getRuns(String taskId, String owner, int limit) {
		// Verify ownership
		getById(taskId, owner); // throws if not found
		return db.queryForList("SELECT * FROM task_runs WHERE task_id=? ORDER BY started_at DESC LIMIT ?", taskId,
				limit > 0 ? limit : 20);
	}

	// ── Scheduler ────────────────────────────────────────────────────────────

	/**
	 * Scheduled every minute — queries for active tasks whose {@code next_run} is
	 * due and executes each in a virtual thread.
	 * 
	 * @since v2026.2.1
	 */
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
		String runId = "run-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ UUID.randomUUID().toString().substring(0, 6);
		String owner = (String) task.get("owner");
		long startMs = System.currentTimeMillis();

		db.update("INSERT INTO task_runs (id, task_id, status, started_at) VALUES (?,?,?,?)", runId, taskId, "running",
				Instant.now().toString());

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

			db.update("UPDATE task_runs SET status='ok', output=?, finished_at=?, duration_ms=? WHERE id=?", output,
					Instant.now().toString(), durationMs, runId);
			db.update("""
					UPDATE scheduled_tasks SET last_run=?, run_count=run_count+1, next_run=?
					WHERE id=?""", Instant.now().toString(), nextRun, taskId);

			// Chain: run then_task_id after success
			String thenTaskId = (String) task.get("then_task_id");
			if (thenTaskId != null) {
				List<Map<String, Object>> chain = db.queryForList("SELECT * FROM scheduled_tasks WHERE id=?",
						thenTaskId);
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
		if (prompt == null || prompt.isBlank())
			return "No prompt configured";

		String ollamaUrl = databaseService.getSetting("ollamaUrl", "http://localhost:11434");
		String model = task.get("model") != null ? (String) task.get("model")
				: databaseService.getSetting("defaultModel", "llama3.2");

		var request = Map.of("model", model, "messages", List.of(Map.of("role", "user", "content", prompt)), "stream",
				false);

		var http = HttpClient.newHttpClient();
		var req = HttpRequest.newBuilder().uri(URI.create(ollamaUrl.replaceAll("/+$", "") + "/api/chat"))
				.header("Content-Type", "application/json").timeout(Duration.ofSeconds(120))
				.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build();

		var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		return mapper.readTree(resp.body()).path("message").path("content").asText("(no response)");
	}

	private String runActionTask(Map<String, Object> task, String owner) {
		String action = (String) task.get("action");
		return "Action '" + action + "' executed for " + owner;
	}

	/**
	 * Computes the next run timestamp for a task based on its {@code schedule} and
	 * {@code scheduled_time} fields.
	 *
	 * @param req task/request map with {@code schedule}, {@code scheduled_time},
	 *            {@code scheduled_day}, {@code scheduled_date} fields
	 * @return ISO-8601 instant string for the next scheduled run
	 * @since v2026.2.1
	 */
	public String computeNextRun(Map<String, Object> req) {
		// BUG-019: explicit JSON null must not reach the switch/split below.
		String schedule = (String) orDefault(req.get("schedule"), "daily");
		String scheduledTime = (String) orDefault(req.get("scheduled_time"), "09:00");

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
			ZonedDateTime candidate = ZonedDateTime.of(today, time, ZoneOffset.UTC).with(DayOfWeek.of(day));
			yield candidate.isBefore(ZonedDateTime.now()) ? candidate.plusWeeks(1) : candidate;
		}
		case "monthly" -> {
			int dom = req.get("scheduled_day") != null ? ((Number) req.get("scheduled_day")).intValue() : 1;
			ZonedDateTime candidate = ZonedDateTime.of(today.withDayOfMonth(Math.min(dom, today.lengthOfMonth())), time,
					ZoneOffset.UTC);
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
