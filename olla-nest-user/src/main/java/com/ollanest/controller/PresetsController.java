package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.PresetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * REST controller exposing user-defined presets and reusable prompt templates.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Users curate their own library of templates (saved prompts/configurations) to
 * reuse across sessions. This controller provides the CRUD surface over that
 * library, delegating storage and ownership enforcement to {@link PresetService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Every endpoint resolves the caller via {@link BaseController#requireAuth}
 * and scopes operations to that user's id, so templates are isolated per user.</li>
 * <li>The collection {@code list} endpoint returns all presets; template
 * mutations live under the {@code /templates} sub-path.</li>
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
@RequestMapping("/api/presets")
public class PresetsController extends BaseController {

    /** Service backing preset/template persistence and ownership checks. */
    private final PresetService presetService;

    /**
     * Constructor-injects the preset service.
     *
     * @param presetService the service backing all preset operations
     * @since v2026.2.1
     */
    public PresetsController(PresetService presetService) {
        this.presetService = presetService;
    }

    /**
     * Lists all presets owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @return an OK response with the user's presets
     * @since v2026.2.1
     */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(presetService.listAll(user.id));
    }

    /**
     * Creates a new template for the calling user.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param body the template definition
     * @return a CREATED response with the persisted template
     * @since v2026.2.1
     */
    @PostMapping("/templates")
    public ResponseEntity<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return created(presetService.createTemplate(user.id, body));
    }

    /**
     * Updates an existing template owned by the calling user.
     *
     * @param req  the HTTP request, used to resolve the authenticated user
     * @param id   the id of the template to update
     * @param body the updated template fields
     * @return an OK response with the updated template
     * @since v2026.2.1
     */
    @PutMapping("/templates/{id}")
    public ResponseEntity<?> update(HttpServletRequest req, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return ok(presetService.updateTemplate(id, user.id, body));
    }

    /**
     * Deletes a template owned by the calling user.
     *
     * @param req the HTTP request, used to resolve the authenticated user
     * @param id  the id of the template to delete
     * @return an OK response acknowledging the deletion
     * @since v2026.2.1
     */
    @DeleteMapping("/templates/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        User user = requireAuth(req);
        presetService.deleteTemplate(id, user.id);
        return ok(Map.of("ok", true));
    }
}
