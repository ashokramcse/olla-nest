package com.ollanest.controller.admin;

import com.ollanest.controller.BaseController;
import com.ollanest.service.McpServerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/** Admin MCP server management API. */
@RestController
@RequestMapping("/api/admin/mcp")
public class AdminMcpController extends BaseController {

    private final McpServerService mcpService;

    public AdminMcpController(McpServerService mcpService) {
        this.mcpService = mcpService;
    }

    @GetMapping("/servers")
    public ResponseEntity<?> list(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        return ok(mcpService.list());
    }

    @PostMapping("/servers")
    public ResponseEntity<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        return created(mcpService.create(body));
    }

    @DeleteMapping("/servers/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        mcpService.delete(id);
        return ok(Map.of("ok", true));
    }

    @PostMapping("/servers/{id}/connect")
    public ResponseEntity<?> connect(HttpServletRequest req, @PathVariable String id) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        return ok(mcpService.connect(id));
    }

    @PostMapping("/servers/{id}/disconnect")
    public ResponseEntity<?> disconnect(HttpServletRequest req, @PathVariable String id) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        mcpService.disconnect(id);
        return ok(Map.of("ok", true));
    }

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

    @GetMapping("/tools")
    public ResponseEntity<?> allTools(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        return ok(mcpService.getAllEnabledTools());
    }
}
