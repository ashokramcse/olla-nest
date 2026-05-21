package com.ollanest.controller;

import com.ollanest.model.User;
import com.ollanest.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.File;

@Controller
public class PageController extends BaseController {

    private final AuthService authService;

    @Value("${app.static-dir:./public}")
    private String staticDir;

    public PageController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login(HttpServletRequest req) {
        if (getUser(req) != null) return "redirect:/app";
        return "forward:/login.html";
    }

    @GetMapping("/app")
    public String app(HttpServletRequest req) {
        if (getUser(req) == null) return "redirect:/login";
        return "forward:/app.html";
    }

    @GetMapping("/admin-login")
    public String adminLogin(HttpServletRequest req) {
        User user = getUser(req);
        if (user != null && "admin".equals(user.role)) return "redirect:/admin";
        if (user != null) return "redirect:/app";
        return "forward:/admin-login.html";
    }

    @GetMapping("/admin")
    public String admin(HttpServletRequest req) {
        User user = getUser(req);
        if (user == null) return "redirect:/admin-login";
        if (!"admin".equals(user.role)) return "redirect:/app";
        return "forward:/admin.html";
    }
}
