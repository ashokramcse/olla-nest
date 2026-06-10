package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit tests for {@link BackupService} state guard.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * The {@code backupInProgress} flag prevents concurrent/overlapping backups from
 * corrupting the SQLite snapshot. This pins its initial state. The actual
 * {@code runBackup} file-copy is covered by the live admin-backup integration
 * test (it requires a real on-disk SQLite database).
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The {@link JdbcTemplate} is a mock; the state guard under test never
 * touches the database.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.10 — created to cover the previously-untested BackupService.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@DisplayName("BackupService — in-progress guard")
class BackupServiceTest {

	/** Service under test, built with a mocked DB. */
	private BackupService svc;

	/**
	 * Builds the service with a mocked {@link JdbcTemplate}.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@BeforeEach
	void setUp() {
		svc = new BackupService(mock(JdbcTemplate.class));
	}

	/**
	 * A freshly constructed service reports no backup in progress, so the first
	 * {@code runBackup} is never blocked by a stale flag.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("isBackupInProgress is false on a fresh service")
	void notInProgressInitially() {
		assertThat(svc.isBackupInProgress()).isFalse();
	}
}
