package com.ollanest.controller;

import java.net.URLEncoder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;
import com.ollanest.service.CryptoService;
import com.ollanest.service.SsoService;
import com.ollanest.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * SSO authentication endpoints for Google OAuth 2.0, generic OIDC, and SAML
 * 2.0.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Provides the browser-facing redirect, callback, and assertion consumer
 * endpoints required by the three supported identity-provider protocols. All
 * three flows produce an identical session cookie by calling
 * {@link AuthService#setSession}, so the rest of the application does not need
 * to distinguish SSO sessions from password sessions.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>A one-time CSRF state nonce is stored in the DB before each IdP redirect
 * and validated at callback time — this prevents CSRF attacks against the OAuth
 * callback endpoint.</li>
 * <li>New SSO users are auto-provisioned with role {@code "user"} on first
 * login; their {@code auth_provider} column records the provider type so the
 * account settings page can hide fields owned by the IdP.</li>
 * <li>Admin CRUD endpoints for managing SSO provider records are also grouped
 * here under {@code /api/auth/sso/admin/providers}.</li>
 * <li>Client secrets are stored AES-encrypted via
 * {@link CryptoService#encryptKey} — never in plaintext.</li>
 * </ul>
 *
 * <p>
 * Public endpoints (no auth required):
 * 
 * <pre>
 *   GET  /api/auth/sso/providers          — list enabled providers for the login page
 *   GET  /api/auth/sso/authorize/{id}     — initiate redirect to IdP
 *   GET  /api/auth/sso/callback           — OAuth/OIDC authorization-code callback
 *   POST /api/auth/sso/saml/acs           — SAML assertion consumer service (ACS)
 * </pre>
 *
 * <p>
 * Admin-only endpoints:
 * 
 * <pre>
 *   GET    /api/auth/sso/admin/providers      — list all providers with secrets (redacted)
 *   POST   /api/auth/sso/admin/providers      — create a new provider record
 *   PATCH  /api/auth/sso/admin/providers/{id} — update a provider record
 *   DELETE /api/auth/sso/admin/providers/{id} — delete a provider record
 * </pre>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.1.0 — initial Java Spring Boot migration</li>
 * <li>v2026.1.4 — added SAML 2.0 ACS endpoint and admin CRUD for provider
 * management</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.1.0
 * @version v2026.1.4
 */
@RestController
@RequestMapping("/api/auth/sso")
public class SsoController {

	/** SSO protocol logic: state management, token exchange, claims parsing. */
	private final SsoService ssoService;

	/**
	 * Issues and invalidates session cookies after successful IdP authentication.
	 */
	private final AuthService authService;

	/** Encrypts and decrypts provider client secrets at rest. */
	private final CryptoService cryptoService;

	/** Used to look up and provision user accounts from SSO claims. */
	private final UserService userService;

	/** JDBC template for provider CRUD and SSO state management. */
	private final JdbcTemplate db;

	/**
	 * Constructor-injects all required dependencies.
	 *
	 * @param ssoService    the SSO protocol logic service
	 * @param authService   the session management service
	 * @param cryptoService the secret encryption service
	 * @param userService   the user provisioning service
	 * @param db            the JDBC template
	 * @since v2026.1.0
	 */
	public SsoController(SsoService ssoService, AuthService authService, CryptoService cryptoService,
			UserService userService, JdbcTemplate db) {
		this.ssoService = ssoService;
		this.authService = authService;
		this.cryptoService = cryptoService;
		this.userService = userService;
		this.db = db;
	}

	/**
	 * Returns the list of enabled SSO providers for rendering login buttons.
	 *
	 * <p>
	 * Endpoint: {@code GET /api/auth/sso/providers}
	 *
	 * <p>
	 * This endpoint is intentionally unauthenticated. Only the provider {@code id},
	 * {@code type}, and {@code name} are included — no client secrets or
	 * configuration details are exposed.
	 *
	 * @return 200 OK with {@code {ok: true, providers: [{id, type, name}]}}
	 * @since v2026.1.0
	 */
	@GetMapping("/providers")
	public ResponseEntity<Map<String, Object>> listProviders() {
		List<Map<String, Object>> providers = ssoService.listEnabledProviders().stream()
				.map(p -> Map.<String, Object>of("id", p.get("id"), "type", p.get("type"), "name", p.get("name")))
				.toList();
		return ResponseEntity.ok(Map.of("ok", true, "providers", providers));
	}

	/**
	 * Redirects the browser to the configured IdP's authorization endpoint.
	 *
	 * <p>
	 * Endpoint: {@code GET /api/auth/sso/authorize/{providerId}}
	 *
	 * <p>
	 * A CSRF state nonce is generated and stored in the DB before the redirect. The
	 * optional {@code redirectUri} is embedded in the state so the callback can
	 * forward the user to the intended destination after successful login. Supports
	 * {@code google} and {@code oidc} provider types.
	 *
	 * @param providerId  the SSO provider ID (matches a row in
	 *                    {@code sso_providers})
	 * @param redirectUri optional post-login redirect destination; defaults to
	 *                    {@code /app} or {@code /admin} based on user role
	 * @param res         the HTTP response used to issue the redirect
	 * @throws Exception if state creation or URL building fails
	 * @since v2026.1.0
	 */
	@GetMapping("/authorize/{providerId}")
	public void authorize(@PathVariable String providerId, @RequestParam(required = false) String redirectUri,
			HttpServletResponse res) throws Exception {
		Map<String, Object> provider = ssoService.getProvider(providerId);
		if (provider == null) {
			res.sendError(404, "SSO provider not found");
			return;
		}

		String state = ssoService.createState(providerId, redirectUri);
		String clientId = (String) provider.get("client_id");
		Map<String, Object> cfg = ssoService.parseConfig(provider);
		String type = (String) provider.get("type");
		String authUrl;

		authUrl = switch (type) {
		case "google" -> ssoService.buildGoogleAuthUrl(clientId, state, (String) cfg.get("hd"));
		case "oidc" -> ssoService.buildOidcAuthUrl((String) cfg.get("issuerUrl"), clientId, state);
		default -> {
			res.sendError(400, "Unsupported SSO type: " + type);
			yield null;
		}
		};
		if (authUrl != null)
			res.sendRedirect(authUrl);
	}

	/**
	 * Handles the OAuth 2.0 / OIDC authorization-code callback from the IdP.
	 *
	 * <p>
	 * Endpoint: {@code GET /api/auth/sso/callback}
	 *
	 * <p>
	 * Processing steps:
	 * <ol>
	 * <li>Validate the {@code state} nonce; redirect to
	 * {@code /login?sso_error=invalid_state} on failure.</li>
	 * <li>Exchange the authorization {@code code} for tokens using the appropriate
	 * provider (Google or OIDC).</li>
	 * <li>Extract user claims ({@code email}, {@code name}) from the ID token.</li>
	 * <li>Provision the user via {@link #provisionUser(SsoService.ClaimsResult)} if
	 * this is their first login.</li>
	 * <li>Issue a session cookie and redirect the browser to the intended
	 * destination.</li>
	 * </ol>
	 *
	 * @param code  the authorization code returned by the IdP
	 * @param state the CSRF nonce echoed back by the IdP
	 * @param error an OAuth error code from the IdP; triggers redirect to error
	 *              page if set
	 * @param req   the current HTTP request (used for setting the session cookie)
	 * @param res   the HTTP response used for redirects and cookie setting
	 * @throws Exception if URL encoding or HTTP I/O fails
	 * @since v2026.1.0
	 */
	@GetMapping("/callback")
	public void callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
			@RequestParam(required = false) String error, HttpServletRequest req, HttpServletResponse res)
			throws Exception {
		if (error != null) {
			res.sendRedirect("/login?sso_error=" + error);
			return;
		}

		Map<String, Object> stateData = ssoService.validateState(state);
		if (stateData == null) {
			res.sendRedirect("/login?sso_error=invalid_state");
			return;
		}

		String providerId = (String) stateData.get("provider_id");
		Map<String, Object> provider = ssoService.getProvider(providerId);
		if (provider == null) {
			res.sendRedirect("/login?sso_error=provider_not_found");
			return;
		}

		String type = (String) provider.get("type");
		String clientId = (String) provider.get("client_id");
		String clientSecret = ssoService.decryptSecret(provider);
		Map<String, Object> cfg = ssoService.parseConfig(provider);

		SsoService.ClaimsResult claims;
		try {
			claims = switch (type) {
			case "google" -> ssoService.exchangeGoogleCode(code, clientId, clientSecret);
			case "oidc" -> ssoService.exchangeOidcCode((String) cfg.get("issuerUrl"), clientId, clientSecret, code);
			default -> {
				res.sendRedirect("/login?sso_error=unsupported_type");
				yield null;
			}
			};
		} catch (Exception e) {
			res.sendRedirect("/login?sso_error=" + URLEncoder.encode(e.getMessage(), "UTF-8"));
			return;
		}
		if (claims == null)
			return;

		User user = provisionUser(claims);
		authService.setSession(res, req, user);
		String redirectTo = (String) stateData.get("redirect_uri");
		res.sendRedirect(redirectTo != null && !redirectTo.isBlank() ? redirectTo
				: ("admin".equals(user.role) ? "/admin" : "/app"));
	}

	/**
	 * SAML 2.0 Assertion Consumer Service (ACS) — receives a POST from the IdP
	 * containing a signed {@code SAMLResponse} and establishes a session.
	 *
	 * <p>
	 * Endpoint: {@code POST /api/auth/sso/saml/acs}
	 *
	 * <p>
	 * The SAML response is parsed and verified by
	 * {@link SsoService#parseSamlResponse(String)}. On parse failure, the user is
	 * redirected to {@code /login?sso_error=saml_parse_failed}. The
	 * {@code RelayState} parameter, if present, is reserved for future post-login
	 * redirect support.
	 *
	 * @param samlResponse the Base64-encoded {@code SAMLResponse} form parameter
	 *                     posted by the IdP
	 * @param relayState   optional opaque relay state from the IdP; currently
	 *                     unused
	 * @param req          the current HTTP request (used for setting the session
	 *                     cookie)
	 * @param res          the HTTP response used for session cookie and redirect
	 * @throws Exception if HTTP I/O fails
	 * @since v2026.1.4
	 */
	@PostMapping("/saml/acs")
	public void samlAcs(@RequestParam("SAMLResponse") String samlResponse,
			@RequestParam(value = "RelayState", required = false) String relayState, HttpServletRequest req,
			HttpServletResponse res) throws Exception {
		// CRIT-6 MITIGATION: SAML signature verification is not yet implemented.
		// Until OpenSAML-based XML signature verification is integrated, SAML SSO
		// is disabled by default. Admins can re-enable by setting saml.enabled=true
		// ONLY in environments where they accept the risk of unsigned assertions.
		boolean samlEnabled = "true"
				.equalsIgnoreCase(System.getProperty("saml.enabled", System.getenv("SAML_ENABLED")));
		if (!samlEnabled) {
			LoggerFactory.getLogger(SsoController.class)
					.warn("[sso] SAML ACS request rejected: SAML is disabled pending XML signature verification. "
							+ "Set SAML_ENABLED=true env var only if you accept unsigned assertion risk.");
			res.sendRedirect("/login?sso_error=saml_disabled");
			return;
		}
		SsoService.ClaimsResult claims;
		try {
			claims = ssoService.parseSamlResponse(samlResponse);
		} catch (Exception e) {
			res.sendRedirect("/login?sso_error=saml_parse_failed");
			return;
		}
		User user = provisionUser(claims);
		authService.setSession(res, req, user);
		res.sendRedirect("admin".equals(user.role) ? "/admin" : "/app");
	}

	// ── Admin CRUD for SSO providers ─────────────────────────────────────────

	/**
	 * Lists all SSO provider records including configuration details (admin only).
	 *
	 * <p>
	 * Endpoint: {@code GET /api/auth/sso/admin/providers}
	 *
	 * <p>
	 * Unlike the public {@link #listProviders()} endpoint, this response includes
	 * full provider configuration fields needed by the admin settings UI. Client
	 * secrets are stored encrypted; the response includes the encrypted form.
	 *
	 * @param req the current HTTP request (must carry an admin session)
	 * @return 200 OK with {@code {ok: true, providers: [...]}} on success; 401 if
	 *         unauthenticated; 403 if the caller is not an admin
	 * @since v2026.1.4
	 */
	@GetMapping("/admin/providers")
	public ResponseEntity<Map<String, Object>> adminListProviders(HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		return ResponseEntity.ok(Map.of("ok", true, "providers", db.queryForList(
				"SELECT id, type, name, enabled, client_id, config_json, created_at FROM sso_providers ORDER BY name")));
	}

	/**
	 * Creates a new SSO provider record (admin only).
	 *
	 * <p>
	 * Endpoint: {@code POST /api/auth/sso/admin/providers}
	 *
	 * <p>
	 * The client secret, if provided, is AES-encrypted before storage. The
	 * generated provider ID has the form {@code sso-<type>-<base36timestamp>}.
	 *
	 * @param body JSON request body with:
	 *             <ul>
	 *             <li>{@code type} (String) — {@code "google"}, {@code "oidc"}, or
	 *             {@code "saml"}</li>
	 *             <li>{@code name} (String) — display name shown on the login
	 *             page</li>
	 *             <li>{@code clientId} (String) — OAuth client ID</li>
	 *             <li>{@code clientSecret} (String, optional) — OAuth client
	 *             secret</li>
	 *             <li>{@code configJson} (String, optional) — provider-specific
	 *             JSON config</li>
	 *             </ul>
	 * @param req  the current HTTP request (must carry an admin session)
	 * @return 200 OK with {@code {ok: true, id: "sso-..."}}; 401/403 if not admin
	 * @since v2026.1.4
	 */
	@PostMapping("/admin/providers")
	public ResponseEntity<Map<String, Object>> adminCreateProvider(@RequestBody Map<String, Object> body,
			HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		String id = "sso-" + body.get("type") + "-" + Long.toString(System.currentTimeMillis(), 36) + "-"
				+ UUID.randomUUID().toString().substring(0, 6);
		String secretEnc = "";
		if (body.containsKey("clientSecret") && !body.get("clientSecret").toString().isBlank())
			secretEnc = cryptoService.encryptKey(body.get("clientSecret").toString());
		db.update(
				"INSERT INTO sso_providers (id, type, name, enabled, client_id, client_secret_enc, config_json, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
				id, body.get("type"), body.get("name"), 1, body.get("clientId"), secretEnc,
				body.getOrDefault("configJson", "{}").toString(), Instant.now().toString(), Instant.now().toString());
		return ResponseEntity.ok(Map.of("ok", true, "id", id));
	}

	/**
	 * Updates fields of an existing SSO provider record (admin only).
	 *
	 * <p>
	 * Endpoint: {@code PATCH /api/auth/sso/admin/providers/{id}}
	 *
	 * <p>
	 * Only the fields present in the request body are updated. Supported fields:
	 * {@code enabled}, {@code name}, {@code configJson}, {@code clientSecret}. An
	 * empty {@code clientSecret} string is ignored (does not clear an existing
	 * secret).
	 *
	 * @param id   the provider ID to update
	 * @param body JSON request body with any combination of the supported fields
	 * @param req  the current HTTP request (must carry an admin session)
	 * @return 200 OK with {@code {ok: true}}; 401/403 if not admin
	 * @since v2026.1.4
	 */
	@PatchMapping("/admin/providers/{id}")
	public ResponseEntity<Map<String, Object>> adminUpdateProvider(@PathVariable String id,
			@RequestBody Map<String, Object> body, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		if (body.containsKey("enabled"))
			db.update("UPDATE sso_providers SET enabled=?, updated_at=? WHERE id=?",
					Boolean.TRUE.equals(body.get("enabled")) ? 1 : 0, Instant.now().toString(), id);
		if (body.containsKey("name"))
			db.update("UPDATE sso_providers SET name=?, updated_at=? WHERE id=?", body.get("name"),
					Instant.now().toString(), id);
		if (body.containsKey("configJson"))
			db.update("UPDATE sso_providers SET config_json=?, updated_at=? WHERE id=?", body.get("configJson"),
					Instant.now().toString(), id);
		if (body.containsKey("clientSecret") && !body.get("clientSecret").toString().isBlank())
			db.update("UPDATE sso_providers SET client_secret_enc=?, updated_at=? WHERE id=?",
					cryptoService.encryptKey(body.get("clientSecret").toString()), Instant.now().toString(), id);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	/**
	 * Deletes an SSO provider record permanently (admin only).
	 *
	 * <p>
	 * Endpoint: {@code DELETE /api/auth/sso/admin/providers/{id}}
	 *
	 * <p>
	 * Existing SSO users whose {@code auth_provider} matches this provider will
	 * retain their user record but will no longer be able to sign in via SSO.
	 *
	 * @param id  the provider ID to delete
	 * @param req the current HTTP request (must carry an admin session)
	 * @return 200 OK with {@code {ok: true}}; 401/403 if not admin
	 * @since v2026.1.4
	 */
	@DeleteMapping("/admin/providers/{id}")
	public ResponseEntity<Map<String, Object>> adminDeleteProvider(@PathVariable String id, HttpServletRequest req) {
		ResponseEntity<Map<String, Object>> err = requireAdmin(req);
		if (err != null)
			return err;
		db.update("DELETE FROM sso_providers WHERE id = ?", id);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	// ── Private helpers ─────────────────────────────────────────────────────

	/**
	 * Provisions a user account from IdP claims, creating one if it does not yet
	 * exist.
	 *
	 * <p>
	 * If a user with the given email already exists, they are returned as-is.
	 * Otherwise a new {@code "user"}-role account is created with a {@code NULL}
	 * password hash (preventing password-based login) and the provider type
	 * recorded in {@code auth_provider}.
	 *
	 * @param claims the verified identity claims extracted from the IdP token or
	 *               assertion
	 * @return the existing or newly-created {@link User} object
	 * @since v2026.1.0
	 */
	private User provisionUser(SsoService.ClaimsResult claims) {
		User user = userService.findUserByEmail(claims.email());
		if (user == null) {
			String newId = "u-sso-" + Long.toString(System.currentTimeMillis(), 36) + "-"
					+ UUID.randomUUID().toString().substring(0, 6);
			db.update(
					"INSERT INTO users (id, name, email, password_hash, role, auth_provider, access_status, created_at) VALUES (?,?,?,NULL,'user',?,?,?)",
					newId, claims.name(), claims.email(), claims.provider(), "active", Instant.now().toString());
			user = userService.findUserByEmail(claims.email());
		}
		return user;
	}

	/**
	 * Guards admin-only endpoints; returns an error response if the caller is not
	 * an admin.
	 *
	 * @param req the current HTTP request
	 * @return {@code null} if the caller is an authenticated admin; 401 response if
	 *         unauthenticated; 403 response if not an admin
	 * @since v2026.1.0
	 */
	private ResponseEntity<Map<String, Object>> requireAdmin(HttpServletRequest req) {
		User user = (User) req.getAttribute("authenticatedUser");
		if (user == null)
			return ResponseEntity.status(401).body(Map.of("ok", false, "error", "Unauthorized"));
		if (!"admin".equals(user.role))
			return ResponseEntity.status(403).body(Map.of("ok", false, "error", "Forbidden"));
		return null;
	}
}
