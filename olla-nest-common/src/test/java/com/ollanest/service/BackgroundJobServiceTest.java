package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

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
 * OCD-level unit tests for {@link BackgroundJobService}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Background jobs (downloads, deep research, connector syncs, email polls) are
 * tracked both in the database and in an in-memory thread map. This class pins
 * the full lifecycle — registration, progress, completion, failure,
 * cancellation, listing, and owner-scoped lookups — so that the dual
 * bookkeeping stays consistent and the IDOR fix (BUG-045) keeps job control
 * scoped to the owning user.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>All DB interactions are Mockito-stubbed — no Spring context and no real
 * database.</li>
 * <li>Thread interruption is verified against {@link Thread} mocks so no real
 * threads are spawned.</li>
 * <li>{@link ArgumentCaptor} is used to assert the exact positional INSERT
 * arguments (id/owner/job_type/name/status/progress).</li>
 * <li>Lenient strictness covers shared stubs not exercised by every test.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — initial creation; documented in the project-wide Javadoc
 * pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BackgroundJobService — unit tests")
class BackgroundJobServiceTest {

	/** Stable test owner id for all registered jobs. */
	private static final String OWNER = UserFactory.USER_ID;

	/** Mocked database template backing job persistence. */
	@Mock
	JdbcTemplate db;
	/** Mocked JSON mapper used to serialise job result payloads. */
	@Mock
	ObjectMapper mapper;

	/** System under test with the mocks injected. */
	@InjectMocks
	BackgroundJobService svc;

	// ── register() ───────────────────────────────────────────────────────────

	/**
	 * Groups tests for
	 * {@link BackgroundJobService#register(String, String, String)} — job creation
	 * and its INSERT payload.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("register()")
	class Register {

		/**
		 * Verifies the returned job id carries the {@code job-} prefix.
		 *
		 * <p>
		 * The prefix makes background job ids recognisable in logs and API responses.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returned ID starts with 'job-'")
		void idStartsWithJobPrefix() {
			String id = svc.register(OWNER, "download", "My Download");
			// job- prefix makes background job IDs recognisable in logs and APIs
			assertThat(id).startsWith("job-");
		}

		/**
		 * Verifies the INSERT binds owner, job_type, and name correctly.
		 *
		 * <p>
		 * Captures the positional arguments and proves the owner (arg 1), job_type
		 * (arg 2), and name (arg 3) are persisted verbatim.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("INSERT called with correct owner, job_type, and name")
		void insertCalledWithOwner() {
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.register(OWNER, "research", "Deep Research");
			verify(db).update(contains("INSERT INTO background_jobs"), cap.capture());
			Object[] args = cap.getValue();
			// args[0] = id, args[1] = owner, args[2] = job_type, args[3] = name — verify
			// correct values
			assertThat(args[1]).isEqualTo(OWNER);
			assertThat(args[2]).isEqualTo("research");
			assertThat(args[3]).isEqualTo("Deep Research");
		}

		/**
		 * Verifies a freshly registered job has status {@code running}.
		 *
		 * <p>
		 * Proves the status column (arg 4) is set to {@code running} at registration
		 * time.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("status is set to 'running' on insert")
		void statusIsRunning() {
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.register(OWNER, "sync", "Connector Sync");
			verify(db).update(contains("INSERT INTO background_jobs"), cap.capture());
			// args[4] = status — must be "running" at registration time
			assertThat(cap.getValue()[4]).isEqualTo("running");
		}

		/**
		 * Verifies a freshly registered job starts at progress {@code 0}.
		 *
		 * <p>
		 * Proves the progress column (arg 5) is initialised to zero.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("progress is set to 0 on insert")
		void initialProgressIsZero() {
			ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
			svc.register(OWNER, "email", "Email Poll");
			verify(db).update(contains("INSERT INTO background_jobs"), cap.capture());
			// args[5] = progress — must start at 0
			assertThat(cap.getValue()[5]).isEqualTo(0);
		}
	}

	// ── updateProgress() ──────────────────────────────────────────────────────

	/**
	 * Groups tests for
	 * {@link BackgroundJobService#updateProgress(String, int, String)}.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("updateProgress()")
	class UpdateProgress {

		/**
		 * Verifies progress updates persist both the percentage and message.
		 *
		 * <p>
		 * Proves the UPDATE binds the progress value, the status message, and the job id
		 * so the UI can render live progress.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("calls UPDATE with progress value and message")
		void updatesProgressAndMsg() {
			svc.updateProgress("job-123", 42, "Processing...");
			// Both progress percentage and message must be persisted
			verify(db).update(contains("UPDATE background_jobs SET progress"), eq(42), eq("Processing..."),
					eq("job-123"));
		}
	}

	// ── complete() ────────────────────────────────────────────────────────────

	/**
	 * Groups tests for {@link BackgroundJobService#complete(String, Object)} — the
	 * success path and thread-map cleanup.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("complete()")
	class Complete {

		/**
		 * Verifies completion sets status {@code completed} and progress 100.
		 *
		 * <p>
		 * Serialises the result payload and proves the terminal UPDATE marks the job as
		 * completed.
		 *
		 * @throws Exception if the mocked serialisation declares a checked exception
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("calls UPDATE with status='completed' and progress=100")
		void setsCompletedStatus() throws Exception {
			when(mapper.writeValueAsString(any())).thenReturn("{\"result\":true}");
			svc.complete("job-abc", Map.of("result", true));
			// Status must change to 'completed' and progress set to 100
			verify(db).update(contains("status='completed'"), any(), any(), any());
		}

		/**
		 * Verifies completion removes the job's thread from the running map.
		 *
		 * <p>
		 * Registers a job with a thread, completes it, and proves a subsequent
		 * {@code cancel} returns {@code false} because there is no longer a thread to
		 * interrupt.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("removes thread from running map — cancel after complete returns false")
		void removesThreadFromMap() {
			// Step 1: register a job and attach a thread
			String id = svc.register(OWNER, "t", "T");
			Thread mockThread = mock(Thread.class);
			svc.registerThread(id, mockThread);
			// Step 2: complete the job — thread must be removed from the map
			svc.complete(id, null);
			// Step 3: cancel after complete returns false — no thread to interrupt
			boolean result = svc.cancel(id);
			assertThat(result).isFalse();
		}

		/**
		 * Verifies completing with a {@code null} result does not throw.
		 *
		 * <p>
		 * A null result needs no serialisation, so the call must complete gracefully.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("null result does not throw (no mapper call needed)")
		void nullResultNoException() {
			// No exception thrown = null result is handled gracefully (no serialisation
			// needed)
			assertThatCode(() -> svc.complete("job-xyz", null)).doesNotThrowAnyException();
		}
	}

	// ── fail() ────────────────────────────────────────────────────────────────

	/**
	 * Groups tests for {@link BackgroundJobService#fail(String, String)} — the
	 * error path and thread-map cleanup.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("fail()")
	class Fail {

		/**
		 * Verifies failure sets status {@code error} and persists the error message.
		 *
		 * <p>
		 * Proves the error text and terminal status are stored so the UI can surface the
		 * failure reason.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("calls UPDATE with status='error' and error message")
		void setsErrorStatus() {
			svc.fail("job-123", "Connection refused");
			// Error message and status must be persisted for the UI to display
			verify(db).update(contains("status='error'"), eq("Connection refused"), any(), eq("job-123"));
		}

		/**
		 * Verifies failure removes the job's thread from the running map.
		 *
		 * <p>
		 * Registers a job with a thread, fails it, and proves a subsequent
		 * {@code cancel} returns {@code false} (no thread to interrupt).
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("removes thread from map after fail")
		void removesThreadFromMap() {
			// Step 1: register a job and attach a thread
			String id = svc.register(OWNER, "t", "T");
			Thread t = mock(Thread.class);
			svc.registerThread(id, t);
			// Step 2: mark job as failed — thread must be removed
			svc.fail(id, "error");
			// Thread removed — cancel returns false (no thread to interrupt)
			assertThat(svc.cancel(id)).isFalse();
		}
	}

	// ── cancel() ──────────────────────────────────────────────────────────────

	/**
	 * Groups tests for {@link BackgroundJobService#cancel(String)} — interruption
	 * and DB status update.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("cancel()")
	class Cancel {

		/**
		 * Verifies cancelling a job with no registered thread returns {@code false} but
		 * still updates the DB.
		 *
		 * <p>
		 * With no thread to interrupt the method returns false, yet the database status
		 * must still be set to {@code cancelled}.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns false and still calls UPDATE when no thread registered")
		void returnsFalseWhenNoThread() {
			// Register a job but don't attach a thread
			String id = svc.register(OWNER, "t", "Test");
			boolean result = svc.cancel(id);
			// No thread to interrupt → returns false, but DB status must still be updated
			assertThat(result).isFalse();
			verify(db).update(contains("status='cancelled'"), any(Object[].class));
		}

		/**
		 * Verifies cancelling a job with a registered thread interrupts it and returns
		 * {@code true}.
		 *
		 * <p>
		 * Proves the live thread is interrupted so the running work actually stops.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("returns true and interrupts thread when thread is registered")
		void interruptsThread() {
			// Step 1: register a job and attach a mock thread
			String id = svc.register(OWNER, "t", "Test");
			Thread mockThread = mock(Thread.class);
			svc.registerThread(id, mockThread);
			// Step 2: cancel — thread must be interrupted
			boolean result = svc.cancel(id);
			assertThat(result).isTrue();
			verify(mockThread).interrupt();
		}

		/**
		 * Verifies cancellation always persists status {@code cancelled}.
		 *
		 * <p>
		 * Proves the DB update happens regardless of whether a thread was attached.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("always calls DB UPDATE with status='cancelled'")
		void alwaysUpdatesDbStatus() {
			String id = svc.register(OWNER, "t", "Test");
			svc.cancel(id);
			// DB update must always happen regardless of whether a thread was attached
			verify(db).update(contains("status='cancelled'"), any(Object[].class));
		}
	}

	// ── listActive() / listByOwner() / getById() ──────────────────────────────

	/**
	 * Groups tests for the read paths: {@code listActive()},
	 * {@code listByOwner()}, and {@code getById()}, including the BUG-045 IDOR
	 * owner-scoping fixes.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.1
	 * @version v2026.2.1
	 */
	@Nested
	@DisplayName("listActive() / listByOwner() / getById()")
	class Query {

		/**
		 * Verifies {@code listActive()} queries only for status {@code running}.
		 *
		 * <p>
		 * Proves the active-jobs view excludes completed, failed, and cancelled jobs.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("listActive() queries for status='running'")
		void listActiveQueriesRunning() {
			when(db.queryForList(contains("status='running'"))).thenReturn(List.of());
			svc.listActive();
			// Must query specifically for 'running' status — not completed or failed jobs
			verify(db).queryForList(contains("status='running'"));
		}

		/**
		 * Verifies {@code listByOwner()} passes a positive limit through unchanged.
		 *
		 * <p>
		 * Proves the owner and a positive limit reach the query as-is.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("listByOwner() passes owner and positive limit directly")
		void listByOwnerPositiveLimit() {
			when(db.queryForList(anyString(), eq(OWNER), eq(10))).thenReturn(List.of());
			svc.listByOwner(OWNER, 10);
			verify(db).queryForList(anyString(), eq(OWNER), eq(10));
		}

		/**
		 * Verifies {@code listByOwner()} normalises a non-positive limit to 20.
		 *
		 * <p>
		 * Proves a limit of 0 or negative is coerced to 20 to prevent unbounded
		 * full-table scans.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("listByOwner() defaults limit to 20 when limit <= 0")
		void listByOwnerDefaultsLimitTo20() {
			when(db.queryForList(anyString(), eq(OWNER), eq(20))).thenReturn(List.of());
			// 0 or negative limit must be normalised to 20 to prevent full-table scans
			svc.listByOwner(OWNER, 0);
			verify(db).queryForList(anyString(), eq(OWNER), eq(20));
		}

		/**
		 * Verifies {@code getById()} returns {@code null} when no row matches.
		 *
		 * <p>
		 * A missing job must yield null rather than an exception.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getById() returns null when DB returns no rows")
		void getByIdReturnsNullWhenEmpty() {
			when(db.queryForList(anyString(), eq("job-missing"))).thenReturn(List.of());
			// Missing job → null, not exception
			assertThat(svc.getById("job-missing")).isNull();
		}

		/**
		 * Verifies {@code getById()} returns the first matching row.
		 *
		 * <p>
		 * Proves a found job is returned as the row map.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getById() returns first row when DB has a match")
		void getByIdReturnsRow() {
			var row = Map.<String, Object>of("id", "job-1", "owner", OWNER, "status", "running");
			when(db.queryForList(anyString(), eq("job-1"))).thenReturn(List.of(row));
			assertThat(svc.getById("job-1")).isEqualTo(row);
		}

		/**
		 * Verifies the owner-scoped {@code getById(id, owner)} enforces ownership
		 * (BUG-045 IDOR).
		 *
		 * <p>
		 * Proves the query includes {@code WHERE id=? AND owner=?}, returns the row for
		 * the rightful owner, and returns {@code null} for a different owner.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("getById(id, owner) scopes the query to the owner (BUG-045 IDOR)")
		void getByIdOwnerScoped() {
			var row = Map.<String, Object>of("id", "job-1", "owner", OWNER);
			when(db.queryForList(contains("WHERE id=? AND owner=?"), eq("job-1"), eq(OWNER))).thenReturn(List.of(row));
			assertThat(svc.getById("job-1", OWNER)).isEqualTo(row);
			// A different owner must not match — the WHERE includes owner.
			when(db.queryForList(contains("WHERE id=? AND owner=?"), eq("job-1"), eq("other"))).thenReturn(List.of());
			assertThat(svc.getById("job-1", "other")).isNull();
		}

		/**
		 * Verifies the owner-scoped {@code cancel(id, owner)} only cancels owned jobs
		 * (BUG-045 IDOR).
		 *
		 * <p>
		 * Proves an unowned job is not cancelled (no rows updated → {@code false}) while
		 * an owned job is cancelled ({@code true}).
		 *
		 * @author Ashok Ram
		 * @since v2026.2.1
		 * @version v2026.2.1
		 */
		@Test
		@DisplayName("cancel(id, owner) only cancels an owned job (BUG-045 IDOR)")
		void cancelOwnerScoped() {
			when(db.update(contains("WHERE id=? AND owner=?"), any(), eq("job-1"), eq("other"))).thenReturn(0);
			// Not owned → no cancel, no thread interruption.
			assertThat(svc.cancel("job-1", "other")).isFalse();
			when(db.update(contains("WHERE id=? AND owner=?"), any(), eq("job-1"), eq(OWNER))).thenReturn(1);
			assertThat(svc.cancel("job-1", OWNER)).isTrue();
		}
	}
}
