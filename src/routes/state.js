/**
 * @file src/routes/state.js
 * @description State routes: /api/state, /api/ollama/ping, /api/ollama/models.
 */

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql, one, rows } = deps;
  const { requireAuth } = deps;
  const { publicUser, USER_SELECT, allowedModelIds, roleCatalog, permissionCatalog, effectiveAccess } = deps;
  const { parseModel } = deps;
  const { settingsState, setSetting } = deps;
  const { safeJson } = deps;
  const { syncOllamaModels, ollamaUrl, cleanBaseUrl } = deps;
  const { buildChatObject, getActiveChat } = deps;
  const { workspaceForUser } = deps;

  // [GET] /api/state — Auth: requireAuth — Purpose: return the full app state (user, models, chats, settings, access)
  //   in one call so the SPA can hydrate with a single fetch on load
  router.get("/state", requireAuth, async (req, res) => {
    const db = openSql();
    try {
      setSetting(db, "activeUserId", req.user.id);
      // Model state is kept fresh by the background sync timer started at server boot.
      // No sync needed here — just read cached DB state.
      const user = publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.user.id));
      const models = rows(db, "SELECT * FROM models ORDER BY provider, name").map(parseModel);
      let chats;
      if (user.role === "admin") {
        const chatSessions = db.prepare("SELECT * FROM chat_sessions WHERE is_active = 1 ORDER BY updated_at DESC").all();
        // Lightweight metadata only — no messages loaded for admin state
        chats = chatSessions.map(s => ({
          id: s.id,
          userId: s.user_id,
          title: s.title,
          pinned: Boolean(s.pinned),
          archived: Boolean(s.archived),
          unread: Boolean(s.unread),
          isActive: Boolean(s.is_active),
          messageCount: db.prepare("SELECT COUNT(*) as n FROM chat_messages WHERE session_id = ?").get(s.id).n,
          updatedAt: s.updated_at,
        }));
      } else {
        // Ensure an active session exists (creates one if the user has none)
        getActiveChat(db, user.id);
        // Return session metadata for this user (no full messages in state)
        const allSessions = db.prepare(
          "SELECT * FROM chat_sessions WHERE user_id = ? ORDER BY pinned DESC, updated_at DESC LIMIT 50"
        ).all(user.id);
        chats = allSessions.map(s => buildChatObject(db, s));
      }
      const auditRows = db.prepare("SELECT * FROM audit_events ORDER BY created_at DESC LIMIT 20").all();
      const audit = auditRows.map(r => ({ id: r.id, actor: r.actor, action: r.action, detail: r.detail, extra: safeJson(r.extra_json, {}), createdAt: r.created_at }));
      res.json({
        activeUser: user,
        // users omitted — fetch via GET /api/admin/users for paginated access
        departments: (() => {
          const depts = rows(db, "SELECT id, name FROM departments ORDER BY name");
          let deptRights = {};
          try { deptRights = JSON.parse(deps.setting(db, "deptDefaultRights", "{}")); } catch {}
          return depts.map(d => ({ ...d, defaultRights: deptRights[d.id] || [] }));
        })(),
        groups: rows(db, "SELECT id, name FROM groups ORDER BY name"),
        teams: rows(db, "SELECT id, name, description FROM teams ORDER BY name"),
        models,
        settings: settingsState(db),
        chats,
        audit,
        allowedModelIds: allowedModelIds(db, user),
        roles: roleCatalog(db),
        permissions: permissionCatalog(db),
        effectiveAccess: effectiveAccess(db, user),
        workspace: workspaceForUser(db, user.id),
      });
    } finally {
      db.close();
    }
  });

  // [GET] /api/ollama/ping — Auth: requireAuth — Purpose: fast 2-second connectivity check, no DB sync (used by status chip)
  router.get("/ollama/ping", requireAuth, async (req, res) => {
    const db = openSql();
    const url = cleanBaseUrl(ollamaUrl(db));
    db.close();
    try {
      const r = await fetch(`${url}/api/tags`, { signal: AbortSignal.timeout(2000) });
      res.json({ ok: r.ok });
    } catch {
      res.json({ ok: false });
    }
  });

  // [GET] /api/ollama/models — Auth: requireAuth — Purpose: trigger an Ollama sync and return result (used by sync button)
  router.get("/ollama/models", requireAuth, async (req, res) => {
    const db = openSql();
    try {
      const result = await syncOllamaModels(db);
      res.json(result);
    } catch (error) {
      res.json({ ok: false, error: error.message, models: [] });
    } finally {
      db.close();
    }
  });

  return router;
};
