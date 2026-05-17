/**
 * @file src/routes/bootstrap.js
 * @description Bootstrap route: /api/bootstrap.
 */

const bcrypt = require("bcryptjs");

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql, one } = deps;
  const { DEFAULT_ADMIN_EMAIL, DEFAULT_ADMIN_PASSWORD } = deps;

  // [GET] /api/bootstrap — Auth: public — Purpose: return default admin credentials hint on first boot only
  router.get("/", (req, res) => {
    // Only expose admin email+password hint on first-boot (when default password is still in use)
    const db = openSql();
    try {
      const admin = one(db, "SELECT password_hash FROM users WHERE id = 'u-admin'");
      const isDefaultPassword = admin && bcrypt.compareSync(DEFAULT_ADMIN_PASSWORD, admin.password_hash);
      if (isDefaultPassword) {
        return res.json({ ready: true, adminEmail: DEFAULT_ADMIN_EMAIL, adminPassword: DEFAULT_ADMIN_PASSWORD });
      }
      res.json({ ready: true });
    } finally {
      db.close();
    }
  });

  return router;
};
