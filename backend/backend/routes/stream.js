const router = require('express').Router();
const { db, logAccess } = require('../config/database');
const { requireMac, requireToken, enforceConnections } = require('../middleware/auth');

router.get('/playlist', requireMac, requireToken, enforceConnections, (req, res) => {
  const device = req.device;
  db.prepare(`UPDATE sessions SET last_seen=datetime('now') WHERE token=? AND is_active=1`).run(req.tokenRecord.token);
  if (!device.playlist_url && !device.xtream_server)
    return res.status(404).json({ error: 'Aucune playlist assignée.', code: 'NO_PLAYLIST' });
  logAccess({ mac: req.mac, ip: req.clientIp, action: 'stream_start', result: 'success' });
  if (device.playlist_url)
    return res.json({ type: 'm3u', url: device.playlist_url });
  return res.json({ type: 'xtream', server: device.xtream_server, user: device.xtream_user, pass: device.xtream_pass });
});

router.post('/stop', requireMac, requireToken, (req, res) => {
  db.prepare('UPDATE sessions SET is_active=0 WHERE token=?').run(req.tokenRecord.token);
  logAccess({ mac: req.mac, ip: req.clientIp, action: 'stream_stop', result: 'success' });
  return res.json({ ok: true });
});

module.exports = router;
