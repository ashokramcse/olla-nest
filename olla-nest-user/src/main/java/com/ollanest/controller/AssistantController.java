package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.PersonalAssistantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Personal AI assistant configuration API. */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController extends BaseController {

    private final PersonalAssistantService assistantService;

    public AssistantController(PersonalAssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @GetMapping
    public ResponseEntity<?> get(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(assistantService.getOrCreate(user.id));
    }

    @PutMapping
    public ResponseEntity<?> update(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        User user = requireAuth(req);
        return ok(assistantService.update(user.id, body));
    }

    @GetMapping("/check-ins")
    public ResponseEntity<?> checkIns(HttpServletRequest req) {
        User user = requireAuth(req);
        return ok(assistantService.getCheckIns(user.id));
    }
}
