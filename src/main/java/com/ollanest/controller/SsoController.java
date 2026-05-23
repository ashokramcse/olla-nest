package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;
import com.ollanest.service.CryptoService;
import com.ollanest.service.SsoService;
import com.ollanest.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SSO endpoints for Google OAuth 2.0, Generic OIDC, and SAML 2.0.
 *
 * <p>All three flows ultimately call {@link AuthService#setSession} so the
 * resulting session cookie is identical to a password-based login.
 *
 * <p>Endpoints:
 * <pre>
 *   GET  /api/auth/sso/providers         — list enabled providers (login page)
 *   GET  /api/auth/sso/authorize/{id}    — redirect to IdP
 *   GET  /api/auth/sso/callback          — OAuth/OIDC code exchange
 *   POST /api/auth/sso/saml/acs          — SAML assertion consumer
 * </pre>
 */
@RestController
@RequestMapping("/api/auth/sso")
public class SsoController {

    private final SsoService ssoService;
    private final AuthService authService;
    private final CryptoService cryptoService;
    private final UserService userService;
    private final JdbcTemplate db;

    public SsoController(SsoService ssoService, AuthService authService,
                         CryptoService cryptoService, UserService userService, JdbcTemplate db) {
        this.ssoService    = ssoService;
        this.authService   = authService;
        this.cryptoService = cryptoService;
        this.userService   = userService;
        this.db            = db;
    }

    /** List enabled SSO providers (used by login page to render SSO buttons). */
    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> listProviders() {
        List<Map<String, Object>> providers = ssoService.listEnabledProviders().stream()
                .map(p -> Map.<String, Object>of(
                        "id",   p.get("id"),
                        "type", p.get("type"),
                        "name", p.get("name")))
                .toList();
        return ResponseEntity.ok(Map.of("ok", true, "providers", providers));
    }

    /** Redirect browser to IdP. Stores a state nonce to prevent CSRF. */
    @GetMapping("/authorize/{providerId}")
    public void authorize(@PathVariable String providerId,
                          @RequestParam(required = false) String redirectUri,
                          HttpServletResponse res) throws Exception {
        Map<String, Object> provider = ssoService.getProvider(providerId);
        if (provider == null) { res.sendError(404, "SSO provider not found"); return; }

        String state = ssoService.createState(providerId, redirectUri);
        String clientId = (String) provider.get("client_id");
        Map<String, Object> cfg = ssoService.parseConfig(provider);
        String type = (String) provider.get("type");
        String authUrl;

        authUrl = switch (type) {
            case "google" -> ssoService.buildGoogleAuthUrl(clientId, state, (String) cfg.get("hd"));
            case "oidc"   -> ssoService.buildOidcAuthUrl((String) cfg.get("issuerUrl"), clientId, state);
            default -> { res.sendError(400, "Unsupported SSO type: " + type); yield null; }
        };
        if (authUrl != null) res.sendRedirect(authUrl);
    }

    /** OAuth/OIDC callback — exchange code, provision user, set session, redirect. */
    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         @RequestParam(required = false) String error,
                         HttpServletRequest req, HttpServletResponse res) throws Exception {
        if (error != null) { res.sendRedirect("/login?sso_error=" + error); return; }

        Map<String, Object> stateData = ssoService.validateState(state);
        if (stateData == null) { res.sendRedirect("/login?sso_error=invalid_state"); return; }

        String providerId = (String) stateData.get("provider_id");
        Map<String, Object> provider = ssoService.getProvider(providerId);
        if (provider == null) { res.sendRedirect("/login?sso_error=provider_not_found"); return; }

        String type = (String) provider.get("type");
        String clientId     = (String) provider.get("client_id");
        String clientSecret = ssoService.decryptSecret(provider);
        Map<String, Object> cfg = ssoService.parseConfig(provider);

        SsoService.ClaimsResult claims;
        try {
            claims = switch (type) {
                case "google" -> ssoService.exchangeGoogleCode(code, clientId, clientSecret);
                case "oidc"   -> ssoService.exchangeOidcCode((String) cfg.get("issuerUrl"), clientId, clientSecret, code);
                default -> { res.sendRedirect("/login?sso_error=unsupported_type"); yield null; }
            };
        } catch (Exception e) {
            res.sendRedirect("/login?sso_error=" + java.net.URLEncoder.encode(e.getMessage(), "UTF-8"));
            return;
        }
        if (claims == null) return;

        User user = provisionUser(claims);
        authService.setSession(res, req, user);
        String redirectTo = (String) stateData.get("redirect_uri");
        res.sendRedirect(redirectTo != null && !redirectTo.isBlank() ? redirectTo :
                ("admin".equals(user.role) ? "/admin" : "/app"));
    }

    /** SAML Assertion Consumer Service — POST from IdP with SAMLResponse param. */
    @PostMapping("/saml/acs")
    public void samlAcs(@RequestParam("SAMLResponse") String samlResponse,
                        @RequestParam(value = "RelayState", required = false) String relayState,
                        HttpServletRequest req, HttpServletResponse res) throws Exception {
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

    @GetMapping("/admin/providers")
    public ResponseEntity<Map<String, Object>> adminListProviders(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        return ResponseEntity.ok(Map.of("ok", true, "providers",
                db.queryForList("SELECT id, type, name, enabled, client_id, config_json, created_at FROM sso_providers ORDER BY name")));
    }

    @PostMapping("/admin/providers")
    public ResponseEntity<Map<String, Object>> adminCreateProvider(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        String id = "sso-" + body.get("type") + "-" + Long.toString(System.currentTimeMillis(), 36);
        String secretEnc = "";
        if (body.containsKey("clientSecret") && !body.get("clientSecret").toString().isBlank())
            secretEnc = cryptoService.encryptKey(body.get("clientSecret").toString());
        db.update("INSERT INTO sso_providers (id, type, name, enabled, client_id, client_secret_enc, config_json, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id, body.get("type"), body.get("name"), 1,
                body.get("clientId"), secretEnc, body.getOrDefault("configJson", "{}").toString(),
                Instant.now().toString(), Instant.now().toString());
        return ResponseEntity.ok(Map.of("ok", true, "id", id));
    }

    @PatchMapping("/admin/providers/{id}")
    public ResponseEntity<Map<String, Object>> adminUpdateProvider(
            @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        if (body.containsKey("enabled"))
            db.update("UPDATE sso_providers SET enabled=?, updated_at=? WHERE id=?", Boolean.TRUE.equals(body.get("enabled")) ? 1 : 0, Instant.now().toString(), id);
        if (body.containsKey("name"))
            db.update("UPDATE sso_providers SET name=?, updated_at=? WHERE id=?", body.get("name"), Instant.now().toString(), id);
        if (body.containsKey("configJson"))
            db.update("UPDATE sso_providers SET config_json=?, updated_at=? WHERE id=?", body.get("configJson"), Instant.now().toString(), id);
        if (body.containsKey("clientSecret") && !body.get("clientSecret").toString().isBlank())
            db.update("UPDATE sso_providers SET client_secret_enc=?, updated_at=? WHERE id=?",
                    cryptoService.encryptKey(body.get("clientSecret").toString()), Instant.now().toString(), id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/admin/providers/{id}")
    public ResponseEntity<Map<String, Object>> adminDeleteProvider(@PathVariable String id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        db.update("DELETE FROM sso_providers WHERE id = ?", id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private User provisionUser(SsoService.ClaimsResult claims) {
        User user = userService.findUserByEmail(claims.email());
        if (user == null) {
            String newId = "u-sso-" + Long.toString(System.currentTimeMillis(), 36);
            db.update("INSERT INTO users (id, name, email, password_hash, role, auth_provider, access_status, created_at) VALUES (?,?,?,NULL,'user',?,?,?)",
                    newId, claims.name(), claims.email(), claims.provider(), "active", Instant.now().toString());
            user = userService.findUserByEmail(claims.email());
        }
        return user;
    }

    private ResponseEntity<Map<String, Object>> requireAdmin(HttpServletRequest req) {
        User user = (User) req.getAttribute("authenticatedUser");
        if (user == null) return ResponseEntity.status(401).body(Map.of("ok", false, "error", "Unauthorized"));
        if (!"admin".equals(user.role)) return ResponseEntity.status(403).body(Map.of("ok", false, "error", "Forbidden"));
        return null;
    }
}
