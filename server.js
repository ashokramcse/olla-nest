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

  // Initialise database schema and seed defaults once before accepting requests
  initDatabase();

  server.listen(PORT, async () => {
    // Initial startup tasks
    const db = openSql();
    try {
      migrateDocumentsJson(db);
    } finally {
      db.close();
    }

    // Initial Ollama sync then background refresh every 30 seconds.
    // Each tick opens and closes its own DB connection — completely independent
    // of any HTTP request lifecycle, no race conditions possible.
    async function runOllamaSync() {
      const syncDb = openSql();
      try {
        await syncOllamaModels(syncDb);
      } catch (_) {
        // unreachable — syncOllamaModels catches internally
      } finally {
        syncDb.close();
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
}
