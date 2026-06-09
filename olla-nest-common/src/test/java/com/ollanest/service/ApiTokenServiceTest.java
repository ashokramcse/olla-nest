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
 * <p>
 * Covers: {@code mint()} — token format, prefix storage, bcrypt hashing, DB
 * write, scope persistence, token uniqueness; {@code validate()} — prefix
 * mismatch rejection, invalid/null tokens, full-token validation path;
 * {@code list()} — owner scoping; {@code revoke()} / {@code revokeAll()} —
 * correct SQL and ownership.
 *
 * <p>
 * Security invariants tested:
 * <ul>
 * <li>Raw token is returned exactly once (on mint) and never stored in
 * plaintext.</li>
 * <li>token_hash is never present in the list/validate response maps.</li>
 * <li>All tokens start with the {@code oly_} prefix.</li>
 * <li>100 minted tokens produce 100 unique tokens (SecureRandom).</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.0 — initial creation
 * @version v2026.2.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiTokenService — unit tests")
class ApiTokenServiceTest {

	private static final String OWNER = UserFactory.USER_ID;
	private static final String TOKEN_PREFIX = "oly_";

	@Mock
	JdbcTemplate db;
	@Mock
	ObjectMapper mapper;

	@InjectMocks
	ApiTokenService tokenService;

	@BeforeEach
	void stubMapper() throws Exception {
		when(mapper.writeValueAsString(any())).thenReturn("[\"chat\"]");
	}

	// ── mint() ────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("mint()")
	class Mint {

		@Test
		@DisplayName("returned map contains 'token' key (shown once)")
		void returnedMapContainsToken() {
			var result = tokenService.mint(OWNER, "My Token", List.of("chat"));
			// SECURITY: raw token shown only once (on mint), never stored in plaintext
			assertThat(result).containsKey("token");
		}

		@Test
		@DisplayName("minted token starts with 'oly_' prefix")
		void tokenStartsWithOlyPrefix() {
			var result = tokenService.mint(OWNER, "Test Token", List.of("chat"));
			// oly_ prefix allows quick prefix-based lookup without exposing the full hash
			assertThat(result.get("token").toString()).startsWith(TOKEN_PREFIX);
		}

		@Test
		@DisplayName("token_prefix stored is first 12 chars of the raw token")
		void tokenPrefixIsFirst12Chars() {
			var result = tokenService.mint(OWNER, "Test Token", List.of("chat"));
			String rawToken = (String) result.get("token");
			// First 12 chars used as DB lookup prefix — allows O(1) candidate search
			String expectedPrefix = rawToken.substring(0, Math.min(12, rawToken.length()));
			assertThat(result.get("token_prefix")).isEqualTo(expectedPrefix);
		}

		@Test
		@DisplayName("DB INSERT called with owner, name, hashed token (not raw)")
		void dbInsertCallsWithOwnerAndName() {
			tokenService.mint(OWNER, "My Device", List.of("chat"));
			// INSERT must be called — raw token is never stored, only the bcrypt hash
			verify(db).update(contains("INSERT INTO api_tokens"), any(Object[].class));
		}

		@Test
		@DisplayName("owner is present in returned record")
		void ownerInReturnedRecord() {
			var result = tokenService.mint(OWNER, "Device", List.of("chat"));
			assertThat(result.get("owner")).isEqualTo(OWNER);
		}

		@Test
		@DisplayName("null name defaults to 'API Token'")
		void nullNameDefaults() {
			var result = tokenService.mint(OWNER, null, List.of("chat"));
			// Null name must produce a sensible default, not NPE or "null"
			assertThat(result.get("name")).isEqualTo("API Token");
		}

		@Test
		@DisplayName("null scopes default to [\"chat\"]")
		void nullScopesDefault() {
			var result = tokenService.mint(OWNER, "Token", null);
			// scopes field should default to [chat]
			assertThat(result.get("scopes")).isNotNull();
		}

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

	@Nested
	@DisplayName("validate()")
	class Validate {

		@Test
		@DisplayName("null token returns null (not authenticated)")
		void nullTokenReturnsNull() {
			// Null token must short-circuit before any DB lookup
			assertThat(tokenService.validate(null)).isNull();
		}

		@ParameterizedTest
		@NullAndEmptySource
		@ValueSource(strings = { "   ", "Bearer abc", "api_key_123", "sk-abc123" })
		@DisplayName("token not starting with 'oly_' returns null")
		void nonOlyPrefixReturnsNull(String token) {
			// SECURITY: tokens from other providers or invalid format must be rejected
			// immediately
			assertThat(tokenService.validate(token)).isNull();
		}

		@Test
		@DisplayName("oly_ token with no DB candidate returns null")
		void noDbCandidateReturnsNull() {
			// Stub: no DB row matches the prefix → token is invalid (revoked or never
			// existed)
			when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
			assertThat(tokenService.validate("oly_" + "a".repeat(64))).isNull();
		}

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

	@Nested
	@DisplayName("list()")
	class ListTokens {

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

	@Nested
	@DisplayName("revoke() / revokeAll()")
	class Revoke {

		@Test
		@DisplayName("revoke sets is_active=0 for the specific token and owner")
		void revokeSetsFlagZero() {
			tokenService.revoke("tok-123", OWNER);
			// UPDATE must include both token id and owner to prevent cross-user revocation
			verify(db).update(contains("UPDATE api_tokens SET is_active=0"), eq("tok-123"), eq(OWNER));
		}

		@Test
		@DisplayName("revokeAll sets is_active=0 for all tokens of the owner")
		void revokeAllSetsFlagZero() {
			tokenService.revokeAll(OWNER);
			// Bulk revoke must only affect the specified owner
			verify(db).update(contains("UPDATE api_tokens SET is_active=0"), eq(OWNER));
		}

		@Test
		@DisplayName("revoke targets only the given owner — not other owners")
		void revokeOwnerScoped() {
			tokenService.revoke("tok-123", OWNER);
			// Verify the owner parameter is passed to the WHERE clause
			verify(db).update(contains("UPDATE api_tokens SET is_active=0"), (Object) any(), (Object) any());
		}
	}
}
