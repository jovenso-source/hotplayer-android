const Database = require('better-sqlite3');
const bcrypt   = require('bcryptjs');
const path     = require('path');

const DB_PATH = process.env.DB_PATH || path.join(__dirname, '../hotplayer.db');
const db      = new Database(DB_PATH);

db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

function initDB() {
  db.exec(`
    CREATE TABLE IF NOT EXISTS devices (
      id               INTEGER PRIMARY KEY AUTOINCREMENT,
      mac_address      TEXT    UNIQUE COLLATE NOCASE,
      device_uuid      TEXT    UNIQUE,
      fingerprint      TEXT    DEFAULT NULL,
      label            TEXT    DEFAULT '',
      status           TEXT    NOT NULL DEFAULT 'inactive'
                                CHECK(status IN ('active','inactive','suspended','trial')),
      plan             TEXT    NOT NULL DEFAULT 'monthly'
                                CHECK(plan IN ('trial','monthly','quarterly','yearly','lifetime')),
      max_connections  INTEGER NOT NULL DEFAULT 1,
      expiration_date  TEXT    DEFAULT NULL,
      playlist_url     TEXT    DEFAULT NULL,
      xtream_server    TEXT    DEFAULT NULL,
      xtream_user      TEXT    DEFAULT NULL,
      xtream_pass      TEXT    DEFAULT NULL,
      notes            TEXT    DEFAULT '',
      created_at       TEXT    NOT NULL DEFAULT (datetime('now')),
      updated_at       TEXT    NOT NULL DEFAULT (datetime('now')),
      activated_at     TEXT    DEFAULT NULL
    );

    CREATE TABLE IF NOT EXISTS access_tokens (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      device_id   INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      token       TEXT    NOT NULL UNIQUE,
      issued_at   TEXT    NOT NULL DEFAULT (datetime('now')),
      expires_at  TEXT    NOT NULL,
      revoked     INTEGER NOT NULL DEFAULT 0
    );

    CREATE TABLE IF NOT EXISTS sessions (
      id           INTEGER PRIMARY KEY AUTOINCREMENT,
      device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      token        TEXT    NOT NULL,
      ip_address   TEXT,
      user_agent   TEXT,
      started_at   TEXT    NOT NULL DEFAULT (datetime('now')),
      last_seen    TEXT    NOT NULL DEFAULT (datetime('now')),
      is_active    INTEGER NOT NULL DEFAULT 1
    );

    CREATE TABLE IF NOT EXISTS access_log (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      mac_address TEXT,
      ip_address  TEXT,
      action      TEXT,
      result      TEXT,
      detail      TEXT    DEFAULT '',
      created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
    );

    CREATE TABLE IF NOT EXISTS admins (
      id            INTEGER PRIMARY KEY AUTOINCREMENT,
      username      TEXT    NOT NULL UNIQUE,
      password_hash TEXT    NOT NULL,
      role          TEXT    NOT NULL DEFAULT 'admin',
      created_at    TEXT    NOT NULL DEFAULT (datetime('now')),
      last_login    TEXT    DEFAULT NULL
    );

    CREATE TABLE IF NOT EXISTS popups (
      id              INTEGER PRIMARY KEY AUTOINCREMENT,
      popup_id        TEXT    NOT NULL UNIQUE,
      type            TEXT    NOT NULL DEFAULT 'announcement',
      title           TEXT    NOT NULL,
      message         TEXT    NOT NULL DEFAULT '',
      force           INTEGER NOT NULL DEFAULT 0,
      new_version     TEXT    DEFAULT NULL,
      current_version TEXT    DEFAULT NULL,
      download_url    TEXT    DEFAULT NULL,
      button_ok       TEXT    DEFAULT 'Fermer',
      button_action   TEXT    DEFAULT NULL,
      auto_close_sec  INTEGER DEFAULT NULL,
      is_active       INTEGER NOT NULL DEFAULT 0,
      created_at      TEXT    NOT NULL DEFAULT (datetime('now')),
      updated_at      TEXT    NOT NULL DEFAULT (datetime('now'))
    );

    CREATE INDEX IF NOT EXISTS idx_devices_mac     ON devices(mac_address);
    CREATE INDEX IF NOT EXISTS idx_devices_uuid    ON devices(device_uuid);
    CREATE INDEX IF NOT EXISTS idx_devices_status  ON devices(status);
    CREATE INDEX IF NOT EXISTS idx_sessions_device ON sessions(device_id, is_active);
    CREATE INDEX IF NOT EXISTS idx_tokens_token    ON access_tokens(token);
  `);

  const exists = db.prepare('SELECT id FROM admins WHERE username = ?').get('admin');
  if (!exists) {
    const pwd  = process.env.ADMIN_PASSWORD || 'Admin@1234!';
    const hash = bcrypt.hashSync(pwd, 12);
    db.prepare(`INSERT INTO admins (username, password_hash, role) VALUES ('admin', ?, 'superadmin')`).run(hash);
    console.log('Admin créé → user: admin | pass:', pwd);
  }
}

function logAccess({ mac = null, ip = null, action, result, detail = '' }) {
  db.prepare(`INSERT INTO access_log (mac_address, ip_address, action, result, detail) VALUES (?, ?, ?, ?, ?)`)
    .run(mac, ip, action, result, detail);
}

module.exports = { db, initDB, logAccess };
