/**
 * @file src/services/backup.js
 * @description SQLite backup service using VACUUM INTO for safe online backups.
 * Keeps the last 7 backups in DATA_DIR/backups/.
 */

const path = require('path');
const fs = require('fs');
const { DatabaseSync } = require('node:sqlite');
const { DATA_DIR } = require('../config');

const DB_PATH = path.join(DATA_DIR, 'olla-nest.sqlite');
const BACKUP_DIR = path.join(DATA_DIR, 'backups');

function runBackup() {
  if (!fs.existsSync(BACKUP_DIR)) fs.mkdirSync(BACKUP_DIR, { recursive: true });
  const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const dest = path.join(BACKUP_DIR, `olla-nest-${ts}.sqlite`);
  // Use SQLite VACUUM INTO for safe online backup
  const db = new DatabaseSync(DB_PATH);
  try {
    db.exec(`VACUUM INTO '${dest}'`);
    // Keep only last 7 backups
    const files = fs.readdirSync(BACKUP_DIR)
      .filter(f => f.endsWith('.sqlite'))
      .sort();
    while (files.length > 7) {
      fs.unlinkSync(path.join(BACKUP_DIR, files.shift()));
    }
    console.log(`[backup] Backup written to ${dest}`);
    return { ok: true, file: dest };
  } catch (err) {
    console.error(`[backup] Backup failed: ${err.message}`);
    return { ok: false, error: err.message };
  } finally {
    db.close();
  }
}

module.exports = { runBackup, BACKUP_DIR };
