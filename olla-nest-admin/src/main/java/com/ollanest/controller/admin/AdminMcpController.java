package com.ollanest.controller.admin;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ollanest.controller.BaseController;
import com.ollanest.service.McpServerService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Admin REST controller for managing MCP (Model Context Protocol) servers.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * MCP servers extend the assistant with external tools. Registering, connecting,
 * and curating which of their tools are enabled is a privileged, system-wide
 * operation, so this controller is admin-gated. Connection lifecycle and tool
 * discovery are delegated to {@link McpServerService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Each handler short-circuits via {@link BaseController#requireAdmin}, which
 * returns a non-null error response when the caller is not an admin.</li>
 * <li>Individual tools can be disabled per server via {@link #setDisabledTools}
 * without removing the server itself.</li>
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
@RequestMapping("/api/admin/mcp")
public class AdminMcpController extends BaseController {

    /** Service backing MCP server registration, connection, and tool discovery. */
    private final McpServerService mcpService;

    /**
     * Constructor-injects the MCP server service.
     *
     * @param mcpService the service backing all MCP operations
     * @since v2026.2.1
     */
    public AdminMcpController(McpServerService mcpService) {
        this.mcpService = mcpService;
    }

    /**
     * Lists all registered MCP servers.
     *
     * @param req the HTTP request; must resolve to an admin user
     * @return an OK response with the servers, or an admin error response
     * @since v2026.2.1
     */
    @GetMapping("/servers")
    public ResponseEntity<?> list(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        return ok(mcpService.list());
    }

    /**
     * Registers a new MCP server.
     *
     * @param req  the HTTP request; must resolve to an admin user
     * @param body the server definition (transport, command/URL, etc.)
     * @return a CREATED response with the persisted server, or an admin error
     *         response
     * @since v2026.2.1
     */
    @PostMapping("/servers")
    public ResponseEntity<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        return created(mcpService.create(body));
    }

    /**
     * Deletes a registered MCP server.
     *
     * @param req the HTTP request; must resolve to an admin user
     * @param id  the id of the server to delete
     * @return an OK response acknowledging the deletion, or an admin error response
     * @since v2026.2.1
     */
    @DeleteMapping("/servers/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        mcpService.delete(id);
        return ok(Map.of("ok", true));
    }

    /**
     * Connects to a registered MCP server.
     *
     * @param req the HTTP request; must resolve to an admin user
     * @param id  the id of the server to connect
     * @return an OK response with the connection result, or an admin error response
     * @since v2026.2.1
     */
    @PostMapping("/servers/{id}/connect")
    public ResponseEntity<?> connect(HttpServletRequest req, @PathVariable String id) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        return ok(mcpService.connect(id));
    }

    /**
     * Disconnects from a connected MCP server.
     *
     * @param req the HTTP request; must resolve to an admin user
     * @param id  the id of the server to disconnect
     * @return an OK response acknowledging the disconnect, or an admin error
     *         response
     * @since v2026.2.1
     */
    @PostMapping("/servers/{id}/disconnect")
    public ResponseEntity<?> disconnect(HttpServletRequest req, @PathVariable String id) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        mcpService.disconnect(id);
        return ok(Map.of("ok", true));
    }

    /**
     * Sets the list of disabled tools for an MCP server.
     *
     * @param req  the HTTP request; must resolve to an admin user
     * @param id   the id of the server whose tools are configured
     * @param body request payload; {@code disabled_tools} lists tool names to
     *             disable
     * @return an OK response acknowledging the change, or an admin error response
     * @since v2026.2.1
     */
    @PostMapping("/servers/{id}/tools")
    public ResponseEntity<?> setDisabledTools(HttpServletRequest req, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        @SuppressWarnings("unchecked")
        List<String> disabled = (List<String>) body.getOrDefault("disabled_tools", List.of());
        mcpService.setDisabledTools(id, disabled);
        return ok(Map.of("ok", true));
    }

    /**
     * Lists every enabled tool across all connected MCP servers.
     *
     * @param req the HTTP request; must resolve to an admin user
     * @return an OK response with the aggregated enabled tools, or an admin error
     *         response
     * @since v2026.2.1
     */
    @GetMapping("/tools")
    public ResponseEntity<?> allTools(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        return ok(mcpService.getAllEnabledTools());
    }
}
