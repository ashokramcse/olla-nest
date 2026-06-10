package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;

/**
 * OCD-level unit tests for {@link TaskSchedulerService}.
 *
 * <p>
 * Covers: {@code create()} — DB INSERT, ID prefix, notifications_enabled
 * defaulting; {@code update()} — NoSuchElementException when task not found, DB
 * UPDATE called; {@code delete()} — scoped DELETE; {@code getById()} — null
 * when not found; {@code list()} — with and without status filter;
 * {@code computeNextRun()} — daily/once schedules.
 *
 * <p>
 * All DB interactions are Mockito-stubbed — no Spring context, no real DB.
 *
 * @author Ashok Ram
 * @since v2026.2.1 — initial creation
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TaskSchedulerService — unit tests")
class TaskSchedulerServiceTest {

	private static final String OWNER = UserFactory.USER_ID;

	@Mock
	JdbcTemplate db;
	@Mock
	ObjectMapper mapper;
	@Mock
	DatabaseService databaseService;

	@InjectMocks
	TaskSchedulerService svc;

	@BeforeEach
	void stubGetById() {
		// Stub: default to empty list so getById returns null unless overridden in
		// specific tests
		when(db.queryForList(contains("FROM scheduled_tasks WHERE id"), (Object) any(), any())).thenReturn(List.of());
	}

	// ── create() ──────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("create()")
	class Create {

		@Test
		@DisplayName("DB INSERT called with owner")
		void insertCalledWithOwner() {
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.create(OWNER, Map.of("name", "My Task", "schedule", "daily", "scheduled_time", "09:00"));
			verify(db).update(contains("INSERT INTO scheduled_tasks"), cap.capture());
			// args[1] = owner — task must be scoped to the creating user for RBAC
			// enforcement
			assertThat(cap.getValue()[1]).isEqualTo(OWNER);
		}

		@Test
		@DisplayName("generated id starts with 'task-'")
		void idStartsWithTaskPrefix() {
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.create(OWNER, Map.of("schedule", "daily", "scheduled_time", "09:00"));
			verify(db).update(contains("INSERT INTO scheduled_tasks"), cap.capture());
			// args[0] = id — must use the "task-" prefix for consistent ID namespace
			assertThat(cap.getValue()[0].toString()).startsWith("task-");
		}

		@Test
		@DisplayName("notifications_enabled defaults to 1 when not specified")
		void notificationsEnabledDefaultsToOne() {
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.create(OWNER, Map.of("name", "Task", "schedule", "daily", "scheduled_time", "09:00"));
			verify(db).update(contains("INSERT INTO scheduled_tasks"), cap.capture());
			// notifications_enabled is the 19th param (index 18)
			// Defaulting to 1 ensures users receive task completion notifications unless
			// explicitly disabled
			assertThat(cap.getValue()[18]).isEqualTo(1);
		}

		@Test
		@DisplayName("notifications_enabled is 0 when explicitly set to false")
		void notificationsDisabledWhenFalse() {
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.create(OWNER, Map.of("name", "Task", "schedule", "daily", "scheduled_time", "09:00",
					"notifications_enabled", false));
			verify(db).update(contains("INSERT INTO scheduled_tasks"), cap.capture());
			// Explicit false must be respected — must not be overridden by the default
			assertThat(cap.getValue()[18]).isEqualTo(0);
		}

		@Test
		@DisplayName("status is set to 'active' on create")
		void statusIsActive() {
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.create(OWNER, Map.of("schedule", "daily", "scheduled_time", "09:00"));
			verify(db).update(contains("INSERT INTO scheduled_tasks"), cap.capture());
			// New tasks are immediately active — no manual activation step required
			assertThat(cap.getValue()[19]).isEqualTo("active");
		}

		@Test
		@DisplayName("rapid creates in the same millisecond produce UNIQUE ids (BUG-009 regression)")
		void rapidCreatesProduceUniqueIds() {
			// Regression for the seedCheckIns 500: task ids were timestamp-only, so
			// several creates in the same millisecond collided on the primary key.
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			for (int i = 0; i < 50; i++) {
				svc.create(OWNER, Map.of("name", "Check-in " + i, "schedule", "daily", "scheduled_time", "09:00"));
			}
			verify(db, times(50)).update(contains("INSERT INTO scheduled_tasks"), cap.capture());
			var ids = new HashSet<String>();
			for (Object[] args : cap.getAllValues())
				ids.add(args[0].toString());
			// All 50 generated ids must be distinct — no PRIMARY KEY collision possible.
			assertThat(ids).hasSize(50);
		}
	}

	// ── update() ──────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("update()")
	class Update {

		@Test
		@DisplayName("throws NoSuchElementException when task not found")
		void throwsWhenNotFound() {
			// Stub: task not found — either missing or belongs to different owner
			when(db.queryForList(contains("FROM scheduled_tasks WHERE id"), eq("task-x"), eq(OWNER)))
					.thenReturn(List.of());
			// SECURITY: must throw with task ID — not silently modify another user's task
			assertThatThrownBy(() -> svc.update("task-x", OWNER, Map.of("name", "New Name")))
					.isInstanceOf(NoSuchElementException.class).hasMessageContaining("task-x");
		}
	}

	// ── delete() ──────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("delete()")
	class Delete {

		@Test
		@DisplayName("calls DELETE WHERE id=? AND owner=?")
		void deleteScopedToIdAndOwner() {
			when(db.update(contains("DELETE FROM scheduled_tasks"), eq("task-123"), eq(OWNER))).thenReturn(1);
			svc.delete("task-123", OWNER);
			// DELETE must include both id AND owner to prevent cross-user deletion
			verify(db).update(contains("DELETE FROM scheduled_tasks WHERE id"), eq("task-123"), eq(OWNER));
		}

		@Test
		@DisplayName("throws (→404) when nothing was deleted — missing or other-user task (BUG-040)")
		void throwsWhenNothingDeleted() {
			when(db.update(contains("DELETE FROM scheduled_tasks"), anyString(), anyString())).thenReturn(0);
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.delete("task-missing", OWNER))
					.isInstanceOf(java.util.NoSuchElementException.class);
		}
	}

	// ── getById() ─────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("getById()")
	class GetById {

		@Test
		@DisplayName("returns null when DB has no row")
		void returnsNullWhenNotFound() {
			// Stub: task not found (covered by BeforeEach stub)
			when(db.queryForList(anyString(), eq("task-missing"), eq(OWNER))).thenReturn(List.of());
			// Must return null — not throw — when the task is absent
			assertThat(svc.getById("task-missing", OWNER)).isNull();
		}
	}

	// ── list() ────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("list()")
	class ListTasks {

		@Test
		@DisplayName("queries without status filter when status is null")
		void noStatusFilter() {
			// Stub: DB returns empty list
			when(db.queryForList(anyString(), eq(OWNER))).thenReturn(List.of());
			svc.list(OWNER, null);
			// Null status = no WHERE status= clause — all tasks returned
			verify(db).queryForList(anyString(), eq(OWNER));
		}

		@Test
		@DisplayName("queries with status filter when status provided")
		void withStatusFilter() {
			// Stub: DB returns empty active task list
			when(db.queryForList(anyString(), eq(OWNER), eq("active"))).thenReturn(List.of());
			svc.list(OWNER, "active");
			// Status filter must be applied to prevent returning paused/cancelled tasks
			verify(db).queryForList(anyString(), eq(OWNER), eq("active"));
		}
	}

	// ── computeNextRun() ──────────────────────────────────────────────────────

	@Nested
	@DisplayName("computeNextRun()")
	class ComputeNextRun {

		@Test
		@DisplayName("daily schedule returns a non-null future ISO instant")
		void dailyReturnsInstant() {
			// Daily tasks must always have a next_run timestamp so they get picked up by
			// the scheduler
			String next = svc.computeNextRun(Map.of("schedule", "daily", "scheduled_time", "09:00"));
			assertThat(next).isNotNull().matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
		}

		@Test
		@DisplayName("weekly schedule returns a non-null future ISO instant")
		void weeklyReturnsInstant() {
			// Weekly schedule must also resolve to a future timestamp
			String next = svc
					.computeNextRun(Map.of("schedule", "weekly", "scheduled_time", "08:00", "scheduled_day", 1));
			assertThat(next).isNotNull().matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
		}

		@Test
		@DisplayName("unknown schedule falls back to next-day instant")
		void unknownScheduleFallback() {
			// Unknown schedules must not return null — a safe fallback prevents the task
			// from being orphaned
			String next = svc.computeNextRun(Map.of("schedule", "unknown", "scheduled_time", "09:00"));
			assertThat(next).isNotNull();
		}
	}
}
