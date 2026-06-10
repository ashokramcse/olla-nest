package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link RagService} focusing on its deterministic, easily
 * isolated behaviour: the {@code personalScope} key format, the blank-query
 * short-circuit in {@code retrieve}, and the two-table cascade in
 * {@code deleteDocument}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * {@code personalScope} must produce a stable owner key (a mismatch silently
 * hides personal documents — the BUG-018 class), blank queries must not waste an
 * embedding call, and deleting a document must remove both its chunks and its
 * metadata row so no orphans remain.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>The {@link JdbcTemplate} and {@link EmbeddingService} are mocks; tests
 * verify delegation and short-circuiting rather than embedding quality.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.10 — created to cover the previously-untested RagService.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.10
 * @version v2026.1.10
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RagService — scope, retrieve short-circuit, delete cascade")
class RagServiceTest {

	/** Mocked JDBC template; verified for the delete cascade. */
	@Mock JdbcTemplate db;
	/** Mocked embedding service; verified to ensure no needless embed call. */
	@Mock EmbeddingService embeddingService;
	/** Mocked prompt-security service collaborator. */
	@Mock PromptSecurityService promptSecurityService;
	/** Unused request mock kept for symmetry with other service tests. */
	@Mock HttpServletRequest req;

	/** Service under test, constructed with the mocked collaborators. */
	private RagService svc;

	/**
	 * Builds the service with the mocked collaborators.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@BeforeEach
	void setUp() {
		svc = new RagService(db, embeddingService, promptSecurityService);
	}

	/**
	 * {@code personalScope} produces the stable {@code "personal:<userId>"} key for
	 * a real user id — the exact format that document ingest and retrieval must
	 * agree on (BUG-018 guard).
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("personalScope formats as personal:<userId>")
	void personalScopeFormat() {
		assertThat(RagService.personalScope("u-123")).isEqualTo("personal:u-123");
	}

	/**
	 * {@code personalScope} returns {@code null} for a null or blank user id so a
	 * missing owner never produces a bogus {@code "personal:"} scope.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("personalScope returns null for null/blank user id")
	void personalScopeNullSafe() {
		assertThat(RagService.personalScope(null)).isNull();
		assertThat(RagService.personalScope("  ")).isNull();
	}

	/**
	 * A blank query short-circuits to an empty result and must not invoke the
	 * embedding backend — avoiding a wasted (and potentially slow) embed call.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("retrieve with blank query → empty, no embed call")
	void retrieveBlankQueryShortCircuits() {
		assertThat(svc.retrieve("  ", "global", 5)).isEmpty();
		verify(embeddingService, never()).embed(anyString());
	}

	/**
	 * Deleting a document removes both its chunk rows and its metadata row, leaving
	 * no orphaned chunks behind.
	 *
	 * @author Ashok Ram
	 * @since v2026.1.10
	 * @version v2026.1.10
	 */
	@Test
	@DisplayName("deleteDocument removes chunks then the document row")
	void deleteDocumentCascades() {
		svc.deleteDocument("doc-1");
		verify(db).update(contains("DELETE FROM rag_chunks"), eq("doc-1"));
		verify(db).update(contains("DELETE FROM rag_documents"), eq("doc-1"));
	}
}
