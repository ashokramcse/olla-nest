/**
 * @file src/routes/chat.js
 * @description Chat routes: /api/chat (non-stream), /api/chat/stream, /api/chat/clear,
 * /api/feedback, /api/chat DELETE.
 */

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql, one } = deps;
  const { requireAuth } = deps;
  const { publicUser, USER_SELECT, allowedModels } = deps;
  const { checkChatRateLimit, hasRight } = deps;
  const { uid } = deps;
  const { routeModel } = deps;
  const { resolveProvider, callProvider, callProviderStream } = deps;
  const { buildSystemPrompt, buildContextMessages, getActiveChat, getActiveChatSession, archiveCurrentChat, buildChatObject, appendAudit, appendTrace } = deps;
  const { workspaceForUser, writeLocalArtifacts, extractArtifacts, cleanModelOutput } = deps;
  const { detectSensitiveContent } = require("../services/router");

  // [POST] /api/chat — Auth: requireAuth — Purpose: non-streaming chat request (legacy/fallback); returns full response JSON
  router.post("/", requireAuth, async (req, res) => {
    const db = openSql();
    try {
      const user = publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.user.id));
      if (!hasRight(user, "chat:use")) return res.status(403).json({ error: "Chat access is not enabled for this account" });
      if (!checkChatRateLimit(req.user.id, user.apiRateLimitPerMinute)) {
        return res.status(429).json({ error: `Rate limit reached. You are limited to ${user.apiRateLimitPerMinute} messages per minute. Please wait before sending another message.` });
      }
      const { message, mode = "ask", manualModelId } = req.body;
      if (!message || !message.trim()) return res.status(400).json({ error: "Message is required" });
      if (String(message).length > 16000) return res.status(400).json({ error: "Message exceeds maximum length of 16,000 characters" });

      // Enforce daily token quota
      const todayUsage = db.prepare(`
        SELECT COALESCE(SUM(tokens_used),0) as total
        FROM chat_messages
        WHERE user_id = ? AND role = 'assistant'
        AND date(created_at) = date('now')
      `).get(user.id).total;
      if (user.dailyTokenLimit && todayUsage >= user.dailyTokenLimit) {
        return res.status(429).json({ error: "Daily token limit reached." });
      }

      const manualModel = manualModelId ? allowedModels(db, user).find((model) => model.id === manualModelId) : null;

      // If a manual model is selected, still run sensitive content detection
      if (manualModel) {
        const sensitivityResult = detectSensitiveContent(message, db);
        if (sensitivityResult.isSensitive && manualModel.privacy !== "local") {
          return res.status(403).json({ error: "Message contains sensitive content and cannot be sent to an external model. Please select a local model." });
        }
      }

      const route = manualModel
        ? { selected: manualModel, tags: ["manual"], candidates: [], reason: "User manually selected an approved model." }
        : routeModel(db, user, message, mode);

      if (!route.selected) return res.status(403).json({ error: route.reason });

      const workspace = workspaceForUser(db, user.id);
      const chat = getActiveChat(db, user.id);
      let content;
      let live = true;
      try {
        const provider = resolveProvider(db, route);
        const systemPrompt = await buildSystemPrompt(mode, route, workspace);
        const images = Array.isArray(req.body.images) ? req.body.images : [];
        const contextMsgs = await buildContextMessages(db, chat.id, systemPrompt, message, route.selected.model, images);
        const result = await callProvider(provider, route.selected.model, contextMsgs, { timeout: 300000 });
        content = result.content;
      } catch (error) {
        live = false;
        content = `Auto Router selected ${route.selected.name}, but the model call did not complete.\n\nReason: ${error.message}\n\nThe route itself is valid; check model availability, startup time, or admin configuration.`;
      }

      const writeApproved = Boolean(req.body.writeToWorkspace) || workspace.permissionMode === "full";
      const shouldWriteLocal = live && workspace.localWritesEnabled && writeApproved;
      const artifacts = shouldWriteLocal ? writeLocalArtifacts(db, workspace, message, mode, content) : [];

      /* Always extract artifact file contents so remote clients can write them locally */
      const extractedFiles = live ? extractArtifacts(content, message).map(a => ({
        name: a.name,
        content: a.content,
      })) : [];

      /* When files are available (saved or extracted), strip code blocks from chat */
      let chatContent = content;
      if (artifacts.length || extractedFiles.length) {
        chatContent = content
          .replace(/```[\s\S]*?```/g, "")
          .replace(/\n{3,}/g, "\n\n")
          .trim();
      }
      const now = new Date().toISOString();
      const userMsgId = uid("msg");
      const asstMsgId = uid("msg");
      db.exec("BEGIN");
      try {
        db.prepare("INSERT INTO chat_messages (id, session_id, role, content, mode, created_at) VALUES (?, ?, ?, ?, ?, ?)").run(userMsgId, chat.id, "user", message, mode, now);
        db.prepare("INSERT INTO chat_messages (id, session_id, role, content, model_id, model_name, route_reason, live, artifacts_json, extracted_files_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").run(
          asstMsgId, chat.id, "assistant", chatContent, route.selected.id, route.selected.name, route.reason, live ? 1 : 0, JSON.stringify(artifacts), JSON.stringify(extractedFiles), now
        );
        db.exec("COMMIT");
      } catch (txErr) {
        db.exec("ROLLBACK");
        throw txErr;
      }
      /* Auto-set title from first user message if still default */
      const currentSession = db.prepare("SELECT * FROM chat_sessions WHERE id = ?").get(chat.id);
      let title = currentSession?.title || "New Chat";
      if (!title || title === "New Chat" || title === "New workspace") {
        title = message.slice(0, 45).trim() + (message.length > 45 ? "…" : "");
      }
      db.prepare("UPDATE chat_sessions SET title = ?, updated_at = ? WHERE id = ?").run(title, now, chat.id);
      const updatedChat = buildChatObject(db, db.prepare("SELECT * FROM chat_sessions WHERE id = ?").get(chat.id));
      appendTrace({ userId: user.id, sessionId: chat.id, message, mode, selectedModelId: route.selected.id, tags: route.tags, candidates: route.candidates, live }, db);
      appendAudit(user.name, "chat.request", `${mode.toUpperCase()} routed to ${route.selected.name}`, { live, artifacts });
      res.json({ content, route, model: route.selected, live, artifacts, extractedFiles, chat: updatedChat });
    } finally {
      db.close();
    }
  });

  // [POST] /api/chat/stream — Auth: requireAuth — Purpose: primary chat endpoint; streams tokens via SSE then persists messages
  //   SSE event types: "routing" (model chosen), "token" (incremental text), "done" (final metadata), "error"
  router.post("/stream", requireAuth, async (req, res) => {
    const db = openSql();
    let dbClosed = false;
    const closeDb = () => { if (!dbClosed) { dbClosed = true; try { db.close(); } catch {} } };

    res.setHeader("Content-Type", "text/event-stream");
    res.setHeader("Cache-Control", "no-cache");
    res.setHeader("X-Accel-Buffering", "no");
    res.flushHeaders();

    const keepAlive = setInterval(() => { res.write(": keepalive\n\n"); }, 15000);

    const abort = new AbortController();
    res.on("close", () => { abort.abort(); clearInterval(keepAlive); });

    try {
      const user = publicUser(one(db, `SELECT ${USER_SELECT} FROM users WHERE id = ?`, req.user.id));
      if (!hasRight(user, "chat:use")) { res.write(`data: ${JSON.stringify({ type: "error", message: "Chat access not enabled" })}\n\n`); return res.end(); }
      if (!checkChatRateLimit(req.user.id, user.apiRateLimitPerMinute)) {
        res.write(`data: ${JSON.stringify({ type: "error", message: `Rate limit reached. You are limited to ${user.apiRateLimitPerMinute} messages per minute.` })}\n\n`);
        return res.end();
      }
      const { message, mode = "ask", manualModelId } = req.body;
      if (!message || !message.trim()) { res.write(`data: ${JSON.stringify({ type: "error", message: "Message is required" })}\n\n`); return res.end(); }
      if (String(message).length > 16000) { res.write(`data: ${JSON.stringify({ type: "error", message: "Message exceeds maximum length of 16,000 characters" })}\n\n`); return res.end(); }

      // Enforce daily token quota
      const todayUsage = db.prepare(`
        SELECT COALESCE(SUM(tokens_used),0) as total
        FROM chat_messages
        WHERE user_id = ? AND role = 'assistant'
        AND date(created_at) = date('now')
      `).get(user.id).total;
      if (user.dailyTokenLimit && todayUsage >= user.dailyTokenLimit) {
        res.write(`data: ${JSON.stringify({ type: "error", message: "Daily token limit reached." })}\n\n`);
        return res.end();
      }

      const manualModel = manualModelId ? allowedModels(db, user).find(m => m.id === manualModelId) : null;

      // If a manual model is selected, still run sensitive content detection
      if (manualModel) {
        const sensitivityResult = detectSensitiveContent(message, db);
        if (sensitivityResult.isSensitive && manualModel.privacy !== "local") {
          res.write(`data: ${JSON.stringify({ type: "error", message: "Message contains sensitive content and cannot be sent to an external model. Please select a local model." })}\n\n`);
          return res.end();
        }
      }

      const route = manualModel
        ? { selected: manualModel, tags: ["manual"], candidates: [], reason: "User manually selected an approved model.", privacyBlocked: false, sensitiveReasons: [] }
        : routeModel(db, user, message, mode);

      if (!route.selected) { res.write(`data: ${JSON.stringify({ type: "error", message: route.reason })}\n\n`); return res.end(); }

      res.write(`data: ${JSON.stringify({ type: "routing", model: route.selected.name, provider: route.selected.provider, reason: route.reason })}\n\n`);

      const workspace = workspaceForUser(db, user.id);
      const images = Array.isArray(req.body.images) ? req.body.images : [];

      // Load session history for context window
      const chatSession = getActiveChat(db, user.id);
      const systemPrompt = await buildSystemPrompt(mode, route, workspace);
      const messages = await buildContextMessages(db, chatSession.id, systemPrompt, message, route.selected.model, images);

      let fullContent = "";
      let tokensUsed = 0;
      let live = true;
      const startMs = Date.now();

      try {
        const provider = resolveProvider(db, route);
        await callProviderStream(provider, route.selected.model, messages, {}, (token) => {
          fullContent += token;
          res.write(`data: ${JSON.stringify({ type: "token", content: token })}\n\n`);
        }, (total) => { tokensUsed = total; }, abort.signal);
      } catch (err) {
        live = false;
        fullContent = `Auto Router selected ${route.selected.name}, but the model call did not complete.\n\nReason: ${err.message}`;
        res.write(`data: ${JSON.stringify({ type: "error", message: err.message })}\n\n`);
      }

      fullContent = cleanModelOutput(fullContent);
      const writeApproved = Boolean(req.body.writeToWorkspace) || workspace.permissionMode === "full";
      const shouldWriteLocal = live && workspace.localWritesEnabled && writeApproved;
      const artifacts = shouldWriteLocal ? writeLocalArtifacts(db, workspace, message, mode, fullContent) : [];
      const extractedFiles = live ? extractArtifacts(fullContent, message).map(a => ({ name: a.name, content: a.content })) : [];
      let chatContent = fullContent;
      if (artifacts.length || extractedFiles.length) {
        chatContent = fullContent.replace(/```[\s\S]*?```/g, "").replace(/\n{3,}/g, "\n\n").trim();
      }

      if (!dbClosed) {
        const chat = chatSession; // already fetched above for context loading
        const now = new Date().toISOString();
        const userMsgId = uid("msg");
        const asstMsgId = uid("msg");
        const latencyMs = Date.now() - startMs;
        db.exec("BEGIN");
        try {
          db.prepare("INSERT INTO chat_messages (id, session_id, role, content, mode, created_at) VALUES (?, ?, ?, ?, ?, ?)").run(userMsgId, chat.id, "user", message, mode, now);
          db.prepare("INSERT INTO chat_messages (id, session_id, role, content, model_id, model_name, route_reason, live, artifacts_json, extracted_files_json, tokens_used, latency_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").run(
            asstMsgId, chat.id, "assistant", chatContent, route.selected.id, route.selected.name, route.reason, live ? 1 : 0, JSON.stringify(artifacts), JSON.stringify(extractedFiles), tokensUsed, latencyMs, now
          );
          db.exec("COMMIT");
        } catch (txErr) {
          db.exec("ROLLBACK");
          throw txErr;
        }
        const currentSession = db.prepare("SELECT * FROM chat_sessions WHERE id = ?").get(chat.id);
        let title = currentSession?.title || "New Chat";
        if (!title || title === "New Chat") title = message.slice(0, 45).trim() + (message.length > 45 ? "…" : "");
        db.prepare("UPDATE chat_sessions SET title = ?, updated_at = ? WHERE id = ?").run(title, now, chat.id);
        appendTrace({ userId: user.id, sessionId: chat.id, message, mode, selectedModelId: route.selected.id, tags: route.tags, candidates: route.candidates, live }, db);
        appendAudit(user.name, "chat.stream", `${mode.toUpperCase()} streamed to ${route.selected.name}`, { live });
        res.write(`data: ${JSON.stringify({ type: "done", tokensUsed, latencyMs, messageId: asstMsgId, live, artifacts, extractedFiles })}\n\n`);
        return;
      }

      // DB was closed before we could persist — send done with no messageId so feedback is skipped
      const latencyMs = Date.now() - startMs;
      res.write(`data: ${JSON.stringify({ type: "done", tokensUsed, latencyMs, messageId: null, live: false, artifacts: [], extractedFiles: [] })}\n\n`);
    } catch (err) {
      res.write(`data: ${JSON.stringify({ type: "error", message: err.message })}\n\n`);
    } finally {
      clearInterval(keepAlive);
      closeDb();
      res.end();
    }
  });

  // [POST] /api/chat/clear — Auth: requireAuth — Purpose: archive the current chat and start a fresh active session
  router.post("/clear", requireAuth, (req, res) => {
    const db = openSql();
    try {
      archiveCurrentChat(db, req.user.id);
      getActiveChat(db, req.user.id);
      res.json({ ok: true });
    } finally {
      db.close();
    }
  });

  // [DELETE] /api/chat — Auth: requireAuth — Purpose: permanently delete the current active chat and create a fresh one
  router.delete("/", requireAuth, (req, res) => {
    const db = openSql();
    try {
      const session = getActiveChatSession(db, req.user.id);
      if (session) {
        db.prepare("DELETE FROM chat_messages WHERE session_id = ?").run(session.id);
        db.prepare("DELETE FROM chat_sessions WHERE id = ?").run(session.id);
      }
      getActiveChat(db, req.user.id);
      res.json({ ok: true });
    } finally {
      db.close();
    }
  });

  // [POST] /api/feedback — Auth: requireAuth — Purpose: submit thumbs-up/down rating for an assistant message
  router.post("/feedback", requireAuth, (req, res) => {
    const db = openSql();
    try {
      const { messageId, sessionId, rating, comment } = req.body;
      if (!messageId || !sessionId || (rating !== 1 && rating !== -1)) {
        return res.status(400).json({ error: "messageId, sessionId, and rating (1 or -1) are required" });
      }
      db.prepare("INSERT INTO feedback (id, message_id, session_id, user_id, rating, comment, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)").run(
        uid("fb"), messageId, sessionId, req.user.id, rating, comment || null, new Date().toISOString()
      );
      res.json({ ok: true });
    } finally {
      db.close();
    }
  });

  return router;
};
