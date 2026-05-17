/**
 * @file src/routes/admin/reports.js
 * @description Admin reports routes: /api/admin/reports, /api/admin/feedback.
 */

module.exports = function(deps) {
  const router = require("express").Router();
  const { openSql } = deps;
  const { requireAdmin } = deps;

  // [GET] /api/admin/reports — Auth: requireAdmin — Purpose: return aggregated analytics (daily activity, model usage,
  //   token leaderboard, mode breakdown, department usage, latency) for the Reports tab
  router.get("/reports", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const days = Number(req.query.days || 30);
      const since = new Date(Date.now() - days * 86400000).toISOString();

      // 1. Daily message & token activity (last N days)
      const dailyActivity = db.prepare(`
        SELECT substr(created_at,1,10) AS day,
               COUNT(*) AS messages,
               SUM(CASE WHEN role='assistant' THEN tokens_used ELSE 0 END) AS tokens
        FROM chat_messages
        WHERE created_at >= ?
        GROUP BY day ORDER BY day
      `).all(since);

      // 2. Model usage distribution
      const modelUsage = db.prepare(`
        SELECT model_name, COUNT(*) AS uses,
               SUM(tokens_used) AS total_tokens,
               AVG(latency_ms) AS avg_latency
        FROM chat_messages
        WHERE role='assistant' AND model_name IS NOT NULL AND model_name != ''
        GROUP BY model_name ORDER BY uses DESC LIMIT 10
      `).all();

      // 3. Token leaderboard (users ranked by total tokens)
      const tokenLeaderboard = db.prepare(`
        SELECT u.name, u.email, u.role, u.department_id,
               u.ai_access_tier, u.daily_token_limit,
               COUNT(DISTINCT cs.id) AS sessions,
               COUNT(cm.id) AS messages,
               COALESCE(SUM(cm.tokens_used),0) AS total_tokens,
               COALESCE(AVG(cm.tokens_used),0) AS avg_tokens_per_msg,
               MAX(cs.updated_at) AS last_active
        FROM users u
        LEFT JOIN chat_sessions cs ON cs.user_id = u.id
        LEFT JOIN chat_messages cm ON cm.session_id = cs.id AND cm.role = 'assistant'
        WHERE u.active = 1
        GROUP BY u.id ORDER BY total_tokens DESC LIMIT 20
      `).all();

      // 4. Mode breakdown (ask / build / fix / review etc.)
      const modeBreakdown = db.prepare(`
        SELECT mode, COUNT(*) AS count
        FROM router_traces WHERE created_at >= ? AND mode IS NOT NULL
        GROUP BY mode ORDER BY count DESC
      `).all(since);

      // 5. Department usage
      const deptUsage = db.prepare(`
        SELECT d.name AS dept,
               COUNT(DISTINCT cs.id) AS sessions,
               COALESCE(SUM(cm.tokens_used),0) AS tokens
        FROM departments d
        LEFT JOIN users u ON u.department_id = d.id
        LEFT JOIN chat_sessions cs ON cs.user_id = u.id AND cs.created_at >= ?
        LEFT JOIN chat_messages cm ON cm.session_id = cs.id AND cm.role = 'assistant'
        GROUP BY d.id ORDER BY tokens DESC
      `).all(since);

      // 6. Access tier user distribution
      const tierDist = db.prepare(`
        SELECT ai_access_tier AS tier, COUNT(*) AS count
        FROM users WHERE active = 1
        GROUP BY ai_access_tier ORDER BY count DESC
      `).all();

      // 7. Live vs failed responses
      const liveVsFailed = db.prepare(`
        SELECT SUM(CASE WHEN live=1 THEN 1 ELSE 0 END) AS live_count,
               SUM(CASE WHEN live=0 THEN 1 ELSE 0 END) AS failed_count
        FROM chat_messages WHERE role='assistant' AND created_at >= ?
      `).get(since);

      // 8. Audit event breakdown (top action types)
      const auditBreakdown = db.prepare(`
        SELECT action, COUNT(*) AS count
        FROM audit_events WHERE created_at >= ?
        GROUP BY action ORDER BY count DESC LIMIT 10
      `).all(since);

      // 9. Daily audit events over time
      const auditTimeline = db.prepare(`
        SELECT substr(created_at,1,10) AS day, COUNT(*) AS events
        FROM audit_events WHERE created_at >= ?
        GROUP BY day ORDER BY day
      `).all(since);

      // 10. Response latency by model (avg ms)
      const latencyByModel = db.prepare(`
        SELECT model_name,
               ROUND(AVG(latency_ms)) AS avg_ms,
               MIN(latency_ms) AS min_ms,
               MAX(latency_ms) AS max_ms,
               COUNT(*) AS count
        FROM chat_messages
        WHERE role='assistant' AND model_name IS NOT NULL AND model_name != ''
          AND latency_ms > 0
        GROUP BY model_name ORDER BY avg_ms ASC LIMIT 10
      `).all();

      // Summary stats
      const summary = db.prepare(`
        SELECT COUNT(DISTINCT u.id) AS total_users,
               COUNT(DISTINCT cs.id) AS total_sessions,
               COUNT(cm.id) AS total_messages,
               COALESCE(SUM(cm.tokens_used),0) AS total_tokens,
               ROUND(AVG(cm.latency_ms)) AS avg_latency
        FROM users u
        LEFT JOIN chat_sessions cs ON cs.user_id = u.id
        LEFT JOIN chat_messages cm ON cm.session_id = cs.id AND cm.role='assistant'
        WHERE u.active = 1
      `).get();

      res.json({
        summary,
        dailyActivity,
        modelUsage,
        tokenLeaderboard,
        modeBreakdown,
        deptUsage,
        tierDist,
        liveVsFailed: liveVsFailed || { live_count: 0, failed_count: 0 },
        auditBreakdown,
        auditTimeline,
        latencyByModel,
      });
    } finally {
      db.close();
    }
  });

  // [GET] /api/admin/feedback — Auth: requireAdmin — Purpose: aggregate thumbs ratings per model (model quality scorecard)
  router.get("/feedback", requireAdmin, (req, res) => {
    const db = openSql();
    try {
      const feedbackRows = db.prepare(`
        SELECT cm.model_name, f.rating, f.comment, f.created_at
        FROM feedback f
        LEFT JOIN chat_messages cm ON cm.id = f.message_id
        ORDER BY f.created_at DESC
      `).all();
      const models = {};
      for (const row of feedbackRows) {
        const key = row.model_name || "unknown";
        if (!models[key]) models[key] = { modelName: key, thumbsUp: 0, thumbsDown: 0, totalRatings: 0, comments: [] };
        models[key].totalRatings++;
        if (row.rating === 1) models[key].thumbsUp++;
        else models[key].thumbsDown++;
        if (row.comment && models[key].comments.length < 10) models[key].comments.push(row.comment);
      }
      res.json({ ok: true, feedback: Object.values(models) });
    } finally {
      db.close();
    }
  });

  return router;
};
