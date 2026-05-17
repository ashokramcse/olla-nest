/**
 * @file server.js
 * @description Olla Nest — entry point.
 *
 * Responsibilities:
 *   - Requires the Express app and HTTP server from src/app.js
 *   - Starts the server and kicks off background Ollama sync
 *
 * See src/app.js for the full application setup.
 */

const { server, migrateDocumentsJson, syncOllamaModels, openSql } = require("./src/app");
const { PORT } = require("./src/config");

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

  console.log(`Olla Nest running at http://localhost:${PORT}`);
});
