/**
 * @file server.js
 * @description Olla Nest — entry point.
 *
 * Responsibilities:
 *   - Requires the Express app and HTTP server from src/app.js
 *   - Starts the server and kicks off background Ollama sync
 *   - Uses Node.js cluster for multi-core utilisation (sessions are SQLite-backed)
 *
 * See src/app.js for the full application setup.
 */

const cluster = require('cluster');
const os = require('os');

if (cluster.isPrimary) {
  const numWorkers = Math.min(os.cpus().length, 4); // cap at 4
  console.log(`[cluster] Starting ${numWorkers} workers`);
  for (let i = 0; i < numWorkers; i++) cluster.fork();
  cluster.on('exit', (worker, code) => {
    console.error(`[cluster] Worker ${worker.process.pid} died (code ${code}). Restarting…`);
    cluster.fork();
  });
} else {
  const { server, migrateDocumentsJson, syncOllamaModels, openSql } = require("./src/app");
  const { initDatabase } = require("./src/db/index");
  const { PORT } = require("./src/config");
  const { runBackup } = require("./src/services/backup");

  // Wrap the entire worker startup in an async IIFE so we can await initDatabaseWithRetry
  (async () => {

  // Initialise database schema — retry up to 10 times with 1-second backoff.
  // The Docker volume mount can take a moment to become writable after container
  // start, causing "unable to open database file" on the very first open attempt.
  async function initDatabaseWithRetry(maxAttempts = 10, delayMs = 1000) {
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        initDatabase();
        console.log(`[startup] Database initialised (attempt ${attempt})`);
        return;
      } catch (err) {
        if (attempt === maxAttempts) {
          console.error(`[startup] Database init failed after ${maxAttempts} attempts:`, err.message);
          process.exit(1); // unrecoverable — exit so cluster restarts cleanly
        }
        console.error(`[startup] Database init attempt ${attempt}/${maxAttempts} failed (retrying in ${delayMs}ms):`, err.message);
        await new Promise(r => setTimeout(r, delayMs));
      }
    }
  }

  await initDatabaseWithRetry();

  server.listen(PORT, async () => {
    // Initial startup tasks
    let db;
    try {
      db = openSql();
      migrateDocumentsJson(db);
    } catch (err) {
      console.error("[startup] migrateDocumentsJson error (non-fatal):", err.message);
    } finally {
      try { db && db.close(); } catch (_) {}
    }

    // Initial Ollama sync then background refresh every 30 seconds.
    // Each tick opens and closes its own DB connection — completely independent
    // of any HTTP request lifecycle, no race conditions possible.
    async function runOllamaSync() {
      let syncDb;
      try {
        syncDb = openSql();
        await syncOllamaModels(syncDb);
      } catch (err) {
        // openSql() can throw disk I/O errors if the volume is temporarily unavailable.
        // Log and swallow so the worker stays alive — next tick will retry.
        console.error("[ollama-sync] DB open error (will retry):", err.message);
      } finally {
        try { syncDb && syncDb.close(); } catch (_) {}
      }
    }
    runOllamaSync(); // run immediately on boot
    setInterval(runOllamaSync, 30000); // then every 30 seconds

    // Daily SQLite backup — only worker 1 runs it to avoid concurrent VACUUM
    if (cluster.worker.id === 1) {
      runBackup(); // run once at boot
      setInterval(() => runBackup(), 24 * 60 * 60 * 1000);
    }

    console.log(`Olla Nest running at http://localhost:${PORT} (worker ${process.pid})`);
  });

  })().catch(err => {
    console.error("[worker] Fatal startup error:", err.message);
    process.exit(1);
  });
}
