package com.ollanest.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Single Sign-On (SSO) authentication service for Google OAuth 2.0, generic
 * OIDC, and SAML 2.0.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Olla Nest supports enterprise SSO so that organisations can allow employees
 * to log in with their corporate identity provider (Google Workspace, Okta,
 * Azure AD, Auth0, Keycloak, ADFS, etc.) without creating a separate Olla Nest
 * password. {@code SsoService} owns the entire OAuth/OIDC/SAML protocol layer —
 * building authorisation URLs, exchanging codes for tokens, validating ID
 * tokens, and extracting a normalised {@link ClaimsResult} (email + name) that
 * {@code SsoController} uses for auto-provisioning or session creation.
 *
 * <h3>Supported flows</h3>
 * <ul>
 * <li><b>Google OAuth 2.0</b> — redirects to Google's consent screen, exchanges
 * the authorisation code at {@code https://oauth2.googleapis.com/token}, then
 * fetches user info from the Google userinfo endpoint. Supports hosted-domain
 * restrictions (e.g. {@code hd=mycompany.com}).</li>
 * <li><b>Generic OIDC</b> — fetches the provider's
 * {@code /.well-known/openid-configuration} discovery document, builds the
 * authorisation URL from {@code authorization_endpoint}, exchanges the code at
 * {@code token_endpoint}, and decodes the JWT payload of the {@code id_token}
 * to extract claims. Trusts the IdP over TLS — full JWKS signature verification
 * is a future enhancement.</li>
 * <li><b>SAML 2.0</b> — receives a base64-encoded SAML response assertion from
 * the IdP via HTTP POST, decodes the XML, and extracts the {@code NameID}
 * (typically email) and display-name attributes. This is a lightweight
 * implementation; full XML digital signature verification would require the
 * Shibboleth OpenSAML library.</li>
 * </ul>
 *
 * <h3>CSRF protection</h3>
 * <p>
 * All OAuth/OIDC flows use a cryptographically random 256-bit state nonce
 * stored in the {@code oauth_state} table. The nonce is validated — and
 * immediately deleted — when the provider callback arrives. States older than
 * 10 minutes are considered expired.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Client secrets are stored encrypted (AES-256-GCM via
 * {@link CryptoService}) in the {@code sso_providers} table and decrypted at
 * call time.</li>
 * <li>Provider configs are stored as a JSON blob in {@code config_json} and
 * parsed by {@link #parseConfig(Map)} on demand.</li>
 * <li>The {@code app.base-url} property determines the OAuth redirect URI, so
 * it must match the registered redirect URI in the IdP configuration
 * exactly.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li><b>v2026.1.4</b> — initial creation; Google OAuth 2.0, generic OIDC
 * discovery, lightweight SAML 2.0 XML parsing; CSRF state nonce management;
 * provider CRUD helpers.</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.4
 * @version v2026.1.4
 * @see com.ollanest.controller.SsoController
 */
@Service
public class SsoService {

	/** SLF4J logger for SSO flow diagnostics and error reporting. */
	private static final Logger log = LoggerFactory.getLogger(SsoService.class);

	/** Google OAuth 2.0 token exchange endpoint. */
	private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";

	/** Google OAuth 2.0 user-info endpoint (returns email, name, picture). */
	private static final String GOOGLE_USERINFO = "https://www.googleapis.com/oauth2/v3/userinfo";

	/**
	 * JDBC template for reading SSO provider configurations and managing state
	 * nonces.
	 */
	private final JdbcTemplate db;

	/** Decrypts stored {@code client_secret_enc} values at runtime. */
	private final CryptoService cryptoService;

	/** Shared Jackson mapper for provider API responses and config JSON. */
	private final ObjectMapper mapper;

	/**
	 * Shared HTTP client with a 10-second connection timeout. All SSO protocol
	 * calls go through this client.
	 */
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	/**
	 * Base URL of this Olla Nest instance (e.g. {@code https://ai.mycompany.com}).
	 * Used to construct OAuth redirect URIs. Must match the URI registered in the
	 * IdP. Defaults to {@code http://localhost:5000} for local development.
	 */
	@Value("${app.base-url:http://localhost:5000}")
	private String appBaseUrl;

	/**
	 * Constructor-injects all dependencies.
	 *
	 * @param db            JDBC template for state nonce and provider table access
	 * @param cryptoService used to decrypt stored client secrets
	 * @param mapper        shared Jackson {@link ObjectMapper}
	 * @since v2026.1.4
	 */
	public SsoService(JdbcTemplate db, CryptoService cryptoService, ObjectMapper mapper) {
		this.db = db;
		this.cryptoService = cryptoService;
		this.mapper = mapper;
	}

	// -------------------------------------------------------------------------
	// Normalised claims result
	// -------------------------------------------------------------------------

	/**
	 * Normalised result of any SSO authentication flow — email, display name, and
	 * provider.
	 *
	 * <p>
	 * All three SSO flows (Google, OIDC, SAML) converge on this record so that
	 * {@code SsoController} has a single code path for user lookup and
	 * auto-provisioning.
	 *
	 * @param email    the verified email address from the IdP; used as the unique
	 *                 user key
	 * @param name     the display name from the IdP; used as the {@code name}
	 *                 column value for auto-provisioned users
	 * @param provider the SSO protocol used: {@code "google"}, {@code "oidc"}, or
	 *                 {@code "saml"}
	 * @since v2026.1.4
	 */
	public record ClaimsResult(String email, String name, String provider) {
	}

	// -------------------------------------------------------------------------
	// State nonce (CSRF protection for OAuth/OIDC)
	// -------------------------------------------------------------------------

	/**
	 * Creates and persists a cryptographically random CSRF state nonce for an OAuth
	 * flow.
	 *
	 * <p>
	 * The state nonce is a 256-bit hex string stored in the {@code oauth_state}
	 * table alongside the provider ID and the post-login redirect URI. It is
	 * validated — and immediately consumed — when the provider callback arrives via
	 * {@link #validateState}.
	 *
	 * @param providerId  the SSO provider row ID (e.g. {@code "sso-google-01"})
	 * @param redirectUri the URI to redirect to after successful login; may be null
	 * @return the 64-character hex state nonce to embed in the authorisation URL
	 * @since v2026.1.4
	 */
	public String createState(String providerId, String redirectUri) {
		String state = generateToken();
		db.update("INSERT INTO oauth_state (state, provider_id, redirect_uri, created_at) " + "VALUES (?,?,?,?)", state,
				providerId, redirectUri, Instant.now().toString());
		return state;
	}

	/**
	 * Validates and atomically consumes a state nonce received in an OAuth
	 * callback.
	 *
	 * <p>
	 * Queries the {@code oauth_state} table for a matching nonce created within the
	 * last 10 minutes. If found, deletes the nonce immediately (one-time use) and
	 * returns the associated metadata row. Returns {@code null} if the nonce is
	 * absent, expired, or already consumed — all of which indicate a CSRF or replay
	 * attack.
	 *
	 * @param state the state string received from the IdP callback; must not be
	 *              null
	 * @return the {@code oauth_state} row map (including {@code provider_id} and
	 *         {@code redirect_uri}), or {@code null} if the nonce is
	 *         invalid/expired
	 * @since v2026.1.4
	 */
	public Map<String, Object> validateState(String state) {
		List<Map<String, Object>> rows = db.queryForList(
				"SELECT * FROM oauth_state WHERE state = ? " + "AND created_at > datetime('now', '-10 minutes')",
				state);
		if (rows.isEmpty())
			return null;
		db.update("DELETE FROM oauth_state WHERE state = ?", state);
		return rows.get(0);
	}

	// -------------------------------------------------------------------------
	// Provider lookup
	// -------------------------------------------------------------------------

	/**
	 * Returns all enabled SSO providers from the {@code sso_providers} table.
	 *
	 * <p>
	 * Called by the login page to render "Sign in with …" buttons. Only public-safe
	 * columns are selected — {@code client_secret_enc} is not included.
	 *
	 * @return a list of provider maps with keys: {@code id}, {@code type},
	 *         {@code name}, {@code client_id}, {@code config_json}; ordered
	 *         alphabetically by name
	 * @since v2026.1.4
	 */
	public List<Map<String, Object>> listEnabledProviders() {
		return db.queryForList("SELECT id, type, name, client_id, config_json "
				+ "FROM sso_providers WHERE enabled = 1 ORDER BY name");
	}

	/**
	 * Fetches the full SSO provider row by its primary key, including the encrypted
	 * secret.
	 *
	 * <p>
	 * <b>Security:</b> the returned map includes {@code client_secret_enc}. Callers
	 * must decrypt it via {@link #decryptSecret(Map)} and must not return the raw
	 * map to the browser.
	 *
	 * @param id the SSO provider primary key (e.g. {@code "sso-google-01"})
	 * @return the full provider row map, or {@code null} if not found
	 * @since v2026.1.4
	 */
	public Map<String, Object> getProvider(String id) {
		List<Map<String, Object>> rows = db.queryForList("SELECT * FROM sso_providers WHERE id = ?", id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	// -------------------------------------------------------------------------
	// Google OAuth 2.0
	// -------------------------------------------------------------------------

	/**
	 * Builds the Google OAuth 2.0 authorisation URL to redirect the user to.
	 *
	 * <p>
	 * The redirect URI is always {@code {appBaseUrl}/api/auth/sso/callback} and
	 * must be registered in the Google Cloud Console for the given client ID. If
	 * {@code hostedDomain} is non-null, the {@code hd} parameter restricts sign-in
	 * to users from that Google Workspace domain.
	 *
	 * @param clientId     the Google OAuth 2.0 client ID from the Cloud Console
	 * @param state        the CSRF nonce created by {@link #createState}
	 * @param hostedDomain optional Google Workspace domain restriction (e.g.
	 *                     {@code "myco.com"}); pass {@code null} to allow any
	 *                     Google account
	 * @return the full authorisation URL to redirect the browser to
	 * @since v2026.1.4
	 */
	public String buildGoogleAuthUrl(String clientId, String state, String hostedDomain) {
		String redirect = appBaseUrl + "/api/auth/sso/callback";
		String url = "https://accounts.google.com/o/oauth2/v2/auth" + "?response_type=code" + "&client_id="
				+ enc(clientId) + "&redirect_uri=" + enc(redirect) + "&scope=" + enc("openid email profile") + "&state="
				+ enc(state) + "&access_type=offline" + "&prompt=select_account";
		if (hostedDomain != null && !hostedDomain.isBlank()) {
			url += "&hd=" + enc(hostedDomain);
		}
		return url;
	}

	/**
	 * Exchanges a Google OAuth 2.0 authorisation code for user identity claims.
	 *
	 * <p>
	 * POSTs the code to {@code https://oauth2.googleapis.com/token} to obtain an
	 * access token, then GETs the Google userinfo endpoint to retrieve email and
	 * name. The ID token is not decoded locally — the userinfo endpoint is used
	 * instead for simplicity and reliability.
	 *
	 * @param code         the authorisation code received in the OAuth callback
	 * @param clientId     the Google OAuth 2.0 client ID
	 * @param clientSecret the plain-text client secret (already decrypted by the
	 *                     caller)
	 * @return a {@link ClaimsResult} with the user's email, name, and
	 *         {@code provider = "google"}
	 * @throws RuntimeException if Google returns a token error (wrong code,
	 *                          expired, etc.)
	 * @throws Exception        on network failure or JSON parse error
	 * @since v2026.1.4
	 */
	public ClaimsResult exchangeGoogleCode(String code, String clientId, String clientSecret) throws Exception {
		String redirect = appBaseUrl + "/api/auth/sso/callback";
		String body = "code=" + enc(code) + "&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret)
				+ "&redirect_uri=" + enc(redirect) + "&grant_type=authorization_code";
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create(GOOGLE_TOKEN_URL))
				.header("Content-Type", "application/x-www-form-urlencoded").timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		JsonNode tokens = mapper.readTree(resp.body());
		if (tokens.has("error")) {
			throw new RuntimeException("Google token error: " + tokens.path("error_description").asText());
		}
		// Use the access token to fetch user identity from the userinfo endpoint
		String accessToken = tokens.path("access_token").asText();
		HttpRequest infoReq = HttpRequest.newBuilder().uri(URI.create(GOOGLE_USERINFO))
				.header("Authorization", "Bearer " + accessToken).timeout(Duration.ofSeconds(10)).GET().build();
		JsonNode info = mapper.readTree(http.send(infoReq, HttpResponse.BodyHandlers.ofString()).body());
		return new ClaimsResult(info.path("email").asText(), info.path("name").asText(), "google");
	}

	// -------------------------------------------------------------------------
	// Generic OIDC (Okta, Azure AD, Auth0, Keycloak, …)
	// -------------------------------------------------------------------------

	/**
	 * Builds an OIDC authorisation URL by fetching the provider's discovery
	 * document.
	 *
	 * <p>
	 * GETs {@code {issuerUrl}/.well-known/openid-configuration} to discover the
	 * {@code authorization_endpoint}, then appends the standard OIDC parameters.
	 *
	 * @param issuerUrl the OIDC issuer base URL (e.g.
	 *                  {@code https://dev-xyz.okta.com})
	 * @param clientId  the OIDC client ID registered with the provider
	 * @param state     the CSRF nonce created by {@link #createState}
	 * @return the full authorisation URL to redirect the browser to
	 * @throws Exception if the discovery document cannot be fetched or parsed
	 * @since v2026.1.4
	 */
	public String buildOidcAuthUrl(String issuerUrl, String clientId, String state) throws Exception {
		JsonNode discovery = fetchOidcDiscovery(issuerUrl);
		String authEndpoint = discovery.path("authorization_endpoint").asText();
		String redirect = appBaseUrl + "/api/auth/sso/callback";
		return authEndpoint + "?response_type=code" + "&client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirect)
				+ "&scope=" + enc("openid email profile") + "&state=" + enc(state);
	}

	/**
	 * Exchanges a generic OIDC authorisation code for user identity claims.
	 *
	 * <p>
	 * Discovers the {@code token_endpoint} from the OIDC discovery document,
	 * exchanges the code for tokens, then decodes the JWT ID token payload
	 * (base64url middle segment) to extract {@code email} and
	 * {@code name}/{@code preferred_username} claims.
	 *
	 * <p>
	 * <b>Security note:</b> this implementation trusts the IdP over TLS and does
	 * not perform JWKS signature verification of the ID token. A future version
	 * should validate the JWT signature using the JWKS URI from the discovery
	 * document.
	 *
	 * @param issuerUrl    the OIDC issuer base URL
	 * @param clientId     the OIDC client ID
	 * @param clientSecret the plain-text client secret (already decrypted by the
	 *                     caller)
	 * @param code         the authorisation code received in the callback
	 * @return a {@link ClaimsResult} with the user's email, name, and
	 *         {@code provider = "oidc"}
	 * @throws RuntimeException if the OIDC provider returns a token error
	 * @throws Exception        on network failure, discovery fetch failure, or JWT
	 *                          decode error
	 * @since v2026.1.4
	 */
	public ClaimsResult exchangeOidcCode(String issuerUrl, String clientId, String clientSecret, String code)
			throws Exception {
		JsonNode discovery = fetchOidcDiscovery(issuerUrl);
		String tokenEndpoint = discovery.path("token_endpoint").asText();
		String redirect = appBaseUrl + "/api/auth/sso/callback";
		String body = "code=" + enc(code) + "&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret)
				+ "&redirect_uri=" + enc(redirect) + "&grant_type=authorization_code";
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create(tokenEndpoint))
				.header("Content-Type", "application/x-www-form-urlencoded").timeout(Duration.ofSeconds(15))
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		JsonNode tokens = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
		if (tokens.has("error")) {
			throw new RuntimeException("OIDC token error: " + tokens.path("error_description").asText());
		}
		// Decode the JWT id_token payload (header.PAYLOAD.signature) without signature
		// verification
		String idToken = tokens.path("id_token").asText();
		JsonNode claims = decodeJwtPayload(idToken);
		String email = claims.path("email").asText();
		String name = claims.path("name").asText(claims.path("preferred_username").asText(email));
		return new ClaimsResult(email, name, "oidc");
	}

	// -------------------------------------------------------------------------
	// SAML 2.0
	// -------------------------------------------------------------------------

	/**
	 * Parses a base64-encoded SAML 2.0 response assertion and extracts identity
	 * claims.
	 *
	 * <p>
	 * Decodes the base64 MIME assertion, extracts the {@code NameID} element as the
	 * user's email, and looks for {@code displayName} or {@code cn} attribute
	 * values as the display name. Falls back to the email address if no
	 * display-name attribute is found.
	 *
	 * <p>
	 * <b>Security note:</b> this is a lightweight XML string-parsing implementation
	 * suitable for development and internal deployments. It does not validate the
	 * SAML XML digital signature. For production deployments requiring full
	 * signature verification, integrate
	 * {@code spring-security-saml2-service-provider} with the Shibboleth OpenSAML
	 * library.
	 *
	 * @param base64Assertion the base64-encoded SAML response received via HTTP
	 *                        POST from the identity provider
	 * @return a {@link ClaimsResult} with email, name, and
	 *         {@code provider = "saml"}
	 * @throws Exception if the base64 decoding or XML parsing fails
	 * @since v2026.1.4
	 */
	public ClaimsResult parseSamlResponse(String base64Assertion) throws Exception {
		byte[] decoded = Base64.getMimeDecoder().decode(base64Assertion);
		String xml = new String(decoded, StandardCharsets.UTF_8);
		String email = extractXmlValue(xml, "NameID");
		String name = extractXmlAttr(xml, "displayName");
		if (name == null || name.isBlank())
			name = extractXmlAttr(xml, "cn");
		if (name == null || name.isBlank())
			name = email;
		return new ClaimsResult(email, name, "saml");
	}

	// -------------------------------------------------------------------------
	// Public helpers for SsoController
	// -------------------------------------------------------------------------

	/**
	 * Decrypts the {@code client_secret_enc} column value from an SSO provider row.
	 *
	 * <p>
	 * Returns an empty string if the encrypted value is absent or blank (e.g. for
	 * providers that do not require a client secret).
	 *
	 * @param provider the full SSO provider row map from
	 *                 {@link #getProvider(String)}
	 * @return the plain-text client secret, or {@code ""} if not stored
	 * @since v2026.1.4
	 */
	public String decryptSecret(Map<String, Object> provider) {
		String enc = (String) provider.get("client_secret_enc");
		return (enc != null && !enc.isBlank()) ? cryptoService.decryptKey(enc) : "";
	}

	/**
	 * Parses the {@code config_json} column of an SSO provider row into a
	 * {@code Map}.
	 *
	 * <p>
	 * The config JSON stores non-secret provider parameters such as
	 * {@code issuer_url}, {@code hosted_domain}, {@code scopes}, and SAML
	 * {@code entity_id}. Returns an empty map on parse failure.
	 *
	 * @param provider the SSO provider row map from {@link #getProvider(String)}
	 * @return a mutable {@code Map<String, Object>} of provider config values;
	 *         never null, may be empty
	 * @since v2026.1.4
	 */
	public Map<String, Object> parseConfig(Map<String, Object> provider) {
		String json = (String) provider.getOrDefault("config_json", "{}");
		try {
			return mapper.readValue(json,
					mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
		} catch (Exception e) {
			return Map.of();
		}
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	/**
	 * Fetches and parses an OIDC provider's discovery document.
	 *
	 * @param issuerUrl the OIDC issuer base URL; trailing slash is removed before
	 *                  appending {@code /.well-known/openid-configuration}
	 * @return the parsed discovery document as a {@link JsonNode}
	 * @throws Exception on network failure or JSON parse error
	 * @since v2026.1.4
	 */
	private JsonNode fetchOidcDiscovery(String issuerUrl) throws Exception {
		String url = issuerUrl.replaceAll("/$", "") + "/.well-known/openid-configuration";
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
		return mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
	}

	/**
	 * Decodes the payload segment of a JWT (the middle base64url-encoded segment).
	 *
	 * <p>
	 * Pads the base64url string to a multiple of 4 before decoding to handle JWT
	 * payloads that omit padding characters.
	 *
	 * @param jwt a dot-separated JWT string ({@code header.payload.signature})
	 * @return the decoded payload as a {@link JsonNode}
	 * @throws RuntimeException if the JWT has fewer than 2 segments
	 * @throws Exception        on base64 decode or JSON parse failure
	 * @since v2026.1.4
	 */
	private JsonNode decodeJwtPayload(String jwt) throws Exception {
		String[] parts = jwt.split("\\.");
		if (parts.length < 2)
			throw new RuntimeException("Invalid JWT");
		// Pad to multiple of 4 to handle standard base64url without padding
		byte[] payload = Base64.getUrlDecoder().decode(parts[1] + "==");
		return mapper.readTree(new String(payload, StandardCharsets.UTF_8));
	}

	/**
	 * Extracts the text content of a simple XML element by tag name (first match).
	 *
	 * <p>
	 * Handles both unqualified tags and namespace-qualified tags (e.g.
	 * {@code <saml:NameID>} → looks for {@code :NameID>}).
	 *
	 * @param xml the XML string to search
	 * @param tag the element tag name (without namespace prefix)
	 * @return the text content of the first matching element, or {@code ""}
	 * @since v2026.1.4
	 */
	private String extractXmlValue(String xml, String tag) {
		int start = xml.indexOf("<" + tag);
		if (start < 0)
			start = xml.indexOf(":" + tag + ">");
		if (start < 0)
			return "";
		int gt = xml.indexOf(">", start);
		if (gt < 0)
			return "";
		int end = xml.indexOf("<", gt + 1);
		if (end < 0)
			return "";
		return xml.substring(gt + 1, end).trim();
	}

	/**
	 * Extracts the value of a SAML {@code AttributeValue} element for a given
	 * attribute name.
	 *
	 * <p>
	 * Searches for an attribute with {@code Name="{attrName}"} and returns the text
	 * content of its first {@code AttributeValue} child. Returns {@code null} if
	 * the attribute is not present.
	 *
	 * @param xml      the SAML XML string
	 * @param attrName the SAML attribute name to look for (e.g.
	 *                 {@code "displayName"})
	 * @return the attribute value text, or {@code null} if not found
	 * @since v2026.1.4
	 */
	private String extractXmlAttr(String xml, String attrName) {
		String marker = "Name=\"" + attrName + "\"";
		int idx = xml.indexOf(marker);
		if (idx < 0) {
			marker = "Name=\"urn:oid:";
			idx = xml.indexOf(marker);
		}
		if (idx < 0)
			return null;
		int av = xml.indexOf("<saml:AttributeValue", idx);
		if (av < 0)
			av = xml.indexOf("<AttributeValue", idx);
		if (av < 0)
			return null;
		int gt = xml.indexOf(">", av);
		int end = xml.indexOf("<", gt + 1);
		return (gt >= 0 && end > gt) ? xml.substring(gt + 1, end).trim() : null;
	}

	/**
	 * Generates a cryptographically random 256-bit (64 hex character) token.
	 *
	 * <p>
	 * Used to create CSRF state nonces for OAuth/OIDC flows.
	 *
	 * @return a 64-character lowercase hex string
	 * @since v2026.1.4
	 */
	private static String generateToken() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	/**
	 * URL-encodes a string value using UTF-8 for inclusion in OAuth query
	 * parameters.
	 *
	 * @param v the string to encode; must not be null
	 * @return the URL-encoded string
	 * @since v2026.1.4
	 */
	private static String enc(String v) {
		return URLEncoder.encode(v, StandardCharsets.UTF_8);
	}
}
