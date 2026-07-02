const router  = require('express').Router();
const jwt     = require('jsonwebtoken');
const bcrypt  = require('bcryptjs');
const { db, logAccess } = require('../config/database');
const { requireAdmin, normalizeMac, JWT_SECRET } = require('../middleware/auth');

router.post('/login', (req, res) => {
  const { username, password } = req.body || {};
  if (!username || !password)
    return res.status(400).json({ error: 'Identifiants requis.' });
  const admin = db.prepare('SELECT * FROM admins WHERE username=?').get(username);
  if (!admin || !bcrypt.compareSync(password, admin.password_hash))
    return res.status(401).json({ error: 'Identifiants incorrects.' });
  db.prepare(`UPDATE admins SET last_login=datetime('now') WHERE id=?`).run(admin.id);
  const token = jwt.sign({ adminId: admin.id, username: admin.username, role: admin.role }, JWT_SECRET, { expiresIn: '8h' });
  return res.json({ token, role: admin.role, username: admin.username });
});

router.get('/devices', requireAdmin, (req, res) => {
  const { status, search, page = 1, limit = 50 } = req.query;
  const offset = (parseInt(page) - 1) * parseInt(limit);
  const params = []; let where = '1=1';
  if (status) { where += ' AND d.status=?'; params.push(status); }
  if (search) { where += ' AND (d.mac_address LIKE ? OR d.label LIKE ?)'; params.push(`%${search}%`, `%${search}%`); }
  const devices = db.prepare(`SELECT d.*, (SELECT COUNT(*) FROM sessions s WHERE s.device_id=d.id AND s.is_active=1 AND s.last_seen >= datetime('now','-10 minutes')) AS active_connections FROM devices d WHERE ${where} ORDER BY d.created_at DESC LIMIT ? OFFSET ?`).all(...params, parseInt(limit), offset);
  const { cnt: total } = db.prepare(`SELECT COUNT(*) as cnt FROM devices d WHERE ${where}`).get(...params);
  return res.json({ devices, total, page: parseInt(page), pages: Math.ceil(total / limit) });
});

router.post('/devices/import', requireAdmin, (req, res) => {
  const { devices } = req.body || {};
  if (!Array.isArray(devices) || !devices.length)
    return res.status(400).json({ error: 'Aucun appareil fourni.' });

  const VALID_STATUS = ['active','inactive','suspended','trial'];
  const VALID_PLAN   = ['trial','monthly','quarterly','yearly','lifetime'];
  const stmt = db.prepare(`INSERT INTO devices (mac_address,label,plan,max_connections,expiration_date,playlist_url,xtream_server,xtream_user,xtream_pass,status,notes) VALUES (?,?,?,?,?,?,?,?,?,?,?)`);

  let created = 0, skipped = 0;
  const errors = [];

  for (const row of devices) {
    const mac = normalizeMac(row.mac_address);
    if (!mac) { errors.push({ row: row.mac_address || '?', reason: 'MAC invalide' }); continue; }
    if (db.prepare('SELECT id FROM devices WHERE mac_address=?').get(mac)) { skipped++; continue; }
    try {
      stmt.run(
        mac,
        row.label        || '',
        VALID_PLAN.includes(row.plan)     ? row.plan   : 'monthly',
        parseInt(row.max_connections) > 0 ? parseInt(row.max_connections) : 1,
        row.expiration_date || null,
        row.playlist_url   || null,
        row.xtream_server  || null,
        row.xtream_user    || null,
        row.xtream_pass    || null,
        VALID_STATUS.includes(row.status) ? row.status : 'active',
        row.notes          || ''
      );
      created++;
    } catch (e) { errors.push({ row: mac, reason: e.message }); }
  }

  logAccess({ mac: null, ip: req.ip, action: 'admin_import', result: 'success',
    detail: `admin:${req.admin.username} created:${created} skipped:${skipped} errors:${errors.length}` });
  return res.json({ created, skipped, errors, total: devices.length });
});

router.post('/devices', requireAdmin, (req, res) => {
  const { mac_address, label='', plan='monthly', max_connections=1, expiration_date=null, playlist_url=null, xtream_server=null, xtream_user=null, xtream_pass=null, status='active', notes='' } = req.body || {};
  const mac = normalizeMac(mac_address);
  if (!mac) return res.status(400).json({ error: 'Format MAC invalide.' });
  const dup = db.prepare('SELECT id FROM devices WHERE mac_address=?').get(mac);
  if (dup) return res.status(409).json({ error: 'MAC déjà enregistré.', id: dup.id });
  const { lastInsertRowid: id } = db.prepare(`INSERT INTO devices (mac_address,label,plan,max_connections,expiration_date,playlist_url,xtream_server,xtream_user,xtream_pass,status,notes) VALUES (?,?,?,?,?,?,?,?,?,?,?)`).run(mac, label, plan, max_connections, expiration_date, playlist_url, xtream_server, xtream_user, xtream_pass, status, notes);
  logAccess({ mac, ip: req.ip, action: 'admin_add', result: 'success', detail: `admin:${req.admin.username}` });
  return res.status(201).json({ id, mac_address: mac, message: 'Appareil enregistré.' });
});

router.get('/devices/:id', requireAdmin, (req, res) => {
  const device = db.prepare('SELECT * FROM devices WHERE id=?').get(req.params.id);
  if (!device) return res.status(404).json({ error: 'Introuvable.' });
  const sessions = db.prepare('SELECT * FROM sessions WHERE device_id=? ORDER BY started_at DESC LIMIT 20').all(device.id);
  const logs     = db.prepare('SELECT * FROM access_log WHERE mac_address=? ORDER BY created_at DESC LIMIT 50').all(device.mac_address);
  return res.json({ device, sessions, logs });
});

router.patch('/devices/:id', requireAdmin, (req, res) => {
  const device = db.prepare('SELECT * FROM devices WHERE id=?').get(req.params.id);
  if (!device) return res.status(404).json({ error: 'Introuvable.' });
  const allowed = ['label','status','plan','max_connections','expiration_date','playlist_url','xtream_server','xtream_user','xtream_pass','notes'];
  const sets = []; const vals = [];
  allowed.forEach(f => { if (req.body[f] !== undefined) { sets.push(`${f}=?`); vals.push(req.body[f] || null); } });
  if (!sets.length) return res.status(400).json({ error: 'Rien à mettre à jour.' });
  sets.push(`updated_at=datetime('now')`); vals.push(device.id);
  db.prepare(`UPDATE devices SET ${sets.join(',')} WHERE id=?`).run(...vals);
  return res.json({ message: 'Mis à jour.' });
});

router.delete('/devices/:id', requireAdmin, (req, res) => {
  const device = db.prepare('SELECT * FROM devices WHERE id=?').get(req.params.id);
  if (!device) return res.status(404).json({ error: 'Introuvable.' });
  db.prepare('DELETE FROM devices WHERE id=?').run(device.id);
  return res.json({ message: 'Supprimé.' });
});

router.post('/devices/:id/activate', requireAdmin, (req, res) => {
  const { expiration_date } = req.body || {};
  const device = db.prepare('SELECT id,mac_address FROM devices WHERE id=?').get(req.params.id);
  if (!device) return res.status(404).json({ error: 'Introuvable.' });
  db.prepare(`UPDATE devices SET status='active', expiration_date=?, activated_at=COALESCE(activated_at,datetime('now')), updated_at=datetime('now') WHERE id=?`).run(expiration_date || null, device.id);
  return res.json({ message: 'Activé.', expiration_date: expiration_date || null });
});

router.post('/devices/:id/deactivate', requireAdmin, (req, res) => {
  const device = db.prepare('SELECT id,mac_address FROM devices WHERE id=?').get(req.params.id);
  if (!device) return res.status(404).json({ error: 'Introuvable.' });
  db.prepare(`UPDATE devices SET status='inactive', updated_at=datetime('now') WHERE id=?`).run(device.id);
  db.prepare('UPDATE sessions SET is_active=0 WHERE device_id=?').run(device.id);
  db.prepare('UPDATE access_tokens SET revoked=1 WHERE device_id=?').run(device.id);
  return res.json({ message: 'Désactivé. Toutes les sessions terminées.' });
});

router.post('/devices/:id/reset-mac', requireAdmin, (req, res) => {
  const device = db.prepare('SELECT * FROM devices WHERE id=?').get(req.params.id);
  if (!device) return res.status(404).json({ error: 'Introuvable.' });
  const newMac = normalizeMac(req.body?.new_mac);
  if (!newMac) return res.status(400).json({ error: 'MAC invalide.' });
  const dup = db.prepare('SELECT id FROM devices WHERE mac_address=? AND id!=?').get(newMac, device.id);
  if (dup) return res.status(409).json({ error: 'MAC déjà utilisé.' });
  db.prepare(`UPDATE devices SET mac_address=?, fingerprint=NULL, updated_at=datetime('now') WHERE id=?`).run(newMac, device.id);
  db.prepare('UPDATE access_tokens SET revoked=1 WHERE device_id=?').run(device.id);
  db.prepare('UPDATE sessions SET is_active=0 WHERE device_id=?').run(device.id);
  return res.json({ message: 'MAC réinitialisé.', old_mac: device.mac_address, new_mac: newMac });
});

router.get('/sessions', requireAdmin, (req, res) => {
  const rows = db.prepare(`SELECT s.*, d.mac_address, d.label, d.plan FROM sessions s JOIN devices d ON s.device_id=d.id WHERE s.is_active=1 AND s.last_seen >= datetime('now','-10 minutes') ORDER BY s.last_seen DESC`).all();
  return res.json({ sessions: rows, count: rows.length });
});

router.delete('/sessions/:id', requireAdmin, (req, res) => {
  const s = db.prepare('SELECT * FROM sessions WHERE id=?').get(req.params.id);
  if (!s) return res.status(404).json({ error: 'Session introuvable.' });
  db.prepare('UPDATE sessions SET is_active=0 WHERE id=?').run(s.id);
  db.prepare('UPDATE access_tokens SET revoked=1 WHERE token=?').run(s.token);
  return res.json({ message: 'Session terminée.' });
});

router.get('/stats', requireAdmin, (req, res) => {
  return res.json({
    devices: {
      total    : db.prepare('SELECT COUNT(*) as n FROM devices').get().n,
      active   : db.prepare(`SELECT COUNT(*) as n FROM devices WHERE status='active'`).get().n,
      inactive : db.prepare(`SELECT COUNT(*) as n FROM devices WHERE status='inactive'`).get().n,
      suspended: db.prepare(`SELECT COUNT(*) as n FROM devices WHERE status='suspended'`).get().n,
      expiring_soon: db.prepare(`SELECT COUNT(*) as n FROM devices WHERE status='active' AND expiration_date IS NOT NULL AND date(expiration_date) <= date('now','+7 days')`).get().n,
    },
    connections: {
      active_now : db.prepare(`SELECT COUNT(*) as n FROM sessions WHERE is_active=1 AND last_seen >= datetime('now','-10 minutes')`).get().n,
      total_open : db.prepare(`SELECT COUNT(*) as n FROM sessions WHERE is_active=1`).get().n,
    },
    logs: {
      activations_today: db.prepare(`SELECT COUNT(*) as n FROM access_log WHERE action='activate' AND date(created_at)=date('now')`).get().n,
      denied_today     : db.prepare(`SELECT COUNT(*) as n FROM access_log WHERE result='fail' AND date(created_at)=date('now')`).get().n,
    },
    recent_activity: db.prepare('SELECT * FROM access_log ORDER BY created_at DESC LIMIT 25').all(),
  });
});

router.get('/logs', requireAdmin, (req, res) => {
  const { mac, action, result, limit = 100 } = req.query;
  const params = []; let where = '1=1';
  if (mac)    { where += ' AND mac_address=?'; params.push(normalizeMac(mac) || mac); }
  if (action) { where += ' AND action=?';      params.push(action); }
  if (result) { where += ' AND result=?';      params.push(result); }
  const logs = db.prepare(`SELECT * FROM access_log WHERE ${where} ORDER BY created_at DESC LIMIT ?`).all(...params, parseInt(limit));
  return res.json({ logs, count: logs.length });
});

// ── Popup / Notification management ──────────────────────────────────────────

router.get('/popups', requireAdmin, (req, res) => {
  const popups = db.prepare('SELECT * FROM popups ORDER BY created_at DESC').all();
  return res.json({ popups });
});

router.post('/popups/deactivate', requireAdmin, (req, res) => {
  db.prepare('UPDATE popups SET is_active=0').run();
  return res.json({ message: 'Tous les popups désactivés.' });
});

router.post('/popups/:id/activate', requireAdmin, (req, res) => {
  const popup = db.prepare('SELECT id FROM popups WHERE id=?').get(req.params.id);
  if (!popup) return res.status(404).json({ error: 'Popup introuvable.' });
  db.prepare('UPDATE popups SET is_active=0').run();
  db.prepare(`UPDATE popups SET is_active=1, updated_at=datetime('now') WHERE id=?`).run(popup.id);
  return res.json({ message: 'Popup activé.' });
});

router.post('/popups', requireAdmin, (req, res) => {
  const { popup_id, type = 'announcement', title, message = '', force = false,
          new_version, current_version, download_url,
          button_ok = 'Fermer', button_action, auto_close_sec } = req.body || {};
  if (!popup_id || !title) return res.status(400).json({ error: 'popup_id et title sont requis.' });
  const dup = db.prepare('SELECT id FROM popups WHERE popup_id=?').get(popup_id);
  if (dup) return res.status(409).json({ error: 'Un popup avec cet ID existe déjà.' });
  const { lastInsertRowid: id } = db.prepare(
    `INSERT INTO popups (popup_id,type,title,message,force,new_version,current_version,download_url,button_ok,button_action,auto_close_sec)
     VALUES (?,?,?,?,?,?,?,?,?,?,?)`
  ).run(popup_id, type, title, message, force ? 1 : 0,
        new_version || null, current_version || null, download_url || null,
        button_ok, button_action || null, auto_close_sec || null);
  return res.status(201).json({ id, message: 'Popup créé.' });
});

router.patch('/popups/:id', requireAdmin, (req, res) => {
  const popup = db.prepare('SELECT * FROM popups WHERE id=?').get(req.params.id);
  if (!popup) return res.status(404).json({ error: 'Popup introuvable.' });
  const allowed = ['popup_id','type','title','message','force','new_version','current_version',
                   'download_url','button_ok','button_action','auto_close_sec'];
  const sets = []; const vals = [];
  allowed.forEach(f => {
    if (req.body[f] !== undefined) {
      sets.push(`${f}=?`);
      vals.push(f === 'force' ? (req.body[f] ? 1 : 0) : (req.body[f] || null));
    }
  });
  if (!sets.length) return res.status(400).json({ error: 'Rien à mettre à jour.' });
  sets.push(`updated_at=datetime('now')`);
  vals.push(popup.id);
  db.prepare(`UPDATE popups SET ${sets.join(',')} WHERE id=?`).run(...vals);
  return res.json({ message: 'Popup mis à jour.' });
});

router.delete('/popups/:id', requireAdmin, (req, res) => {
  const popup = db.prepare('SELECT id FROM popups WHERE id=?').get(req.params.id);
  if (!popup) return res.status(404).json({ error: 'Popup introuvable.' });
  db.prepare('DELETE FROM popups WHERE id=?').run(popup.id);
  return res.json({ message: 'Popup supprimé.' });
});

module.exports = router;
