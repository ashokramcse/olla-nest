/**
 * @file src/routes/pages.js
 * @description Page routes: GET /, /login, /app, /admin-login, /admin, 404 catch-all.
 */

const path = require("path");
const { STATIC_DIR } = require("../config");

module.exports = function(deps) {
  const router = require("express").Router();
  const { sessionUser } = deps;

  // [GET] / — Auth: public — Purpose: redirect root to /login
  router.get("/", (req, res) => res.redirect("/login"));

  // [GET] /login — Auth: public — Purpose: serve login.html; redirects to /app if already authenticated
  router.get("/login", (req, res) => {
    if (sessionUser(req)) return res.redirect("/app");
    res.sendFile(path.join(STATIC_DIR, "login.html"));
  });

  // [GET] /app — Auth: requireAuth (redirect) — Purpose: serve app.html; redirects to /login if unauthenticated
  router.get("/app", (req, res) => {
    if (!sessionUser(req)) return res.redirect("/login");
    res.sendFile(path.join(STATIC_DIR, "app.html"));
  });

  // [GET] /admin-login — Auth: public — Purpose: serve admin-login.html; redirects admins to /admin, other users to /app
  router.get("/admin-login", (req, res) => {
    const user = sessionUser(req);
    if (user && user.role === "admin") return res.redirect("/admin");
    if (user) return res.redirect("/app");
    res.sendFile(path.join(STATIC_DIR, "admin-login.html"));
  });

  // [GET] /admin — Auth: admin session (redirect) — Purpose: serve admin.html; non-admins redirected to /app
  router.get("/admin", (req, res) => {
    const user = sessionUser(req);
    if (!user) return res.redirect("/admin-login");
    if (user.role !== "admin") return res.redirect("/app");
    res.sendFile(path.join(STATIC_DIR, "admin.html"));
  });

  // 404 catch-all
  router.use((req, res) => {
    res.redirect("/login");
  });

  return router;
};
