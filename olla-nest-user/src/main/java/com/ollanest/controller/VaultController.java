package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.VaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * REST controller integrating the Bitwarden/Vaultwarden CLI as a secrets vault.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Lets the application retrieve credentials from a Bitwarden-compatible vault via
 * the {@code bw} CLI. Configuration, unlocking with the master password, and item
 * retrieval are sensitive operations, so they are restricted to administrators;
 * only the read-only lock status is available to any authenticated user. The CLI
 * interaction and session handling are delegated to {@link VaultService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Configuration, unlock/lock, and item retrieval require an admin via
 * {@link BaseController#requireAdminUser}; the master password is never
 * persisted.</li>
 * <li>{@link #status} only requires authentication so non-admin clients can
 * detect whether the vault is currently unlocked.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <ul>
 * <li>v2026.2.1 — documented as part of the project-wide Javadoc pass</li>
 * </ul>
 *
 * @author Ashok Ram
 * @since v2026.2.1
 * @version v2026.2.1
 */
@RestController
@RequestMapping("/api/vault")
public class VaultController extends BaseController {

    /** Service wrapping the Bitwarden CLI and vault session state. */
    private final VaultService vaultService;

    /**
     * Constructor-injects the vault service.
     *
     * @param vaultService the service backing all vault operations
     * @since v2026.2.1
     */
    public VaultController(VaultService vaultService) {
        this.vaultService = vaultService;
    }

    /**
     * Returns the current vault configuration (admin only).
     *
     * @param req the HTTP request; must resolve to an admin user
     * @return an OK response with the vault configuration
     * @since v2026.2.1
     */
    @GetMapping
    public ResponseEntity<?> config(HttpServletRequest req) {
        User user = requireAdminUser(req);
        return ok(vaultService.getConfig());
    }

    /**
     * Saves the vault configuration (admin only).
     *
     * @param req  the HTTP request; must resolve to an admin user
     * @param body request payload carrying {@code bw_path} and {@code server_url}
     * @return an OK response acknowledging the save
     * @since v2026.2.1
     */
    @PostMapping("/config")
    public ResponseEntity<?> saveConfig(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        requireAdminUser(req);
        vaultService.saveConfig((String) body.get("bw_path"), (String) body.get("server_url"));
        return ok(Map.of("ok", true));
    }

    /**
     * Unlocks the vault with the master password (admin only).
     *
     * @param req  the HTTP request; must resolve to an admin user
     * @param body request payload carrying {@code master_password}
     * @return an OK response with the unlock result, or a 400 if the password is
     *         missing or blank
     * @since v2026.2.1
     */
    @PostMapping("/unlock")
    public ResponseEntity<?> unlock(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        requireAdminUser(req);
        String password = (String) body.get("master_password");
        if (password == null || password.isBlank()) return badRequest("master_password is required");
        return ok(vaultService.unlock(password));
    }

    /**
     * Locks the vault, clearing the unlocked session (admin only).
     *
     * @param req the HTTP request; must resolve to an admin user
     * @return an OK response acknowledging the lock
     * @since v2026.2.1
     */
    @PostMapping("/lock")
    public ResponseEntity<?> lock(HttpServletRequest req) {
        requireAdminUser(req);
        vaultService.lock();
        return ok(Map.of("ok", true));
    }

    /**
     * Reports whether the vault is currently unlocked.
     *
     * @param req the HTTP request; any authenticated user is allowed
     * @return an OK response whose {@code unlocked} flag reflects vault state
     * @since v2026.2.1
     */
    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest req) {
        requireAuth(req);
        return ok(Map.of("unlocked", vaultService.isUnlocked()));
    }

    /**
     * Retrieves a single named item from the vault (admin only).
     *
     * @param req  the HTTP request; must resolve to an admin user
     * @param name the name of the vault item to retrieve
     * @return an OK response with the requested item
     * @since v2026.2.1
     */
    @GetMapping("/item/{name}")
    public ResponseEntity<?> getItem(HttpServletRequest req, @PathVariable String name) {
        requireAdminUser(req);
        return ok(vaultService.getItem(name));
    }
}
