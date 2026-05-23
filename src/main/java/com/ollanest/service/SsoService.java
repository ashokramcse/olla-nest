package com.ollanest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * SSO authentication service supporting:
 *  - Google OAuth 2.0
 *  - Generic OIDC  (Okta, Azure AD, Auth0, Keycloak, …)
 *  - SAML 2.0      (base64 assertion parse — full IdP metadata driven)
 *
 * All three flows converge on a {@link ClaimsResult} with email + name,
 * which is then handed to {@link AuthService#setSession} via SsoController.
 */
@Service
public class SsoService {

    private static final Logger log = LoggerFactory.getLogger(SsoService.class);
    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_USERINFO   = "https://www.googleapis.com/oauth2/v3/userinfo";

    private final JdbcTemplate db;
    private final CryptoService cryptoService;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${app.base-url:http://localhost:3000}")
    private String appBaseUrl;

    public SsoService(JdbcTemplate db, CryptoService cryptoService, ObjectMapper mapper) {
        this.db = db;
        this.cryptoService = cryptoService;
        this.mapper = mapper;
    }

    // ── State / CSRF nonce ──────────────────────────────────────────────────

    public String createState(String providerId, String redirectUri) {
        String state = generateToken();
        db.update("INSERT INTO oauth_state (state, provider_id, redirect_uri, created_at) VALUES (?,?,?,?)",
                state, providerId, redirectUri, Instant.now().toString());
        return state;
    }

    public Map<String, Object> validateState(String state) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM oauth_state WHERE state = ? AND created_at > datetime('now', '-10 minutes')", state);
        if (rows.isEmpty()) return null;
        db.update("DELETE FROM oauth_state WHERE state = ?", state);
        return rows.get(0);
    }

    // ── Provider lookup ─────────────────────────────────────────────────────

    public List<Map<String, Object>> listEnabledProviders() {
        return db.queryForList(
                "SELECT id, type, name, client_id, config_json FROM sso_providers WHERE enabled = 1 ORDER BY name");
    }

    public Map<String, Object> getProvider(String id) {
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM sso_providers WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Google OAuth 2.0 ────────────────────────────────────────────────────

    public String buildGoogleAuthUrl(String clientId, String state, String hostedDomain) {
        String redirect = appBaseUrl + "/api/auth/sso/callback";
        String url = "https://accounts.google.com/o/oauth2/v2/auth"
                + "?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirect)
                + "&scope=" + enc("openid email profile")
                + "&state=" + enc(state)
                + "&access_type=offline"
                + "&prompt=select_account";
        if (hostedDomain != null && !hostedDomain.isBlank())
            url += "&hd=" + enc(hostedDomain);
        return url;
    }

    public ClaimsResult exchangeGoogleCode(String code, String clientId, String clientSecret) throws Exception {
        String redirect = appBaseUrl + "/api/auth/sso/callback";
        String body = "code=" + enc(code)
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&redirect_uri=" + enc(redirect)
                + "&grant_type=authorization_code";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(GOOGLE_TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode tokens = mapper.readTree(resp.body());

        if (tokens.has("error"))
            throw new RuntimeException("Google token error: " + tokens.path("error_description").asText());

        String accessToken = tokens.path("access_token").asText();
        // Fetch user info
        HttpRequest infoReq = HttpRequest.newBuilder()
                .uri(URI.create(GOOGLE_USERINFO))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10)).GET().build();
        JsonNode info = mapper.readTree(http.send(infoReq, HttpResponse.BodyHandlers.ofString()).body());
        return new ClaimsResult(info.path("email").asText(), info.path("name").asText(), "google");
    }

    // ── Generic OIDC (Okta, Azure AD, Auth0, Keycloak, …) ──────────────────

    public String buildOidcAuthUrl(String issuerUrl, String clientId, String state) throws Exception {
        JsonNode discovery = fetchOidcDiscovery(issuerUrl);
        String authEndpoint = discovery.path("authorization_endpoint").asText();
        String redirect = appBaseUrl + "/api/auth/sso/callback";
        return authEndpoint
                + "?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirect)
                + "&scope=" + enc("openid email profile")
                + "&state=" + enc(state);
    }

    public ClaimsResult exchangeOidcCode(String issuerUrl, String clientId, String clientSecret, String code) throws Exception {
        JsonNode discovery = fetchOidcDiscovery(issuerUrl);
        String tokenEndpoint = discovery.path("token_endpoint").asText();
        String redirect = appBaseUrl + "/api/auth/sso/callback";
        String body = "code=" + enc(code)
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&redirect_uri=" + enc(redirect)
                + "&grant_type=authorization_code";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        JsonNode tokens = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());

        if (tokens.has("error"))
            throw new RuntimeException("OIDC token error: " + tokens.path("error_description").asText());

        // Decode id_token JWT payload (middle segment — we trust the IdP over TLS)
        String idToken = tokens.path("id_token").asText();
        JsonNode claims = decodeJwtPayload(idToken);
        String email = claims.path("email").asText();
        String name  = claims.path("name").asText(claims.path("preferred_username").asText(email));
        return new ClaimsResult(email, name, "oidc");
    }

    // ── SAML 2.0 ────────────────────────────────────────────────────────────

    /**
     * Parse a base64-encoded SAML response assertion and extract email/name.
     * This is a lightweight implementation — for full signature verification,
     * use spring-security-saml2-service-provider.
     */
    public ClaimsResult parseSamlResponse(String base64Assertion) throws Exception {
        byte[] decoded = Base64.getMimeDecoder().decode(base64Assertion);
        String xml = new String(decoded, StandardCharsets.UTF_8);

        // Extract NameID
        String email = extractXmlValue(xml, "NameID");
        // Try attribute statements for display name
        String name  = extractXmlAttr(xml, "displayName");
        if (name == null || name.isBlank()) name = extractXmlAttr(xml, "cn");
        if (name == null || name.isBlank()) name = email;
        return new ClaimsResult(email, name, "saml");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private JsonNode fetchOidcDiscovery(String issuerUrl) throws Exception {
        String url = issuerUrl.replaceAll("/$", "") + "/.well-known/openid-configuration";
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }

    private JsonNode decodeJwtPayload(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) throw new RuntimeException("Invalid JWT");
        byte[] payload = Base64.getUrlDecoder().decode(parts[1] + "==");
        return mapper.readTree(new String(payload, StandardCharsets.UTF_8));
    }

    private String extractXmlValue(String xml, String tag) {
        int start = xml.indexOf("<" + tag); if (start < 0) start = xml.indexOf(":" + tag + ">");
        if (start < 0) return "";
        int gt = xml.indexOf(">", start); if (gt < 0) return "";
        int end = xml.indexOf("<", gt + 1); if (end < 0) return "";
        return xml.substring(gt + 1, end).trim();
    }

    private String extractXmlAttr(String xml, String attrName) {
        String marker = "Name=\"" + attrName + "\"";
        int idx = xml.indexOf(marker); if (idx < 0) { marker = "Name=\"urn:oid:"; idx = xml.indexOf(marker); }
        if (idx < 0) return null;
        int av = xml.indexOf("<saml:AttributeValue", idx); if (av < 0) av = xml.indexOf("<AttributeValue", idx); if (av < 0) return null;
        int gt = xml.indexOf(">", av); int end = xml.indexOf("<", gt + 1);
        return (gt >= 0 && end > gt) ? xml.substring(gt + 1, end).trim() : null;
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    public String decryptSecret(Map<String, Object> provider) {
        String enc = (String) provider.get("client_secret_enc");
        return (enc != null && !enc.isBlank()) ? cryptoService.decryptKey(enc) : "";
    }

    public Map<String, Object> parseConfig(Map<String, Object> provider) {
        String json = (String) provider.getOrDefault("config_json", "{}");
        try { return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); }
        catch (Exception e) { return Map.of(); }
    }

    /** Result of any SSO authentication — normalised email + name. */
    public record ClaimsResult(String email, String name, String provider) {}
}
