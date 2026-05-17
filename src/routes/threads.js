/**
 * @file src/routes/threads.js
 * @description Thread routes: /api/threads (GET,DELETE,PATCH), /api/threads/:id/activate,
 * /api/threads/:id/fork.
 */

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql } = deps;
  const { requireAuth } = deps;
  const { uid } = deps;
  const { buildChatObject, archiveCurrentChat } = deps;

  // [GET] /api/threads — Auth: requireAuth — Purpose: list user's active thread and full history for the sidebar
  router.get("/", requireAuth, (req, res) => {
    const db = openSql();
    try {
      const user = req.user;
      const activeSessions = db.prepare("SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 1 ORDER BY updated_at DESC").all(user.id);
      const historySessions = db.prepare("SELECT * FROM chat_sessions WHERE user_id = ? AND is_active = 0 ORDER BY pinned DESC, updated_at DESC").all(user.id);
      const active = activeSessions.length ? buildChatObject(db, activeSessions[0]) : null;
      const history = historySessions.map(s => buildChatObject(db, s));
      res.json({ active, history });
    } finally {
      db.close();
    }
  });

  // [DELETE] /api/threads/:id — Auth: requireAuth — Purpose: permanently delete a specific thread (user must own it)
  router.delete("/:id", requireAuth, (req, res) => {
    const db = openSql();
    try {
      const user = req.user;
      const session = db.prepare("SELECT id FROM chat_sessions WHERE id = ? AND user_id = ?").get(req.params.id, user.id);
      if (!session) return res.status(404).json({ error: "Thread not found" });
      db.prepare("DELETE FROM chat_messages WHERE session_id = ?").run(req.params.id);
      db.prepare("DELETE FROM chat_sessions WHERE id = ? AND user_id = ?").run(req.params.id, user.id);
      res.json({ ok: true });
    } finally {
      db.close();
    }
  });

  // [PATCH] /api/threads/:id — Auth: requireAuth — Purpose: update thread metadata (title, pinned, archived, unread)
  router.patch("/:id", requireAuth, (req, res) => {
    const db = openSql();
    try {
      const user = req.user;
      const session = db.prepare("SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?").get(req.params.id, user.id);
      if (!session) return res.status(404).json({ error: "Thread not found" });
      const now = new Date().toISOString();
      if (typeof req.body.title !== "undefined") db.prepare("UPDATE chat_sessions SET title = ? WHERE id = ?").run(req.body.title, req.params.id);
      if (typeof req.body.pinned !== "undefined") db.prepare("UPDATE chat_sessions SET pinned = ? WHERE id = ?").run(req.body.pinned ? 1 : 0, req.params.id);
      if (typeof req.body.archived !== "undefined") db.prepare("UPDATE chat_sessions SET archived = ? WHERE id = ?").run(req.body.archived ? 1 : 0, req.params.id);
      if (typeof req.body.unread !== "undefined") db.prepare("UPDATE chat_sessions SET unread = ? WHERE id = ?").run(req.body.unread ? 1 : 0, req.params.id);
      db.prepare("UPDATE chat_sessions SET updated_at = ? WHERE id = ?").run(now, req.params.id);
      const updated = db.prepare("SELECT * FROM chat_sessions WHERE id = ?").get(req.params.id);
      res.json({ ok: true, thread: buildChatObject(db, updated) });
    } finally {
      db.close();
    }
  });

  // [POST] /api/threads/:id/activate — Auth: requireAuth — Purpose: make a history thread the active thread (archives current)
  router.post("/:id/activate", requireAuth, (req, res) => {
    const db = openSql();
    try {
      const user = req.user;
      const target = db.prepare("SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?").get(req.params.id, user.id);
      if (!target) return res.status(404).json({ error: "Thread not found" });
      archiveCurrentChat(db, user.id);
      db.prepare("UPDATE chat_sessions SET is_active = 1, unread = 0, updated_at = ? WHERE id = ?").run(new Date().toISOString(), req.params.id);
      res.json({ ok: true });
    } finally {
      db.close();
    }
  });

  // [POST] /api/threads/:id/fork — Auth: requireAuth — Purpose: duplicate a thread's messages into a new active thread
  router.post("/:id/fork", requireAuth, (req, res) => {
    const db = openSql();
    try {
      const user = req.user;
      const src = db.prepare("SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?").get(req.params.id, user.id);
      if (!src) return res.status(404).json({ error: "Thread not found" });
      archiveCurrentChat(db, user.id);
      const now = new Date().toISOString();
      const newId = uid("chat");
      db.prepare("INSERT INTO chat_sessions (id, user_id, title, pinned, archived, unread, is_active, created_at, updated_at) VALUES (?, ?, ?, 0, 0, 0, 1, ?, ?)").run(
        newId, user.id, `Fork of ${src.title}`, now, now
      );
      const srcMsgs = db.prepare("SELECT * FROM chat_messages WHERE session_id = ? ORDER BY created_at ASC").all(src.id);
      for (const msg of srcMsgs) {
        db.prepare("INSERT INTO chat_messages (id, session_id, role, content, mode, model_id, model_name, route_reason, live, artifacts_json, extracted_files_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").run(
          uid("msg"), newId, msg.role, msg.content, msg.mode, msg.model_id, msg.model_name, msg.route_reason, msg.live, msg.artifacts_json, msg.extracted_files_json, msg.created_at
        );
      }
      res.json({ ok: true });
    } finally {
      db.close();
    }
  });

  return router;
};
