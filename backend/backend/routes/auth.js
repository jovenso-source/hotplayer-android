const router = require('express').Router();
const { db, logAccess } = require('../config/database');
const { requireMac, requireToken, enforceConnections, issueToken } = require('../middleware/auth');

router.post('/activate', requireMac, enforceConnections, (req, res) => {
  const { device, mac, clientIp } = req;
  db.prepare(`UPDATE devices SET activated_at=COALESCE(activated_at,datetime('now')), updated_at=datetime('now') WHERE id=?`).run(device.id);
  const { token, expiresAt } = issueToken(device, mac);
  const sessionId = db.prepare(`INSERT INTO sessions (device_id, token, ip_address, user_agent) VALUES (?, ?, ?, ?)`).run(device.id, token, clientIp, req.headers['user-agent'] || '').lastInsertRowid;
  logAccess({ mac, ip: clientIp, action: 'activate', result: 'success', detail: `session:${sessionId}` });
  let playlist = null;
  if (device.playlist_url) {
    playlist = { type: 'm3u', url: device.playlist_url };
  } else if (device.xtream_server) {
    playlist = { type: 'xtream', server: device.xtream_server, user: device.xtream_user, pass: device.xtream_pass };
  }
  return res.json({
    success: true, token,
    expires_at: expiresAt.toISOString(),
    session_id: String(sessionId),
    device_id: device.device_uuid || null,
    device: {
      label: device.label, plan: device.plan,
      status: device.status, expiration_date: device.expiration_date,
      max_connections: device.max_connections
    },
    playlist
  });
});

router.post('/heartbeat', requireMac, requireToken, (req, res) => {
  const { changes } = db.prepare(`UPDATE sessions SET last_seen=datetime('now') WHERE token=? AND is_active=1`).run(req.tokenRecord.token);
  if (changes === 0) {
    // Session was GC'd — recreate it so the device shows as online
    db.prepare(`INSERT INTO sessions (device_id, token, ip_address, user_agent, is_active) VALUES (?, ?, ?, ?, 1)`)
      .run(req.device.id, req.tokenRecord.token, req.clientIp, req.headers['user-agent'] || '');
  }
  return res.json({ ok: true, server_time: new Date().toISOString() });
});

router.post('/logout', requireMac, requireToken, (req, res) => {
  db.prepare('UPDATE access_tokens SET revoked=1 WHERE token=?').run(req.tokenRecord.token);
  db.prepare('UPDATE sessions SET is_active=0 WHERE token=?').run(req.tokenRecord.token);
  logAccess({ mac: req.mac, ip: req.clientIp, action: 'logout', result: 'success' });
  return res.json({ ok: true });
});

router.get('/status', requireMac, (req, res) => {
  const d = req.device;
  return res.json({ activated: true, status: d.status, plan: d.plan, expiration_date: d.expiration_date, label: d.label });
});

module.exports = router;
