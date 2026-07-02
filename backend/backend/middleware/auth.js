const jwt            = require('jsonwebtoken');
const crypto         = require('crypto');
const { db, logAccess } = require('../config/database');

const JWT_SECRET = process.env.JWT_SECRET || 'CHANGE_THIS_IN_PRODUCTION_MIN_32_CHARS!!';
const TOKEN_TTL  = process.env.TOKEN_TTL  || '24h';

function normalizeMac(raw) {
  if (!raw) return null;
  const clean = String(raw).replace(/[:\-.\s]/g, '').toUpperCase().trim();
  if (!/^[0-9A-F]{12}$/.test(clean)) return null;
  return clean.match(/.{2}/g).join(':');
}

function validateMac(mac) {
  const device = db.prepare('SELECT * FROM devices WHERE mac_address = ? COLLATE NOCASE').get(mac);
  if (!device)
    return { ok: false, code: 'NOT_FOUND', msg: 'Appareil non enregistré. Contactez votre administrateur.' };
  if (device.status === 'inactive')
    return { ok: false, code: 'INACTIVE', msg: 'Appareil non activé. Contactez votre administrateur.' };
  if (device.status === 'suspended')
    return { ok: false, code: 'SUSPENDED', msg: 'Appareil suspendu. Contactez le support.' };
  if (device.expiration_date && new Date(device.expiration_date) < new Date()) {
    db.prepare(`UPDATE devices SET status='inactive', updated_at=datetime('now') WHERE id=?`).run(device.id);
    return { ok: false, code: 'EXPIRED', msg: 'Abonnement expiré. Veuillez renouveler.' };
  }
  return { ok: true, device };
}

function requireMac(req, res, next) {
  const ip         = req.ip || req.socket?.remoteAddress;
  const deviceUuid = (req.headers['x-device-id'] || req.body?.device_id || '').trim() || null;
  const rawMac     = req.headers['x-mac-address'] || req.body?.mac || req.query?.mac;
  const mac        = normalizeMac(rawMac);

  // Nouveau client : identifié uniquement par Device UUID
  if (deviceUuid && !mac) {
    const device = db.prepare('SELECT * FROM devices WHERE device_uuid = ?').get(deviceUuid);
    if (!device) {
      logAccess({ mac: deviceUuid, ip, action: 'device_check', result: 'fail', detail: 'NOT_FOUND' });
      return res.status(403).json({ error: 'Appareil non enregistré. Contactez votre administrateur.', code: 'NOT_FOUND' });
    }
    if (device.status === 'inactive')
      return res.status(403).json({ error: 'Appareil non activé. Contactez votre administrateur.', code: 'INACTIVE' });
    if (device.status === 'suspended')
      return res.status(403).json({ error: 'Appareil suspendu. Contactez le support.', code: 'SUSPENDED' });
    if (device.expiration_date && new Date(device.expiration_date) < new Date()) {
      db.prepare(`UPDATE devices SET status='inactive', updated_at=datetime('now') WHERE id=?`).run(device.id);
      return res.status(403).json({ error: 'Abonnement expiré. Veuillez renouveler.', code: 'EXPIRED' });
    }
    req.device   = device;
    req.mac      = device.mac_address || deviceUuid;
    req.clientIp = ip;
    return next();
  }

  // Ancien client (ou migration) : identifié par MAC
  if (!mac) {
    logAccess({ mac: rawMac, ip, action: 'mac_check', result: 'fail', detail: 'invalid_format' });
    return res.status(400).json({ error: 'Adresse MAC manquante ou invalide.', code: 'INVALID_MAC' });
  }
  const check = validateMac(mac);
  if (!check.ok) {
    logAccess({ mac, ip, action: 'mac_check', result: 'fail', detail: check.code });
    return res.status(403).json({ error: check.msg, code: check.code, mac_address: mac });
  }

  // Migration : si un Device UUID est aussi fourni, on le sauvegarde sur l'appareil
  if (deviceUuid && !check.device.device_uuid) {
    db.prepare("UPDATE devices SET device_uuid=?, updated_at=datetime('now') WHERE id=?")
      .run(deviceUuid, check.device.id);
    check.device.device_uuid = deviceUuid;
  }

  req.device   = check.device;
  req.mac      = mac;
  req.clientIp = ip;
  next();
}

function requireToken(req, res, next) {
  const header = req.headers['authorization'] || '';
  const token  = header.startsWith('Bearer ') ? header.slice(7).trim() : null;
  if (!token) return res.status(401).json({ error: 'Token manquant.', code: 'NO_TOKEN' });
  try {
    const payload = jwt.verify(token, JWT_SECRET);
    const mac = normalizeMac(req.headers['x-mac-address'] || req.body?.mac);
    const deviceUuid = (req.headers['x-device-id'] || '').trim() || null;
    // Accepter MAC ou Device UUID comme identifiant
    const identifierOk = (mac && payload.mac === mac) || (deviceUuid && payload.deviceUuid === deviceUuid) || (deviceUuid && req.device?.device_uuid === deviceUuid);
    if (!identifierOk)
      return res.status(403).json({ error: 'Token/identifiant non concordants.', code: 'TOKEN_MAC_MISMATCH' });
    const dbToken = db.prepare('SELECT * FROM access_tokens WHERE token = ? AND revoked = 0').get(token);
    if (!dbToken) return res.status(401).json({ error: 'Token révoqué.', code: 'TOKEN_INVALID' });
    if (new Date(dbToken.expires_at) < new Date())
      return res.status(401).json({ error: 'Token expiré.', code: 'TOKEN_EXPIRED' });
    req.tokenRecord  = dbToken;
    req.tokenPayload = payload;
    db.prepare(`UPDATE sessions SET last_seen=datetime('now') WHERE token=? AND is_active=1`).run(token);
    next();
  } catch(e) {
    return res.status(401).json({ error: 'Token invalide.', code: 'TOKEN_INVALID' });
  }
}

function enforceConnections(req, res, next) {
  const device       = req.device;
  const currentToken = req.tokenRecord?.token || '';
  if (!device) return next();
  // GC stale sessions but never touch the current request's session
  db.prepare(`UPDATE sessions SET is_active=0 WHERE device_id=? AND is_active=1 AND token != ? AND datetime(last_seen,'+10 minutes') < datetime('now')`).run(device.id, currentToken);
  const { cnt } = db.prepare('SELECT COUNT(*) as cnt FROM sessions WHERE device_id=? AND is_active=1').get(device.id);
  if (cnt >= device.max_connections) {
    // Evict the oldest OTHER session — never the one handling this request
    const oldest = db.prepare('SELECT id, token FROM sessions WHERE device_id=? AND is_active=1 AND token != ? ORDER BY last_seen ASC LIMIT 1').get(device.id, currentToken);
    if (oldest) {
      db.prepare('UPDATE sessions SET is_active=0 WHERE id=?').run(oldest.id);
      db.prepare('UPDATE access_tokens SET revoked=1 WHERE token=?').run(oldest.token);
    }
  }
  next();
}

function requireAdmin(req, res, next) {
  const header = req.headers['authorization'] || '';
  const token  = header.startsWith('Bearer ') ? header.slice(7).trim() : null;
  if (!token) return res.status(401).json({ error: 'Token admin requis.' });
  try {
    const payload = jwt.verify(token, JWT_SECRET);
    if (!payload.adminId) return res.status(403).json({ error: 'Pas un token admin.' });
    const admin = db.prepare('SELECT * FROM admins WHERE id=?').get(payload.adminId);
    if (!admin) return res.status(403).json({ error: 'Admin introuvable.' });
    req.admin = admin;
    next();
  } catch(e) {
    return res.status(401).json({ error: 'Token admin invalide.' });
  }
}

function issueToken(device, mac) {
  db.prepare('UPDATE access_tokens SET revoked=1 WHERE device_id=?').run(device.id);
  db.prepare('UPDATE sessions SET is_active=0 WHERE device_id=?').run(device.id);
  const expiresAt = new Date(Date.now() + 86400000);
  const payload   = { mac, deviceId: device.id, plan: device.plan, deviceUuid: device.device_uuid || null };
  const token     = jwt.sign(payload, JWT_SECRET, { expiresIn: TOKEN_TTL });
  db.prepare('INSERT INTO access_tokens (device_id, token, expires_at) VALUES (?, ?, ?)').run(device.id, token, expiresAt.toISOString());
  return { token, expiresAt };
}

module.exports = { normalizeMac, validateMac, requireMac, requireToken, enforceConnections, requireAdmin, issueToken, JWT_SECRET };
