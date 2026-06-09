package com.ollanest.controller.admin;

import com.ollanest.controller.BaseController;
import com.ollanest.service.SkillsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Admin REST controller for moderating the skills library.
 *
 * <h3>Why this class exists</h3>
 * <p>
 * Skills can be learned/proposed automatically and then need human review before
 * entering the shared library. This controller lets admins list, approve,
 * archive, and delete skills regardless of owner, complementing the per-user
 * {@code SkillsController}. The work is delegated to {@link SkillsService}.
 *
 * <h3>Design notes</h3>
 * <ul>
 * <li>Each handler short-circuits via {@link BaseController#requireAdmin}, which
 * returns a non-null error response when the caller is not an admin.</li>
 * <li>Deletion passes a {@code null} owner to {@link SkillsService#deleteSkill}
 * so an admin may remove any skill, not just their own.</li>
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
@RequestMapping("/api/admin/skills")
public class AdminSkillsController extends BaseController {

    /** Service backing skill listing, approval, archival, and deletion. */
    private final SkillsService skillsService;

    /**
     * Constructor-injects the skills service.
     *
     * @param skillsService the service backing all skill moderation operations
     * @since v2026.2.1
     */
    public AdminSkillsController(SkillsService skillsService) {
        this.skillsService = skillsService;
    }

    /**
     * Lists skills for moderation, optionally filtered by status.
     *
     * @param req    the HTTP request; must resolve to an admin user
     * @param status optional lifecycle status filter (e.g. pending, active)
     * @return an OK response with the matching skills, or an admin error response
     * @since v2026.2.1
     */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest req,
            @RequestParam(required = false) String status) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        return ok(skillsService.list("admin", null, status, 500));
    }

    /**
     * Approves a pending skill, promoting it into the active library.
     *
     * @param req the HTTP request; must resolve to an admin user
     * @param id  the id of the skill to approve
     * @return an OK response acknowledging the approval, or an admin error response
     * @since v2026.2.1
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(HttpServletRequest req, @PathVariable String id) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        skillsService.approve(id);
        return ok(Map.of("ok", true));
    }

    /**
     * Archives a skill, removing it from active use without deleting it.
     *
     * @param req the HTTP request; must resolve to an admin user
     * @param id  the id of the skill to archive
     * @return an OK response acknowledging the archival, or an admin error response
     * @since v2026.2.1
     */
    @PostMapping("/{id}/archive")
    public ResponseEntity<?> archive(HttpServletRequest req, @PathVariable String id) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        skillsService.archive(id);
        return ok(Map.of("ok", true));
    }

    /**
     * Deletes any skill (admin override of ownership).
     *
     * @param req the HTTP request; must resolve to an admin user
     * @param id  the id of the skill to delete
     * @return an OK response acknowledging the deletion, or an admin error response
     * @since v2026.2.1
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        ResponseEntity<Map<String, Object>> err = requireAdmin(req);
        if (err != null) return err;
        skillsService.deleteSkill(id, null); // admin can delete any skill
        return ok(Map.of("ok", true));
    }
}
