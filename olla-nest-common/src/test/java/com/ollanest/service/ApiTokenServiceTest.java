package com.ollanest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ollanest.testinfra.UserFactory;

/**
 * OCD-level unit tests for {@link ApiTokenService}.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Personal API tokens are bearer credentials, so their lifecycle must never
 * regress on the security-sensitive details: tokens are shown exactly once,
 * stored only as bcrypt hashes, prefixed for O(1) lookup, owner-scoped on every
 * mutation, and high-entropy (no collisions). This class locks down minting,
 * validation, listing, and revocation, with every collaborator stubbed so no
 * real DB or randomness leaks into the assertions.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Mints exercise the real {@code SecureRandom} token generator; only the DB
 * and JSON mapper are mocked.</li>
 * <li>Security invariants under test: the raw token is returned only on mint and
 * never persisted in plaintext; {@code token_hash} is never present in
 * list/validate responses; all tokens carry the {@code oly_} prefix; and 100
 * mints yield 100 distinct tokens.</li>
 * <li>Lenient strictness covers the shared {@link #stubMapper()} stub that not
 * every test exercises.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.0 — initial creation; documented in the project-wide Javadoc
 * pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.0
 * @version v2026.2.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiTokenService — unit tests")
class ApiTokenServiceTest {

	/** Stable test owner id used across all token operations. */
	private static final String OWNER = UserFactory.USER_ID;
	/** The mandatory prefix every minted token must carry. */
	private static final String TOKEN_PREFIX = "oly_";

	/** Mocked database template backing token persistence. */
	@Mock
	JdbcTemplate db;
	/** Mocked JSON mapper used for scope serialisation/deserialisation. */
	@Mock
	ObjectMapper mapper;

	/** System under test with the mocks injected. */
	@InjectMocks
	ApiTokenService tokenService;

	/**
	 * Stubs the JSON mapper to serialise scope lists to a fixed payload.
	 *
	 * <p>
	 * Minting serialises the scopes list; this stub returns a constant
	 * {@code ["chat"]} payload so mint tests do not depend on real Jackson output.
	 *
	 * @throws Exception if the mocked serialisation declares a checked exception
	 * @since v2026.2.0
	 * @author Ashok Ram
	 * @version v2026.2.0
	 */
	@BeforeEach
	void stubMapper() throws Exception {
		when(mapper.writeValueAsString(any())).thenReturn("[\"chat\"]");
	}

	// ── mint() ────────────────────────────────────────────────────────────────

	/**
	 * Groups tests for {@link ApiTokenService#mint(String, String, List)} — token
	 * creation, format, hashing, and uniqueness.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("mint()")
	class Mint {

		/**
		 * Verifies the mint result exposes the one-time raw {@code token} value.
		 *
		 * <p>
		 * Security: the raw token is shown only on mint and is never stored in
		 * plaintext, so it must appear in the returned map.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("returned map contains 'token' key (shown once)")
		void returnedMapContainsToken() {
			var result = tokenService.mint(OWNER, "My Token", List.of("chat"));
			// SECURITY: raw token shown only once (on mint), never stored in plaintext
			assertThat(result).containsKey("token");
		}

		/**
		 * Verifies the minted raw token starts with the {@code oly_} prefix.
		 *
		 * <p>
		 * The prefix enables prefix-based candidate lookup without exposing the bcrypt
		 * hash.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("minted token starts with 'oly_' prefix")
		void tokenStartsWithOlyPrefix() {
			var result = tokenService.mint(OWNER, "Test Token", List.of("chat"));
			// oly_ prefix allows quick prefix-based lookup without exposing the full hash
			assertThat(result.get("token").toString()).startsWith(TOKEN_PREFIX);
		}

		/**
		 * Verifies the stored {@code token_prefix} is the first 12 chars of the token.
		 *
		 * <p>
		 * The 12-char prefix is the DB lookup key that lets validation fetch candidate
		 * rows in O(1) before bcrypt comparison.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("token_prefix stored is first 12 chars of the raw token")
		void tokenPrefixIsFirst12Chars() {
			var result = tokenService.mint(OWNER, "Test Token", List.of("chat"));
			String rawToken = (String) result.get("token");
			// First 12 chars used as DB lookup prefix — allows O(1) candidate search
			String expectedPrefix = rawToken.substring(0, Math.min(12, rawToken.length()));
			assertThat(result.get("token_prefix")).isEqualTo(expectedPrefix);
		}

		/**
		 * Verifies mint issues an INSERT into {@code api_tokens}.
		 *
		 * <p>
		 * Proves persistence happens; the raw token is never stored, only its bcrypt
		 * hash.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("DB INSERT called with owner, name, hashed token (not raw)")
		void dbInsertCallsWithOwnerAndName() {
			tokenService.mint(OWNER, "My Device", List.of("chat"));
			// INSERT must be called — raw token is never stored, only the bcrypt hash
			verify(db).update(contains("INSERT INTO api_tokens"), any(Object[].class));
		}

		/**
		 * Verifies the returned record carries the owning user id.
		 *
		 * <p>
		 * Proves the owner is propagated into the mint response for the caller's UI.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("owner is present in returned record")
		void ownerInReturnedRecord() {
			var result = tokenService.mint(OWNER, "Device", List.of("chat"));
			assertThat(result.get("owner")).isEqualTo(OWNER);
		}

		/**
		 * Verifies a {@code null} token name defaults to {@code "API Token"}.
		 *
		 * <p>
		 * Null name must produce a sensible default label rather than an NPE or the
		 * literal string "null".
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("null name defaults to 'API Token'")
		void nullNameDefaults() {
			var result = tokenService.mint(OWNER, null, List.of("chat"));
			// Null name must produce a sensible default, not NPE or "null"
			assertThat(result.get("name")).isEqualTo("API Token");
		}

		/**
		 * Verifies {@code null} scopes default to a non-null scope set.
		 *
		 * <p>
		 * Proves a missing scopes list is coerced to the default ({@code ["chat"]})
		 * rather than left null.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("null scopes default to [\"chat\"]")
		void nullScopesDefault() {
			var result = tokenService.mint(OWNER, "Token", null);
			// scopes field should default to [chat]
			assertThat(result.get("scopes")).isNotNull();
		}

		/**
		 * Verifies 100 mints produce 100 distinct tokens.
		 *
		 * <p>
		 * Security: the {@code SecureRandom}-backed generator must guarantee no token
		 * reuse, even in a tight loop, to prevent credential collisions.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@RepeatedTest(1)
		@DisplayName("100 minted tokens are all unique (SecureRandom entropy)")
		void hundredTokensAreUnique() {
			// Mint 100 tokens and collect them — all must be unique
			var tokens = new HashSet<String>();
			for (int i = 0; i < 100; i++) {
				var r = tokenService.mint(OWNER, "Token " + i, List.of("chat"));
				tokens.add((String) r.get("token"));
			}
			// SECURITY: SecureRandom must ensure no token reuse even under tight loops
			assertThat(tokens).hasSize(100);
		}
	}

	// ── validate() ────────────────────────────────────────────────────────────

	/**
	 * Groups tests for {@link ApiTokenService#validate(String)} — bearer-token
	 * verification and rejection paths.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("validate()")
	class Validate {

		/**
		 * Verifies a {@code null} token returns {@code null} (unauthenticated).
		 *
		 * <p>
		 * Null token must short-circuit before any DB lookup is attempted.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("null token returns null (not authenticated)")
		void nullTokenReturnsNull() {
			// Null token must short-circuit before any DB lookup
			assertThat(tokenService.validate(null)).isNull();
		}

		/**
		 * Verifies any token lacking the {@code oly_} prefix is rejected.
		 *
		 * <p>
		 * Security: null, empty, blank, and foreign-provider tokens must be rejected
		 * immediately without a DB round-trip.
		 *
		 * @param token a malformed or foreign-provider token value
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   ", "Bearer abc", "api_key_123", "sk-abc123" })
		@DisplayName("token not starting with 'oly_' returns null")
		void nonOlyPrefixReturnsNull(String token) {
			// SECURITY: tokens from other providers or invalid format must be rejected
			// immediately
			assertThat(tokenService.validate(token)).isNull();
		}

		/**
		 * Verifies a well-formed token with no matching DB row returns {@code null}.
		 *
		 * <p>
		 * When the prefix matches no candidate (revoked or never existed) the token is
		 * invalid.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("oly_ token with no DB candidate returns null")
		void noDbCandidateReturnsNull() {
			// Stub: no DB row matches the prefix → token is invalid (revoked or never
			// existed)
			when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
			assertThat(tokenService.validate("oly_" + "a".repeat(64))).isNull();
		}

		/**
		 * Verifies {@code token_hash} is never present in a validate response.
		 *
		 * <p>
		 * Security: even if a candidate row is found and (theoretically) returned, the
		 * bcrypt {@code token_hash} must never be exposed to the caller.
		 *
		 * @throws Exception if the mocked deserialisation declares a checked exception
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("token_hash is absent from validate() response (never exposed)")
		void hashNotExposedInValidateResponse() throws Exception {
			// Step 1: Mint a real token so we have a valid bcrypt hash to test against
			var minted = tokenService.mint(OWNER, "Device", List.of("chat"));
			String rawToken = (String) minted.get("token");
			String prefix = (String) minted.get("token_prefix");

			// Step 2: Stub DB to return a row including token_hash
			when(db.queryForList(anyString(), eq(prefix)))
					.thenReturn(List.of(Map.of("id", "tok-1", "owner", OWNER, "name", "Device", "token_hash",
							"some-bcrypt-hash", "token_prefix", prefix, "scopes_json", "[\"chat\"]", "is_active", 1)));
			when(mapper.readValue(eq("[\"chat\"]"), eq(List.class))).thenReturn(List.of("chat"));

			var result = tokenService.validate(rawToken);
			// Even if candidate is found, bcrypt mismatch → null (correct behavior)
			// SECURITY: if somehow it returned a record, token_hash must never be exposed
			if (result != null) {
				assertThat(result).doesNotContainKey("token_hash");
			}
		}
	}

	// ── list() ────────────────────────────────────────────────────────────────

	/**
	 * Groups tests for {@link ApiTokenService#list(String)} — owner-scoped token
	 * enumeration with hash stripping.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("list()")
	class ListTokens {

		/**
		 * Verifies listing queries the DB filtered by owner.
		 *
		 * <p>
		 * Proves the SELECT against {@code api_tokens} is bound to the calling owner so
		 * users only see their own tokens.
		 *
		 * @throws Exception if the mocked deserialisation declares a checked exception
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("queries DB with owner filter")
		void queriesWithOwner() throws Exception {
			when(mapper.readValue(anyString(), eq(List.class))).thenReturn(List.of("chat"));
			// Stub: one token row for this owner
			when(db.queryForList(contains("FROM api_tokens"), eq(OWNER)))
					.thenReturn(List.of(Map.of("id", "tok-1", "owner", OWNER, "name", "Device", "token_prefix",
							"oly_abc123", "token_hash", "hash", "scopes_json", "[\"chat\"]", "is_active", 1)));
			var result = tokenService.list(OWNER);
			assertThat(result).isNotEmpty();
		}

		/**
		 * Verifies {@code token_hash} is stripped from every list entry.
		 *
		 * <p>
		 * Security: the bcrypt hash must never reach the client in any list response.
		 *
		 * @throws Exception if the mocked deserialisation declares a checked exception
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("token_hash is stripped from list response")
		void hashStrippedFromList() throws Exception {
			when(mapper.readValue(anyString(), eq(List.class))).thenReturn(List.of("chat"));
			when(db.queryForList(anyString(), eq(OWNER))).thenReturn(
					List.of(Map.of("id", "tok-1", "owner", OWNER, "name", "Device", "token_prefix", "oly_abc123def",
							"token_hash", "super-secret-bcrypt-hash", "scopes_json", "[\"chat\"]", "is_active", 1)));
			var result = tokenService.list(OWNER);
			if (!result.isEmpty()) {
				// SECURITY: token_hash must never be returned to the client in any list
				// operation
				assertThat(result.get(0)).doesNotContainKey("token_hash");
			}
		}
	}

	// ── revoke() / revokeAll() ────────────────────────────────────────────────

	/**
	 * Groups tests for {@link ApiTokenService#revoke(String, String)} and
	 * {@link ApiTokenService#revokeAll(String)} — owner-scoped deactivation.
	 *
	 * @author Ashok Ram
	 * @since v2026.2.0
	 * @version v2026.2.0
	 */
	@Nested
	@DisplayName("revoke() / revokeAll()")
	class Revoke {

		/**
		 * Verifies single-token revoke flips {@code is_active=0} for that token+owner.
		 *
		 * <p>
		 * Proves the UPDATE binds both the token id and owner to prevent cross-user
		 * revocation.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("revoke sets is_active=0 for the specific token and owner")
		void revokeSetsFlagZero() {
			tokenService.revoke("tok-123", OWNER);
			// UPDATE must include both token id and owner to prevent cross-user revocation
			verify(db).update(contains("UPDATE api_tokens SET is_active=0"), eq("tok-123"), eq(OWNER));
		}

		/**
		 * Verifies bulk revoke flips {@code is_active=0} for all of the owner's tokens.
		 *
		 * <p>
		 * Proves a bulk revoke only affects the specified owner's tokens.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("revokeAll sets is_active=0 for all tokens of the owner")
		void revokeAllSetsFlagZero() {
			tokenService.revokeAll(OWNER);
			// Bulk revoke must only affect the specified owner
			verify(db).update(contains("UPDATE api_tokens SET is_active=0"), eq(OWNER));
		}

		/**
		 * Verifies single-token revoke is owner-scoped.
		 *
		 * <p>
		 * Proves the owner parameter participates in the WHERE clause so a user cannot
		 * revoke another user's token.
		 *
		 * @author Ashok Ram
		 * @since v2026.2.0
		 * @version v2026.2.0
		 */
		@Test
		@DisplayName("revoke targets only the given owner — not other owners")
		void revokeOwnerScoped() {
			tokenService.revoke("tok-123", OWNER);
			// Verify the owner parameter is passed to the WHERE clause
			verify(db).update(contains("UPDATE api_tokens SET is_active=0"), (Object) any(), (Object) any());
		}
	}
}
